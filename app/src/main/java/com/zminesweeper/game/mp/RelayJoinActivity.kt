package com.zminesweeper.game.mp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zminesweeper.game.R
import com.zminesweeper.game.SaveManager
import com.zminesweeper.game.net.Message
import com.zminesweeper.game.net.WebSocketRelay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Подключение к комнате на relay-сервере (кроссплат: Android ↔ HTML).
 *
 * Игрок вводит URL сервера + код комнаты + ник.
 * После JOIN_ACK ждёт START от хоста → запускает MpGameActivity.
 */
class RelayJoinActivity : AppCompatActivity() {

    private lateinit var save: SaveManager
    private var relay: WebSocketRelay? = null
    private val handler = Handler(Looper.getMainLooper())

    private var myPlayerId: Int = -1
    private var myNickname: String = "Player"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_relay_join)
        save = SaveManager(this)

        findViewById<EditText>(R.id.etRelayClientUrl).apply {
            setText(save.lastRelayUrl() ?: "")
            hint = "wss://your-relay.onrender.com"
        }
        findViewById<EditText>(R.id.etRelayRoomId).apply {
            setText("")
            hint = "ABC123"
        }
        findViewById<EditText>(R.id.etRelayNick).apply {
            setText(save.nickname() ?: "")
            hint = getString(R.string.mp_nickname_hint)
        }

        findViewById<Button>(R.id.btnRelayConnect).setOnClickListener { tryConnect() }
        findViewById<Button>(R.id.btnRelayDisconnect).apply {
            setOnClickListener { disconnect() }
            isEnabled = false
        }
        findViewById<Button>(R.id.btnBackRelayJoin).setOnClickListener {
            disconnect()
            finish()
        }
    }

    private fun tryConnect() {
        val url = findViewById<EditText>(R.id.etRelayClientUrl).text.toString().trim()
        val roomId = findViewById<EditText>(R.id.etRelayRoomId).text.toString().trim().uppercase()
        val nick = findViewById<EditText>(R.id.etRelayNick).text.toString().trim().ifBlank { getString(R.string.default_player_name) }

        if (url.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.enter_server_url).setPositiveButton(R.string.ok, null).show()
            return
        }
        if (roomId.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.enter_room_code).setPositiveButton(R.string.ok, null).show()
            return
        }
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            AlertDialog.Builder(this).setMessage(R.string.invalid_url).setPositiveButton(R.string.ok, null).show()
            return
        }

        save.setLastRelayUrl(url)
        save.setNickname(nick)
        myNickname = nick

        findViewById<Button>(R.id.btnRelayConnect).isEnabled = false
        findViewById<Button>(R.id.btnRelayDisconnect).isEnabled = true
        appendLog(getString(R.string.connecting_to_room, url, roomId))

        relay = WebSocketRelay(url).apply {
            onOpen = { handler.post {
                appendLog(getString(R.string.ws_open_join))
                // Send JOIN_ROOM
                val msg = JSONObject().apply {
                    put("t", "JOIN_ROOM")
                    put("roomId", roomId)
                    put("nickname", nick)
                }
                relay?.send(msg)
            } }
            onClose = { handler.post {
                appendLog(getString(R.string.ws_closed))
                resetButtons()
            } }
            onError = { msg -> handler.post {
                appendLog(getString(R.string.error_with_message, msg))
                resetButtons()
            } }
            onMessage = { obj -> handler.post { handleServerMessage(obj) } }
        }
        relay?.connect()
    }

    private fun handleServerMessage(obj: JSONObject) {
        val t = obj.optString("t")
        when (t) {
            "JOIN_ACK" -> {
                myPlayerId = obj.optInt("playerId")
                myNickname = obj.optString("nickname")
                appendLog(getString(R.string.connected_as, myNickname, myPlayerId))
            }
            "LOBBY" -> {
                val arr = obj.optJSONArray("players")
                val sb = StringBuilder()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val p = arr.getJSONObject(i)
                        val name = p.optString("name")
                        val isHost = p.optBoolean("isHost")
                        val tag = if (isHost) "  " + getString(R.string.host_tag) else ""
                        sb.append("${i + 1}. $name$tag\n")
                    }
                }
                findViewById<TextView>(R.id.tvRelayClientPlayers).text = sb.toString().trim()
            }
            "START" -> {
                appendLog(getString(R.string.host_started_game))
                launchGame(obj)
            }
            "STATE" -> {
                // Передаём в MpGameActivity через Holder — но игра ещё не запущена.
                // Сохраним последнее состояние и запустим активити, если ещё не запущена.
                launchGame(obj)
            }
            "OVER" -> {
                // Игра ещё не запущена? Игнорируем. Если запущена — Holder получит.
            }
            "GOODBYE" -> {
                appendLog(getString(R.string.host_closed_lobby))
                disconnect()
            }
            "ERROR" -> {
                appendLog(getString(R.string.error_with_message, obj.optString("message")))
            }
        }
    }

    private fun launchGame(startMsg: JSONObject) {
        // Парсим START
        val modeKey = startMsg.optString("mode")
        val diffKey = startMsg.optString("difficulty")
        val shiftInterval = startMsg.optInt("shiftInterval")
        val seed = startMsg.optLong("seed")
        val playersArr = startMsg.optJSONArray("players")
        val players = ArrayList<Message.PlayerInfo>()
        if (playersArr != null) {
            for (i in 0 until playersArr.length()) {
                val p = playersArr.getJSONObject(i)
                players.add(Message.PlayerInfo(
                    p.optInt("id"), p.optString("name"), p.optBoolean("isHost")
                ))
            }
        }

        val playersJson = JSONArray()
        for (p in players) {
            playersJson.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("isHost", p.isHost)
            })
        }

        // Кладём relay в holder — MpGameActivity будет использовать его
        MpContextHolder.relay = relay
        MpContextHolder.relayIsHost = false
        relay = null

        val intent = Intent(this, MpGameActivity::class.java).apply {
            putExtra(MpGameActivity.EXTRA_IS_HOST, false)
            putExtra(MpGameActivity.EXTRA_MODE, modeKey)
            putExtra(MpGameActivity.EXTRA_DIFFICULTY, diffKey)
            putExtra(MpGameActivity.EXTRA_SHIFT_INTERVAL, shiftInterval)
            putExtra(MpGameActivity.EXTRA_SEED, seed)
            putExtra(MpGameActivity.EXTRA_MY_ID, myPlayerId)
            putExtra(MpGameActivity.EXTRA_NICKNAME, myNickname)
            putExtra(MpGameActivity.EXTRA_PLAYERS, playersJson.toString())
            putExtra(MpGameActivity.EXTRA_USE_RELAY, true)
            putExtra(MpGameActivity.EXTRA_MP_MODE, startMsg.optString("mpMode", "coop"))
            if (startMsg.optInt("customRows", 0) > 0) {
                putExtra(MpGameActivity.EXTRA_CUSTOM_ROWS, startMsg.optInt("customRows", 0))
                putExtra(MpGameActivity.EXTRA_CUSTOM_COLS, startMsg.optInt("customCols", 0))
            }
        }
        startActivity(intent)
        finish()
    }

    private fun disconnect() {
        try {
            val leave = JSONObject().apply { put("t", "LEAVE") }
            relay?.send(leave)
        } catch (e: Exception) {}
        relay?.disconnect()
        relay = null
        resetButtons()
        findViewById<TextView>(R.id.tvRelayClientPlayers).text = ""
    }

    private fun resetButtons() {
        findViewById<Button>(R.id.btnRelayConnect).isEnabled = true
        findViewById<Button>(R.id.btnRelayDisconnect).isEnabled = false
    }

    private fun appendLog(text: String) {
        val tv = findViewById<TextView>(R.id.tvRelayClientLog)
        val cur = tv.text.toString()
        tv.text = if (cur.isBlank()) text else "$cur\n$text"
    }

    override fun onDestroy() {
        super.onDestroy()
        // Если ещё не передали в Holder — отключаем
        relay?.disconnect()
        relay = null
    }
}
