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
import com.zminesweeper.game.net.MultiplayerClient
import com.zminesweeper.game.net.MultiplayerServer
import com.zminesweeper.game.net.deduplicateNickname
import org.json.JSONArray
import org.json.JSONObject

/**
 * Подключение к лобби хоста.
 *
 *  Игрок вводит:
 *   - IP-адрес хоста (например 192.168.1.5 или ZeroTier-адрес 10.147.17.5)
 *   - Порт (по умолчанию 7777)
 *   - Ник (по умолчанию из настроек)
 *
 *  После подключения видит список игроков в лобби и ждёт старта хоста.
 */
class JoinClientActivity : AppCompatActivity() {

    private lateinit var save: SaveManager
    private var client: MultiplayerClient? = null
    private val handler = Handler(Looper.getMainLooper())

    private var myPlayerId: Int = -1
    private var myNickname: String = "Игрок"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_join_client)
        save = SaveManager(this)

        findViewById<EditText>(R.id.etHostIp).apply {
            setText(save.lastHostIp() ?: "")
            hint = "10.147.17.5 или 192.168.1.5"
        }
        findViewById<EditText>(R.id.etHostPort).apply {
            setText(MultiplayerServer.DEFAULT_PORT.toString())
        }
        findViewById<EditText>(R.id.etNick).apply {
            setText(save.nickname() ?: "")
            hint = "Твой ник"
        }

        findViewById<Button>(R.id.btnConnect).setOnClickListener { tryConnect() }
        findViewById<Button>(R.id.btnDisconnect).apply {
            setOnClickListener { disconnect() }
            isEnabled = false
        }
    }

    private fun tryConnect() {
        val ip = findViewById<EditText>(R.id.etHostIp).text.toString().trim()
        val portStr = findViewById<EditText>(R.id.etHostPort).text.toString().trim()
        val requestedNick = findViewById<EditText>(R.id.etNick).text.toString().trim().ifBlank { "Игрок" }

        if (ip.isEmpty()) {
            AlertDialog.Builder(this).setMessage("Введи IP хоста").setPositiveButton("OK", null).show()
            return
        }
        val port = portStr.toIntOrNull() ?: MultiplayerServer.DEFAULT_PORT

        save.setLastHostIp(ip)
        save.setNickname(requestedNick)

        findViewById<Button>(R.id.btnConnect).isEnabled = false
        findViewById<Button>(R.id.btnDisconnect).isEnabled = true
        appendLog("Подключаемся к $ip:$port…")

        client = MultiplayerClient().apply {
            onMessage = { msg -> handler.post { handleServerMessage(msg) } }
            onDisconnect = {
                handler.post {
                    appendLog("Отключено от хоста")
                    findViewById<Button>(R.id.btnConnect).isEnabled = true
                    findViewById<Button>(R.id.btnDisconnect).isEnabled = false
                }
            }
            onError = { msg ->
                handler.post {
                    appendLog("Ошибка: $msg")
                    findViewById<Button>(R.id.btnConnect).isEnabled = true
                    findViewById<Button>(R.id.btnDisconnect).isEnabled = false
                }
            }
        }.also { it.connect(ip, port) }

        // Шлём JOIN с ником — хост ответит JOIN_ACK с финальным ником (возможно, с суффиксом)
        client?.send(Message.Join(requestedNick))
    }

    private fun handleServerMessage(msg: Message) {
        when (msg) {
            is Message.JoinAck -> {
                myPlayerId = msg.playerId
                myNickname = msg.nickname
                appendLog("Подключён как ${msg.nickname} (#${msg.playerId})")
            }
            is Message.Lobby -> {
                val sb = StringBuilder()
                for ((idx, p) in msg.players.withIndex()) {
                    sb.append("${idx + 1}. ${p.name}${if (p.isHost) "  (хост)" else ""}\n")
                }
                findViewById<TextView>(R.id.tvLobbyPlayers).text = sb.toString().trim()
            }
            is Message.Start -> {
                appendLog("Хост стартовал игру!")
                launchGame(msg)
            }
            is Message.Goodbye -> {
                appendLog("Хост закрыл лобби")
                disconnect()
            }
            is Message.Error -> appendLog("Ошибка: ${msg.message}")
            else -> {}
        }
    }

    private fun launchGame(start: Message.Start) {
        val playersJson = JSONArray()
        for (p in start.players) {
            playersJson.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("isHost", p.isHost)
            })
        }
        val intent = Intent(this, MpGameActivity::class.java).apply {
            putExtra(MpGameActivity.EXTRA_IS_HOST, false)
            putExtra(MpGameActivity.EXTRA_MODE, start.mode)
            putExtra(MpGameActivity.EXTRA_DIFFICULTY, start.difficulty)
            putExtra(MpGameActivity.EXTRA_SHIFT_INTERVAL, start.shiftInterval)
            putExtra(MpGameActivity.EXTRA_SEED, start.hostSeed)
            putExtra(MpGameActivity.EXTRA_MY_ID, myPlayerId)
            putExtra(MpGameActivity.EXTRA_NICKNAME, myNickname)
            putExtra(MpGameActivity.EXTRA_PLAYERS, playersJson.toString())
            putExtra(MpGameActivity.EXTRA_MP_MODE, start.mpMode)
            if (start.customRows > 0) {
                putExtra(MpGameActivity.EXTRA_CUSTOM_ROWS, start.customRows)
                putExtra(MpGameActivity.EXTRA_CUSTOM_COLS, start.customCols)
            }
        }
        // Клиента НЕ отключаем — он нужен в игре. Перекладываем в контекст.
        MpContextHolder.client = client
        client = null
        startActivity(intent)
        finish()  // лобби больше не нужно
    }

    private fun disconnect() {
        client?.disconnect()
        client = null
        findViewById<Button>(R.id.btnConnect).isEnabled = true
        findViewById<Button>(R.id.btnDisconnect).isEnabled = false
        findViewById<TextView>(R.id.tvLobbyPlayers).text = ""
    }

    private fun appendLog(text: String) {
        val tv = findViewById<TextView>(R.id.tvJoinLog)
        val cur = tv.text.toString()
        tv.text = if (cur.isBlank()) text else "$cur\n$text"
    }

    override fun onDestroy() {
        super.onDestroy()
        // Если клиент ещё у нас (т.е. не передан в MpGameActivity) — отключаем.
        client?.disconnect()
        client = null
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        AlertDialog.Builder(this)
            .setTitle("Выйти из лобби?")
            .setPositiveButton("Выйти") { _, _ ->
                disconnect()
                finish()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
