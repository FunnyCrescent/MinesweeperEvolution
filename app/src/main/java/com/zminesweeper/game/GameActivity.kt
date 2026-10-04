package com.zminesweeper.game

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class GameActivity : AppCompatActivity() {

    private lateinit var engine: GameEngine
    private lateinit var save: SaveManager
    private lateinit var gameView: GameView
    private var sound: SoundManager? = null

    private val handler = Handler(Looper.getMainLooper())

    private var startTimeMs: Long = 0L
    private var elapsedSec: Int = 0
    private var savedFromLoaded: Boolean = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            elapsedSec = ((System.currentTimeMillis() - startTimeMs) / 1000).toInt()
            findViewById<TextView>(R.id.tvTime).text = formatTime(elapsedSec)
            handler.postDelayed(this, 500)
        }
    }

    private var shiftRemainingSec: Int = 0
    private val shiftRunnable = object : Runnable {
        override fun run() {
            if (!engine.gameOver && engine.mode.shifts && engine.firstClickDone) {
                shiftRemainingSec--
                if (shiftRemainingSec <= 0) {
                    engine.shiftMines()
                    gameView.animateShift()
                    haptic(heavy = false)
                    sound?.play(SoundManager.Type.SHIFT)
                    shiftRemainingSec = save.shiftInterval()
                    // Защита от клика в течение 1 секунды после сдвига
                    gameView.shiftCooldownUntilMs = SystemClock.uptimeMillis() + 1000
                    updateMinesLabel()
                    findViewById<TextView>(R.id.tvShift).setTextColor(getColor(R.color.warning))
                } else if (shiftRemainingSec <= 3) {
                    // Нарастающий тик в последние 3 секунды
                    val vol = 0.3f + (3 - shiftRemainingSec) * 0.2f  // 0.3, 0.5, 0.7
                    sound?.play(SoundManager.Type.TICK, vol)
                    findViewById<TextView>(R.id.tvShift).setTextColor(getColor(R.color.danger))
                } else {
                    findViewById<TextView>(R.id.tvShift).setTextColor(getColor(R.color.warning))
                }
                findViewById<TextView>(R.id.tvShift).text = "${shiftRemainingSec}с"
            } else if (!engine.firstClickDone) {
                findViewById<TextView>(R.id.tvShift).text = "—"
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)
        save = SaveManager(this)
        gameView = findViewById(R.id.gameView)
        sound = SoundManager(this).also { it.enabled = save.isSound() }

        val loadSave = intent.getBooleanExtra(EXTRA_LOAD_SAVE, false)
        if (loadSave && save.hasSavedGame()) {
            val state = save.loadGame()!!
            engine = GameEngine.deserialize(state) ?: run {
                initNewGame(GameMode.CLASSIC, Difficulty.BEGINNER)
                engine
            }
            savedFromLoaded = true
        } else {
            val mode = GameMode.fromKey(intent.getStringExtra(EXTRA_MODE))
            val diff = Difficulty.fromKey(intent.getStringExtra(EXTRA_DIFFICULTY))
            initNewGame(mode, diff)
        }

        gameView.engine = engine
        gameView.onRevealListener = { row, col, exploded, won ->
            // Анимация волной от точки клика по всем открытым в этом ходе клеткам
            gameView.animateRevealWave(engine.lastRevealed, row, col)
            if (exploded) {
                sound?.play(SoundManager.Type.EXPLODE)
            } else if (won) {
                sound?.play(SoundManager.Type.WIN)
            } else {
                sound?.play(SoundManager.Type.REVEAL)
            }
            if (exploded || won) showGameOver(won)
            updateMinesLabel()
        }
        gameView.onFlagListener = { _, _ ->
            sound?.play(SoundManager.Type.FLAG)
            updateMinesLabel()
        }

        findViewById<Button>(R.id.btnFlagMode).setOnClickListener {
            gameView.flagMode = !gameView.flagMode
            it.isSelected = gameView.flagMode
            (it as Button).text = if (gameView.flagMode) "⛏" else "🚩"
            sound?.play(SoundManager.Type.CLICK)
        }
        findViewById<Button>(R.id.btnMenu).setOnClickListener {
            confirmExit()
        }

        updateModeLabel()
        updateMinesLabel()
        findViewById<TextView>(R.id.tvShift).text = if (engine.mode.shifts) "${save.shiftInterval()}с" else "—"
        findViewById<View>(R.id.llShift).visibility = if (engine.mode.shifts) View.VISIBLE else View.INVISIBLE

        startTimeMs = System.currentTimeMillis()
        findViewById<TextView>(R.id.tvTime).text = "0:00"
    }

    private fun initNewGame(mode: GameMode, diff: Difficulty) {
        engine = GameEngine()
        engine.initGame(mode, diff)
        elapsedSec = 0
        savedFromLoaded = false
    }

    override fun onResume() {
        super.onResume()
        // Убираем все предыдущие runnable, чтобы не было дублей
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(shiftRunnable)
        handler.post(tickRunnable)
        if (engine.mode.shifts) {
            shiftRemainingSec = save.shiftInterval()
            handler.post(shiftRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(shiftRunnable)
        // Автосохранение при выходе — только если игра ещё идёт.
        if (!engine.gameOver) {
            save.saveGame(engine.serialize())
        } else if (savedFromLoaded) {
            save.clearSavedGame()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(shiftRunnable)
        sound?.release()
        sound = null
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        confirmExit()
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("Выйти в меню?")
            .setMessage("Игра будет сохранена автоматически.")
            .setPositiveButton("Выйти") { _, _ ->
                if (!engine.gameOver) save.saveGame(engine.serialize())
                finish()
            }
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Заново") { _, _ ->
                restartGame()
            }
            .show()
    }

    private fun restartGame() {
        // Полный сброс handlers и движка
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(shiftRunnable)

        val mode = engine.mode
        val diff = engine.difficulty
        initNewGame(mode, diff)
        gameView.engine = engine

        updateModeLabel()
        updateMinesLabel()
        findViewById<TextView>(R.id.tvShift).text = if (mode.shifts) "${save.shiftInterval()}с" else "—"
        findViewById<View>(R.id.llShift).visibility = if (mode.shifts) View.VISIBLE else View.INVISIBLE

        // Сброс таймера
        startTimeMs = System.currentTimeMillis()
        elapsedSec = 0
        findViewById<TextView>(R.id.tvTime).text = "0:00"

        // Сброс счётчика сдвига
        if (mode.shifts) {
            shiftRemainingSec = save.shiftInterval()
        }

        // Перезапуск handlers — КЛЮЧЕВОЙ ФИКС: ранее shiftRunnable не перезапускался
        handler.post(tickRunnable)
        if (mode.shifts) {
            handler.post(shiftRunnable)
        }

        save.clearSavedGame()
    }

    private fun updateModeLabel() {
        val text = "${engine.mode.display} · ${engine.difficulty.display}"
        findViewById<TextView>(R.id.tvModeLabel).text = text
    }

    private fun updateMinesLabel() {
        val mines = if (engine.mode.hasMineLimit) engine.minesLeft().toString() else "~${engine.mineCount}"
        findViewById<TextView>(R.id.tvMines).text = mines
    }

    private fun showGameOver(won: Boolean) {
        // Останавливаем таймер и сдвиг
        handler.removeCallbacks(shiftRunnable)
        handler.removeCallbacks(tickRunnable)

        if (won) {
            save.recordGame(engine.mode, engine.difficulty, true, elapsedSec)
        } else {
            save.recordGame(engine.mode, engine.difficulty, false, 0)
        }
        save.clearSavedGame()

        val view = layoutInflater.inflate(R.layout.dialog_game_over, null)
        view.findViewById<TextView>(R.id.tvResultIcon).text = if (won) "🏆" else "💥"
        view.findViewById<TextView>(R.id.tvResultTitle).text =
            if (won) getString(R.string.win) else getString(R.string.lose)
        val shifts = if (engine.mode.shifts) "Сдвигов: ${engine.shiftsCount}" else ""
        view.findViewById<TextView>(R.id.tvResultDetails).text =
            "Время: ${formatTime(elapsedSec)}\n$shifts"
        view.findViewById<Button>(R.id.btnPlayAgain).setOnClickListener {
            dialog?.dismiss()
            restartGame()
        }
        view.findViewById<Button>(R.id.btnToMenu).setOnClickListener {
            dialog?.dismiss()
            finish()
        }
        dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog?.show()
    }

    private var dialog: AlertDialog? = null

    private fun formatTime(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return "%d:%02d".format(m, s)
    }

    fun haptic(heavy: Boolean) {
        if (!save.isVibration()) return
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            } ?: return
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val effect = if (heavy)
                    VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
                else
                    VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(if (heavy) 120L else 30L)
            }
        } catch (_: Exception) {}
    }

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_DIFFICULTY = "extra_difficulty"
        const val EXTRA_LOAD_SAVE = "extra_load_save"
    }
}
