package com.zminesweeper.game.mp

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zminesweeper.game.Difficulty
import com.zminesweeper.game.GameEngine
import com.zminesweeper.game.GameMode
import com.zminesweeper.game.GameView
import com.zminesweeper.game.R
import com.zminesweeper.game.SaveManager
import com.zminesweeper.game.SoundManager
import com.zminesweeper.game.net.Message
import com.zminesweeper.game.net.MultiplayerClient
import com.zminesweeper.game.net.MultiplayerServer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Активность мультиплеерной партии.
 *
 * Архитектура — host-authoritative:
 *  - Хост держит GameEngine. Локальные клики хост обрабатывает сам (через GameView).
 *  - Клиенты держат «зеркало» — отображают состояние, присланное хостом.
 *  - Локальный клик клиента уходит на хост через Message.Click / Flag.
 *  - Хост применяет клик к движку и бродкастит STATE всем клиентам.
 *  - Сдвиги мин: хост крутит shiftRunnable локально и бродкастит STATE после сдвига.
 *
 *  Порядок ходов: по списку игроков (как пришёл — host, p1, p2, …).
 *  Сверху показывается «▼ ВАШ ХОД ▼» / «Ход: NICK».
 *
 *  Сетевой объект (server для хоста, client для клиента) передаётся из лобби через
 *  MpContextHolder. Колбэки переопределяются на игровые при onCreate.
 */
class MpGameActivity : AppCompatActivity() {

    private lateinit var save: SaveManager
    private lateinit var gameView: GameView
    private var sound: SoundManager? = null

    /** Локальный движок: у хоста — авторитарный, у клиента — зеркало. */
    private var engine: GameEngine? = null

    private var server: MultiplayerServer? = null
    private var client: MultiplayerClient? = null

    private val handler = Handler(Looper.getMainLooper())

    // Игроки
    private var players: List<Message.PlayerInfo> = emptyList()
    private var myId: Int = 0
    private var myNickname: String = "Игрок"
    private var currentTurnId: Int = 0   // чей сейчас ход

    // Игровые параметры
    private var startTimeMs: Long = 0L
    private var elapsedSec: Int = 0
    private var shiftRemainingSec: Int = 10
    private var shiftInterval: Int = 10
    private var mode: GameMode = GameMode.CLASSIC
    private var difficulty: Difficulty = Difficulty.BEGINNER

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (isHost) {
                elapsedSec = ((System.currentTimeMillis() - startTimeMs) / 1000).toInt()
                findViewById<TextView>(R.id.tvTime).text = formatTime(elapsedSec)
            }
            // Клиент отображает время из STATE — оно придёт от хоста.
            handler.postDelayed(this, 500)
        }
    }

    private val shiftRunnable = object : Runnable {
        override fun run() {
            if (isHost) {
                val e = engine ?: return
                if (!e.gameOver && e.mode.shifts && e.firstClickDone) {
                    shiftRemainingSec--
                    if (shiftRemainingSec <= 0) {
                        e.shiftMines()
                        gameView.animateShift()
                        sound?.play(SoundManager.Type.SHIFT)
                        shiftRemainingSec = shiftInterval
                        updateMinesLabel()
                        broadcastState()
                    }
                    findViewById<TextView>(R.id.tvShift).text = "${shiftRemainingSec}с"
                } else if (!e.firstClickDone) {
                    findViewById<TextView>(R.id.tvShift).text = "—"
                }
                handler.postDelayed(this, 1000)
            } else {
                // У клиента — таймер сдвига синхронизируется хостом через STATE
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val isHost: Boolean
        get() = server != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mp_game)
        save = SaveManager(this)
        gameView = findViewById(R.id.mpGameView)
        sound = SoundManager(this).also { it.enabled = save.isSound() }

        // Парсим extras
        val isHost = intent.getBooleanExtra(EXTRA_IS_HOST, false)
        mode = GameMode.fromKey(intent.getStringExtra(EXTRA_MODE))
        difficulty = Difficulty.fromKey(intent.getStringExtra(EXTRA_DIFFICULTY))
        shiftInterval = intent.getIntExtra(EXTRA_SHIFT_INTERVAL, 10)
        myId = intent.getIntExtra(EXTRA_MY_ID, 0)
        myNickname = intent.getStringExtra(EXTRA_NICKNAME) ?: "Игрок"

        // Парсим список игроков из JSON
        val playersJson = intent.getStringExtra(EXTRA_PLAYERS) ?: "[]"
        players = parsePlayers(playersJson)

        // Кто ходит первым — host (id=0).
        currentTurnId = 0

        // Подхватываем сетевой объект из контекста
        if (isHost) {
            engine = GameEngine().also { it.initGame(mode, difficulty) }
            server = MpContextHolder.server
            MpContextHolder.server = null
            server?.onClientMessage = { clientId, msg -> handler.post { handleClientMessage(clientId, msg) } }
            server?.onClientDisconnected = { clientId -> handler.post { handleClientDisconnect(clientId) } }
            server?.onError = { msg -> handler.post { appendDebug("Ошибка: $msg") } }
        } else {
            // У клиента engine будет создан при первом STATE от хоста
            client = MpContextHolder.client
            MpContextHolder.client = null
            client?.onMessage = { msg -> handler.post { handleServerMessage(msg) } }
            client?.onDisconnect = { handler.post {
                AlertDialog.Builder(this)
                    .setMessage("Соединение с хостом потеряно")
                    .setCancelable(false)
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .show()
            } }
            client?.onError = { msg -> handler.post { appendDebug("Ошибка: $msg") } }
        }

        // Подключаем engine к view (у клиента — null, появится после первого STATE)
        gameView.engine = engine
        // Все локальные тапы идут через externalClickListener —我们自己 маршрутизируем.
        gameView.externalClickListener = { row, col, isFlag -> onLocalAction(row, col, isFlag) }
        gameView.onRevealListener = { row, col, exploded, won ->
            gameView.animateRevealWave(engine?.lastRevealed ?: emptyList(), row, col)
            if (exploded) sound?.play(SoundManager.Type.EXPLODE)
            else if (won) sound?.play(SoundManager.Type.WIN)
            else sound?.play(SoundManager.Type.REVEAL)
            updateMinesLabel()
            updateTurnLabel()
        }
        gameView.onFlagListener = { _, _ ->
            sound?.play(SoundManager.Type.FLAG)
            updateMinesLabel()
        }

        findViewById<Button>(R.id.btnFlagMode).setOnClickListener {
            gameView.flagMode = !gameView.flagMode
            it.isSelected = gameView.flagMode
        }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { confirmExit() }

        updateModeLabel()
        updateMinesLabel()
        updateTurnLabel()
        findViewById<TextView>(R.id.tvShift).text = if (mode.shifts) "${shiftInterval}с" else "—"
        findViewById<View>(R.id.llShift).visibility = if (mode.shifts) View.VISIBLE else View.INVISIBLE

        startTimeMs = System.currentTimeMillis()
        findViewById<TextView>(R.id.tvTime).text = "0:00"

        // Если хост один (никто не подключился) — играем соло в MP-режиме
        if (isHost) broadcastState()
    }

    private fun parsePlayers(jsonStr: String): List<Message.PlayerInfo> {
        val arr = JSONArray(jsonStr)
        val list = ArrayList<Message.PlayerInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(Message.PlayerInfo(o.optInt("id"), o.optString("name"), o.optBoolean("isHost")))
        }
        return list
    }

    private fun onLocalAction(row: Int, col: Int, isFlag: Boolean) {
        if (currentTurnId != myId) {
            // Не твой ход — тихо игнорируем
            return
        }
        if (isHost) {
            val e = engine ?: return
            if (isFlag) {
                if (e.toggleFlag(row, col)) {
                    gameView.animateFlag(row, col)
                    sound?.play(SoundManager.Type.FLAG)
                    updateMinesLabel()
                }
            } else {
                val res = e.reveal(row, col)
                gameView.animateRevealWave(e.lastRevealed, row, col)
                handleHostRevealResult(row, col, res)
                updateMinesLabel()
            }
            gameView.invalidate()
            // После успешного не-explode/won хода — передаём ход дальше.
            advanceTurnIfNeeded()
            broadcastState()
        } else {
            // Клиент — отправляем хосту
            val msg = if (isFlag) Message.Flag(row, col) else Message.Click(row, col)
            client?.send(msg)
        }
    }

    private fun handleHostRevealResult(row: Int, col: Int, res: GameEngine.RevealResult) {
        when (res) {
            GameEngine.RevealResult.EXPLODED -> {
                sound?.play(SoundManager.Type.EXPLODE)
                endGame(reason = "exploded", loserId = myId, winnerId = -1)
            }
            GameEngine.RevealResult.WON -> {
                sound?.play(SoundManager.Type.WIN)
                endGame(reason = "won", loserId = -1, winnerId = myId)
            }
            GameEngine.RevealResult.REVEALED -> {
                sound?.play(SoundManager.Type.REVEAL)
            }
            GameEngine.RevealResult.NO_CHANGE -> {}
        }
    }

    /** Передача хода следующему игроку (только у хоста). */
    private fun advanceTurnIfNeeded() {
        val e = engine ?: return
        if (e.gameOver) return
        if (players.size <= 1) return   // соло — ход не передаём
        val idx = players.indexOfFirst { it.id == currentTurnId }
        if (idx < 0) return
        val next = players[(idx + 1) % players.size]
        currentTurnId = next.id
        updateTurnLabel()
    }

    private fun broadcastState() {
        if (!isHost) return
        val e = engine ?: return
        val msg = Message.State(
            engine = e.serialize(),
            turn = currentTurnId,
            elapsed = elapsedSec,
        )
        server?.broadcast(msg)
    }

    /** Обработка входящих сообщений от хоста (на стороне клиента). */
    private fun handleServerMessage(msg: Message) {
        when (msg) {
            is Message.State -> {
                val e = GameEngine.deserialize(msg.engine)
                if (e != null) {
                    engine = e
                    gameView.engine = e
                    currentTurnId = msg.turn
                    elapsedSec = msg.elapsed
                    findViewById<TextView>(R.id.tvTime).text = formatTime(elapsedSec)
                    updateMinesLabel()
                    updateTurnLabel()
                }
            }
            is Message.Over -> {
                showGameOver(msg.reason, msg.loserId, msg.winnerId)
            }
            is Message.Goodbye -> {
                AlertDialog.Builder(this)
                    .setMessage("Хост закрыл игру")
                    .setCancelable(false)
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .show()
            }
            else -> {}
        }
    }

    /** Обработка входящих сообщений от клиентов (на стороне хоста). */
    private fun handleClientMessage(clientId: Int, msg: Message) {
        when (msg) {
            is Message.Click -> {
                if (clientId != currentTurnId) return  // не твой ход
                val e = engine ?: return
                val res = e.reveal(msg.row, msg.col)
                gameView.animateRevealWave(e.lastRevealed, msg.row, msg.col)
                handleHostRevealResult(msg.row, msg.col, res)
                gameView.invalidate()
                updateMinesLabel()
                advanceTurnIfNeeded()
                broadcastState()
            }
            is Message.Flag -> {
                if (clientId != currentTurnId) return
                val e = engine ?: return
                if (e.toggleFlag(msg.row, msg.col)) {
                    gameView.animateFlag(msg.row, msg.col)
                    sound?.play(SoundManager.Type.FLAG)
                }
                gameView.invalidate()
                updateMinesLabel()
                broadcastState()
            }
            is Message.Leave, Message.Goodbye -> {
                handleClientDisconnect(clientId)
            }
            else -> {}
        }
    }

    private fun handleClientDisconnect(clientId: Int) {
        val removed = players.firstOrNull { it.id == clientId }
        players = players.filter { it.id != clientId }
        if (currentTurnId == clientId && players.isNotEmpty()) {
            currentTurnId = players.first().id
        }
        if (players.size <= 1 && removed != null) {
            // Все ушли — заканчиваем игру
            endGame(reason = "abandoned", loserId = -1, winnerId = myId)
        } else {
            updateTurnLabel()
            broadcastState()
        }
    }

    private fun endGame(reason: String, loserId: Int, winnerId: Int) {
        handler.removeCallbacks(shiftRunnable)
        handler.removeCallbacks(tickRunnable)
        server?.broadcast(Message.Over(reason, loserId, winnerId))
        showGameOver(reason, loserId, winnerId)
    }

    private fun showGameOver(reason: String, loserId: Int, winnerId: Int) {
        handler.removeCallbacks(shiftRunnable)
        handler.removeCallbacks(tickRunnable)

        val youWon = winnerId == myId
        val youLost = loserId == myId
        val title = when {
            youWon -> "🏆 Победа!"
            youLost -> "💥 Ты проиграл"
            winnerId != -1 -> "🏆 Победил: ${players.firstOrNull { it.id == winnerId }?.name ?: "?"}"
            loserId != -1 -> "💥 Проиграл: ${players.firstOrNull { it.id == loserId }?.name ?: "?"}"
            else -> "Игра окончена"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Время: ${formatTime(elapsedSec)}\nПричина: $reason")
            .setCancelable(false)
            .setPositiveButton("В меню") { _, _ -> finish() }
            .show()
    }

    private fun updateModeLabel() {
        findViewById<TextView>(R.id.tvModeLabel).text = "${mode.display} · ${difficulty.display} · MP"
    }

    private fun updateMinesLabel() {
        val e = engine ?: return
        val mines = if (e.mode.hasMineLimit) e.minesLeft().toString() else "~${e.mineCount}"
        findViewById<TextView>(R.id.tvMines).text = mines
    }

    private fun updateTurnLabel() {
        val tv = findViewById<TextView>(R.id.tvTurnIndicator)
        // Только хост знает текущий ход из currentTurnId; у клиента — currentTurnId
        // обновляется из STATE.
        if (currentTurnId == myId) {
            tv.text = "▼ ВАШ ХОД ▼"
            tv.setTextColor(getColor(R.color.accent))
            gameView.inputEnabled = true
        } else {
            val name = players.firstOrNull { it.id == currentTurnId }?.name ?: "?"
            tv.text = "Ход: $name"
            tv.setTextColor(getColor(R.color.text_primary))
            gameView.inputEnabled = false
        }
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("Выйти из игры?")
            .setMessage("Игра будет прервана для всех игроков.")
            .setPositiveButton("Выйти") { _, _ ->
                if (isHost) {
                    server?.broadcast(Message.Goodbye)
                    server?.stop()
                } else {
                    client?.send(Message.Leave)
                    client?.disconnect()
                }
                finish()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun appendDebug(text: String) {
        // Можно подключить TextView для отладки, пока — no-op
    }

    /** Публичный доступ к вибрации — для GameView.performHaptic. */
    fun haptic(heavy: Boolean) {
        if (!save.isVibration()) return
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = getSystemService(android.os.VibratorManager::class.java)
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(android.os.Vibrator::class.java)
            } ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val effect = if (heavy)
                    android.os.VibrationEffect.createOneShot(120, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                else
                    android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(if (heavy) 120L else 30L)
            }
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(shiftRunnable)
        handler.post(tickRunnable)
        if (mode.shifts) {
            shiftRemainingSec = shiftInterval
            handler.post(shiftRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(shiftRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(shiftRunnable)
        sound?.release()
        sound = null
        server?.stop()
        client?.disconnect()
    }

    private fun formatTime(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return "%d:%02d".format(m, s)
    }

    companion object {
        const val EXTRA_IS_HOST = "extra_is_host"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_DIFFICULTY = "extra_difficulty"
        const val EXTRA_SHIFT_INTERVAL = "extra_shift_interval"
        const val EXTRA_SEED = "extra_seed"
        const val EXTRA_MY_ID = "extra_my_id"
        const val EXTRA_NICKNAME = "extra_nickname"
        const val EXTRA_PLAYERS = "extra_players_json"
    }
}
