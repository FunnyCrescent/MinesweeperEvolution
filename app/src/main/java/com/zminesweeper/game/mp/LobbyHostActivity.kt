package com.zminesweeper.game.mp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zminesweeper.game.Difficulty
import com.zminesweeper.game.GameMode
import com.zminesweeper.game.MinesweeperApp
import com.zminesweeper.game.MpMode
import com.zminesweeper.game.R
import com.zminesweeper.game.SaveManager
import com.zminesweeper.game.net.Message
import com.zminesweeper.game.net.MultiplayerServer
import com.zminesweeper.game.net.NetUtil
import com.zminesweeper.game.net.deduplicateNickname
import org.json.JSONArray
import org.json.JSONObject

/**
 * Лобби хоста.
 *
 *  - Показывает локальные IP-адреса (для подключения по IP / через ZeroTier).
 *  - Хост выбирает режим/сложность/интервал сдвига.
 *  - Хост видит подключающихся клиентов и может стартовать игру (1-5 игроков).
 *  - Ник хоста берётся из настроек; при совпадении с ником клиента — клиенту добавляется суффикс.
 */
class LobbyHostActivity : AppCompatActivity() {

    private lateinit var save: SaveManager
    private var server: MultiplayerServer? = null
    private val handler = Handler(Looper.getMainLooper())

    private val players = LinkedHashMap<Int, Message.PlayerInfo>()
    private val hostId: Int = 0
    private var hostNickname: String = "Host"
    private var selectedMode: GameMode = GameMode.CLASSIC
    private var selectedDiff: Difficulty = Difficulty.BEGINNER
    private var selectedMpMode: MpMode = MpMode.COOP

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lobby_host)
        save = SaveManager(this)

        hostNickname = save.nickname() ?: getString(R.string.host_default_name)
        hostNickname = deduplicateNickname(hostNickname, emptyList())
        players[hostId] = Message.PlayerInfo(hostId, hostNickname, true)

        val port = MultiplayerServer.DEFAULT_PORT
        server = MultiplayerServer(port = port).apply {
            onClientMessage = { clientId, msg -> handleClientMessage(clientId, msg) }
            onClientConnected = { clientId, addr ->
                handler.post { appendLog(getString(R.string.player_connected_waiting, addr.toString(), clientId)) }
            }
            onClientDisconnected = { clientId ->
                handler.post {
                    val removed = players.remove(clientId)
                    if (removed != null) appendLog(getString(R.string.player_disconnected, removed.name))
                    refreshPlayerList()
                    broadcastLobby()
                }
            }
            onError = { msg -> handler.post { appendLog(getString(R.string.error_with_message, msg)) } }
            start()
        }

        val ips = NetUtil.localIpAddresses()
        findViewById<TextView>(R.id.tvHostIp).text =
            getString(R.string.ip_addresses_label) + "\n" + (if (ips.isEmpty()) getString(R.string.ip_undefined)
                                    else ips.joinToString("\n"))
        findViewById<TextView>(R.id.tvHostPort).text = getString(R.string.port_format, port)

        findViewById<Button>(R.id.btnHostNick).apply {
            text = getString(R.string.nickname_format, hostNickname)
            setOnClickListener { promptNickname() }
        }

        findViewById<Button>(R.id.btnPickMode).apply {
            text = getString(R.string.mode_format, selectedMode.display)
            setOnClickListener { pickMode() }
        }
        findViewById<Button>(R.id.btnPickDiff).apply {
            text = getString(R.string.difficulty_format, selectedDiff.display)
            setOnClickListener { pickDifficulty() }
        }
        findViewById<Button>(R.id.btnPickShift).apply {
            text = getString(R.string.shift_format, save.shiftInterval())
            setOnClickListener { pickShiftInterval() }
        }
        // Кнопка выбора MP-режима: Вместе / Гонка
        findViewById<Button>(R.id.btnPickMpMode)?.apply {
            text = getString(R.string.mp_mode_format, selectedMpMode.display)
            setOnClickListener {
                val labels = MpMode.entries.map { "${it.display} — ${it.shortDesc}" }.toTypedArray<String>()
                AlertDialog.Builder(this@LobbyHostActivity)
                    .setTitle(R.string.mp_mode_title)
                    .setItems(labels) { _, which ->
                        selectedMpMode = MpMode.entries[which]
                        findViewById<Button>(R.id.btnPickMpMode).text = getString(R.string.mp_mode_format, selectedMpMode.display)
                    }
                    .show()
            }
        }

        findViewById<Button>(R.id.btnStartMp).setOnClickListener { startGame() }

        refreshPlayerList()
        appendLog(getString(R.string.lobby_open_log))
    }

    private fun handleClientMessage(clientId: Int, msg: Message) {
        handler.post {
            when (msg) {
                is Message.Join -> handleJoin(clientId, msg.nickname)
                is Message.Leave, Message.Goodbye -> {
                    val removed = players.remove(clientId)
                    if (removed != null) appendLog(getString(R.string.player_left, removed.name))
                    refreshPlayerList()
                    broadcastLobby()
                }
                else -> {
                    // Click/Flag — в лобби игнорируем
                }
            }
        }
    }

    private fun handleJoin(clientId: Int, requestedNick: String) {
        val existing = players.values.map { it.name }
        val finalNick = deduplicateNickname(requestedNick, existing)
        players[clientId] = Message.PlayerInfo(clientId, finalNick, false)
        // Подтверждаем клиенту его финальный ник + id
        server?.sendTo(clientId, Message.JoinAck(finalNick, clientId))
        broadcastLobby()
        refreshPlayerList()
        appendLog(getString(R.string.player_joined, finalNick))
    }

    private fun broadcastLobby() {
        server?.broadcast(Message.Lobby(players.values.toList()))
    }

    private fun refreshPlayerList() {
        val container = findViewById<LinearLayout>(R.id.playersContainer)
        container.removeAllViews()
        for ((idx, p) in players.values.withIndex()) {
            val tv = TextView(this).apply {
                val tag = if (p.isHost) getString(R.string.host_tag) else ""
                text = getString(R.string.player_idx_format, idx + 1, p.name, tag)
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
            .setTitle(R.string.mode_picker_title)
            .setItems(labels) { _, which ->
                selectedMode = GameMode.entries[which]
                findViewById<Button>(R.id.btnPickMode).text = getString(R.string.mode_format, selectedMode.display)
            }
            .show()
    }

    private fun pickDifficulty() {
        val labels = Difficulty.entries.map { "${it.display} — ${it.shortDesc}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.difficulty_picker_title)
            .setItems(labels) { _, which ->
                selectedDiff = Difficulty.entries[which]
                findViewById<Button>(R.id.btnPickDiff).text = getString(R.string.difficulty_format, selectedDiff.display)
            }
            .show()
    }

    private fun pickShiftInterval() {
        val values = (3..30).toList()
        val labels = values.map { getString(R.string.shift_value, it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.shift_picker_title)
            .setItems(labels) { _, which ->
                save.setShiftInterval(values[which])
                findViewById<Button>(R.id.btnPickShift).text = getString(R.string.shift_format, values[which])
            }
            .show()
    }

    private fun promptNickname() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.your_nickname_hint)
            setText(hostNickname)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.host_nickname_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifBlank { getString(R.string.host_default_name) }
                val existing = players.values.filter { it.id != hostId }.map { it.name }
                hostNickname = deduplicateNickname(name, existing)
                players[hostId] = Message.PlayerInfo(hostId, hostNickname, true)
                findViewById<Button>(R.id.btnHostNick).text = getString(R.string.nickname_format, hostNickname)
                refreshPlayerList()
                broadcastLobby()
                save.setNickname(hostNickname)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startGame() {
        if (players.isEmpty()) return
        val playersList = players.values.toList()
        val seed = System.currentTimeMillis()
        val customR = if (selectedDiff == Difficulty.CUSTOM) MinesweeperApp.customRows else 0
        val customC = if (selectedDiff == Difficulty.CUSTOM) MinesweeperApp.customCols else 0
        val startMsg = Message.Start(
            mode = selectedMode.key,
            difficulty = selectedDiff.key,
            shiftInterval = save.shiftInterval(),
            players = playersList,
            hostSeed = seed,
            mpMode = selectedMpMode.key,
            customRows = customR,
            customCols = customC,
        )
        server?.broadcast(startMsg)

        // Сервер НЕ закрываем — перекладываем в контекст, MpGameActivity подхватит.
        MpContextHolder.server = server
        server = null

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
            putExtra(MpGameActivity.EXTRA_MP_MODE, selectedMpMode.key)
            if (selectedDiff == Difficulty.CUSTOM) {
                putExtra(MpGameActivity.EXTRA_CUSTOM_ROWS, MinesweeperApp.customRows)
                putExtra(MpGameActivity.EXTRA_CUSTOM_COLS, MinesweeperApp.customCols)
            }
        }
        startActivity(intent)
        finish()  // лобби больше не нужно
    }

    private fun appendLog(text: String) {
        val tv = findViewById<TextView>(R.id.tvLobbyLog)
        val cur = tv.text.toString()
        tv.text = if (cur.isBlank()) text else "$cur\n$text"
    }

    override fun onDestroy() {
        super.onDestroy()
        // Если сервер ещё у нас (т.е. не передан в MpGameActivity) — закрываем.
        server?.stop()
        server = null
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        AlertDialog.Builder(this)
            .setTitle(R.string.close_lobby_question)
            .setMessage(R.string.close_lobby_message)
            .setPositiveButton(R.string.close) { _, _ ->
                server?.broadcast(Message.Goodbye)
                server?.stop()
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
