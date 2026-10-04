package com.zminesweeper.game.mp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zminesweeper.game.Difficulty
import com.zminesweeper.game.GameMode
import com.zminesweeper.game.R
import com.zminesweeper.game.SaveManager
import com.zminesweeper.game.net.Message
import com.zminesweeper.game.net.WebSocketRelay
import com.zminesweeper.game.net.deduplicateNickname
import org.json.JSONArray
import org.json.JSONObject

/**
 * Лобби хоста на relay-сервере (кроссплат: Android ↔ HTML).
 *
 * Хост вводит URL сервера → жмёт «Создать комнату» → получает код (6 символов).
 * Клиенты (Android или HTML) подключаются по этому коду.
 *
 * Хост выбирает режим/сложность/интервал сдвига. Когда готов — жмёт «Начать игру».
 *
 * Архитектура host-authoritative: хост держит GameEngine, шлёт BROADCAST (STATE/START/OVER),
 * клиенты шлют SEND_TO_HOST (CLICK/FLAG/CHORD).
 */
class RelayHostActivity : AppCompatActivity() {

    private lateinit var save: SaveManager
    private var relay: WebSocketRelay? = null
    private val handler = Handler(Looper.getMainLooper())

    private val players = LinkedHashMap<Int, Message.PlayerInfo>()
    private val hostId: Int = 0
    private var hostNickname: String = "Хост"

    private var selectedMode: GameMode = GameMode.CLASSIC
    private var selectedDiff: Difficulty = Difficulty.BEGINNER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_relay_host)
        save = SaveManager(this)

        hostNickname = save.nickname() ?: "Хост"
        hostNickname = deduplicateNickname(hostNickname, emptyList())
        players[hostId] = Message.PlayerInfo(hostId, hostNickname, true)

        findViewById<EditText>(R.id.etRelayHostUrl).apply {
            setText(save.lastRelayUrl() ?: "")
            hint = "wss://your-relay.onrender.com"
        }
        findViewById<Button>(R.id.btnRelayHostCreate).setOnClickListener { createRoom() }

        findViewById<Button>(R.id.btnHostNick).apply {
            text = "Ник: $hostNickname"
            setOnClickListener { promptNickname() }
        }
        findViewById<Button>(R.id.btnPickMode).apply {
            text = "Режим: ${selectedMode.display}"
            setOnClickListener { pickMode() }
        }
        findViewById<Button>(R.id.btnPickDiff).apply {
            text = "Сложность: ${selectedDiff.display}"
            setOnClickListener { pickDifficulty() }
        }
        findViewById<Button>(R.id.btnPickShift).apply {
            text = "Сдвиг: ${save.shiftInterval()} сек"
            setOnClickListener { pickShiftInterval() }
        }

        findViewById<Button>(R.id.btnStartMp).setOnClickListener { startGame() }
        findViewById<Button>(R.id.btnCloseLobby).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Закрыть лобби?")
                .setMessage("Игроки будут отключены.")
                .setPositiveButton("Закрыть") { _, _ ->
                    relay?.broadcast(Message.Goodbye)
                    relay?.send(JSONObject().apply { put("t", "LEAVE") })
                    relay?.disconnect()
                    relay = null
                    finish()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        refreshPlayerList()
    }

    private fun createRoom() {
        val url = findViewById<EditText>(R.id.etRelayHostUrl).text.toString().trim()
        if (url.isEmpty()) {
            AlertDialog.Builder(this).setMessage("Введи URL сервера").setPositiveButton("OK", null).show()
            return
        }
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            AlertDialog.Builder(this).setMessage("URL должен начинаться с ws:// или wss://").setPositiveButton("OK", null).show()
            return
        }
        save.setLastRelayUrl(url)
        appendLog("Подключаемся к $url…")

        relay = WebSocketRelay(url).apply {
            onOpen = { handler.post {
                appendLog("WebSocket открыт, создаём комнату…")
                val msg = JSONObject().apply {
                    put("t", "CREATE_ROOM")
                    put("nickname", hostNickname)
                }
                relay?.send(msg)
            } }
            onClose = { handler.post {
                appendLog("WebSocket закрыт")
            } }
            onError = { msg -> handler.post { appendLog("Ошибка: $msg") } }
            onMessage = { obj -> handler.post { handleServerMessage(obj) } }
        }
        relay?.connect()
    }

    private fun handleServerMessage(obj: JSONObject) {
        when (obj.optString("t")) {
            "ROOM_CREATED" -> {
                val roomId = obj.optString("roomId")
                findViewById<TextView>(R.id.tvHostRoomId).text = "Код комнаты: $roomId"
                appendLog("Комната создана. Код: $roomId")
                broadcastLobby()
            }
            "LOBBY" -> {
                // Server says players list updated
                val arr = obj.optJSONArray("players")
                val newPlayers = ArrayList<Message.PlayerInfo>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val p = arr.getJSONObject(i)
                        newPlayers.add(Message.PlayerInfo(
                            p.optInt("id"), p.optString("name"), p.optBoolean("isHost")
                        ))
                    }
                }
                // Detect new connections
                for (p in newPlayers) {
                    if (!players.containsKey(p.id) && p.id != 0) {
                        appendLog("${p.name} присоединился")
                    }
                }
                players.clear()
                for (p in newPlayers) players[p.id] = p
                refreshPlayerList()
            }
            "PLAYER_LEFT" -> {
                val pid = obj.optInt("playerId")
                val removed = players.remove(pid)
                if (removed != null) appendLog("${removed.name} отключился")
                refreshPlayerList()
                broadcastLobby()
            }
            // Messages from clients (CLICK/FLAG/CHORD)
            "CLICK", "FLAG", "CHORD" -> {
                val senderId = obj.optInt("senderId", -1)
                if (senderId < 0) return
                val msg = when (obj.optString("t")) {
                    "CLICK" -> Message.Click(obj.optInt("row"), obj.optInt("col"))
                    "FLAG" -> Message.Flag(obj.optInt("row"), obj.optInt("col"))
                    "CHORD" -> Message.Chord(obj.optInt("row"), obj.optInt("col"))
                    else -> return
                }
                // Notify MpGameActivity if it's active (via Holder), else queue?
                // For simplicity: we forward to a static callback if set.
                MpContextHolder.clientMessageHandler?.invoke(senderId, msg)
            }
            "GOODBYE" -> {
                appendLog("Сервер закрыл соединение")
            }
            "ERROR" -> {
                appendLog("Ошибка: ${obj.optString("message")}")
            }
        }
    }

    private fun broadcastLobby() {
        relay?.broadcast(Message.Lobby(players.values.toList()))
    }

    private fun refreshPlayerList() {
        val container = findViewById<LinearLayout>(R.id.playersContainer)
        container.removeAllViews()
        for ((idx, p) in players.values.withIndex()) {
            val tv = TextView(this).apply {
                text = "${idx + 1}. ${p.name}${if (p.isHost) "  (хост)" else ""}"
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setPadding(0, 8, 0, 8)
            }
            container.addView(tv)
        }
        findViewById<Button>(R.id.btnStartMp).isEnabled = players.isNotEmpty()
    }

    private fun pickMode() {
        val labels = GameMode.entries.map { "${it.display} — ${it.shortDesc}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Режим игры")
            .setItems(labels) { _, which ->
                selectedMode = GameMode.entries[which]
                findViewById<Button>(R.id.btnPickMode).text = "Режим: ${selectedMode.display}"
            }
            .show()
    }

    private fun pickDifficulty() {
        val labels = Difficulty.entries.map { "${it.display} — ${it.shortDesc}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Сложность")
            .setItems(labels) { _, which ->
                selectedDiff = Difficulty.entries[which]
                findViewById<Button>(R.id.btnPickDiff).text = "Сложность: ${selectedDiff.display}"
            }
            .show()
    }

    private fun pickShiftInterval() {
        val values = (3..30).toList()
        val labels = values.map { "$it сек" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Интервал сдвига")
            .setItems(labels) { _, which ->
                save.setShiftInterval(values[which])
                findViewById<Button>(R.id.btnPickShift).text = "Сдвиг: ${values[which]} сек"
            }
            .show()
    }

    private fun promptNickname() {
        val input = EditText(this).apply {
            hint = "Ваш ник"
            setText(hostNickname)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Ник хоста")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Хост" }
                val existing = players.values.filter { it.id != hostId }.map { it.name }
                hostNickname = deduplicateNickname(name, existing)
                players[hostId] = Message.PlayerInfo(hostId, hostNickname, true)
                findViewById<Button>(R.id.btnHostNick).text = "Ник: $hostNickname"
                refreshPlayerList()
                broadcastLobby()
                save.setNickname(hostNickname)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startGame() {
        if (players.isEmpty()) return
        val playersList = players.values.toList()
        val seed = System.currentTimeMillis()
        val startMsg = Message.Start(
            mode = selectedMode.key,
            difficulty = selectedDiff.key,
            shiftInterval = save.shiftInterval(),
            players = playersList,
            hostSeed = seed,
        )
        relay?.broadcast(startMsg)

        // Кладём relay в Holder, MpGameActivity подхватит
        MpContextHolder.relay = relay
        MpContextHolder.relayIsHost = true
        relay = null

        val playersJson = JSONArray()
        for (p in playersList) {
            playersJson.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("isHost", p.isHost)
            })
        }

        val intent = Intent(this, MpGameActivity::class.java).apply {
            putExtra(MpGameActivity.EXTRA_IS_HOST, true)
            putExtra(MpGameActivity.EXTRA_MODE, selectedMode.key)
            putExtra(MpGameActivity.EXTRA_DIFFICULTY, selectedDiff.key)
            putExtra(MpGameActivity.EXTRA_SHIFT_INTERVAL, save.shiftInterval())
            putExtra(MpGameActivity.EXTRA_SEED, seed)
            putExtra(MpGameActivity.EXTRA_MY_ID, hostId)
            putExtra(MpGameActivity.EXTRA_NICKNAME, hostNickname)
            putExtra(MpGameActivity.EXTRA_PLAYERS, playersJson.toString())
            putExtra(MpGameActivity.EXTRA_USE_RELAY, true)
        }
        startActivity(intent)
        finish()
    }

    private fun appendLog(text: String) {
        val tv = findViewById<TextView>(R.id.tvLobbyLog)
        val cur = tv.text.toString()
        tv.text = if (cur.isBlank()) text else "$cur\n$text"
    }

    override fun onDestroy() {
        super.onDestroy()
        // Если relay ещё у нас (не передан в Holder) — закрываем
        relay?.disconnect()
        relay = null
    }
}

// Extension helper: broadcast Message through WebSocketRelay
fun WebSocketRelay.broadcast(message: Message) {
    val payload = message.toJson()
    val wrapper = JSONObject().apply {
        put("t", "BROADCAST")
        put("payload", payload)
    }
    send(wrapper)
}
