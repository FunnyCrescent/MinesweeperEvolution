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
import android.widget.ImageView
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

    /** Автосохранение каждые 5 секунд во время игры. */
    private val autoSaveRunnable = object : Runnable {
        override fun run() {
            if (!engine.gameOver) {
                save.saveGame(engine.serialize())
            }
            handler.postDelayed(this, 5000)
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
                    shiftRemainingSec = if (engine.mode.coversAllAfterShift) 25 else save.shiftInterval()
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
                findViewById<TextView>(R.id.tvShift).text = shiftRemainingSec.toString()
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
            android.util.Log.d("MinesweeperSave", "Loaded state: ${state.length} chars")
            engine = GameEngine.deserialize(state) ?: run {
                android.util.Log.e("MinesweeperSave", "Deserialize returned null — starting new game")
                android.widget.Toast.makeText(this,
                    getString(R.string.save_corrupted),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                save.clearSavedGame()
                initNewGame(GameMode.CLASSIC, Difficulty.BEGINNER)
                engine
            }
            // ВАЖНО: после десериализации НЕ вызываем initGame или placeMinesRandomly.
            // Мины уже загружены из файла. Ничего не должно их менять.
            if (engine != null) {
                android.util.Log.d("MinesweeperSave", "Engine loaded: ${engine!!.rows}x${engine!!.cols}, mines=${engine!!.mineCount}, revealed=${engine!!.revealedCount}, firstClick=${engine!!.firstClickDone}")
            }
            savedFromLoaded = true
        } else {
            val mode = GameMode.fromKey(intent.getStringExtra(EXTRA_MODE))
            val diff = Difficulty.fromKey(intent.getStringExtra(EXTRA_DIFFICULTY))
            initNewGame(mode, diff)
        }

        gameView.engine = engine
        // Принудительная перерисовка. Ключевая проблема: после setContentView() View
        // ещё не имеет реальных размеров (layout не прошёл). Если вызвать invalidate()
        // сразу, onDraw сработает с width=height=0 и canvas останется пустым.
        // Решение: отложенный post + onGlobalLayout — гарантия что canvas измерен.
        gameView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                gameView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                gameView.engine = engine  // перепривязываем — это вызовет invalidate()
                gameView.invalidate()
            }
        })
        // Дополнительный фолбэк через 200мс — на случай если onGlobalLayout не вызвался
        gameView.postDelayed({
            if (gameView.width > 0 && gameView.height > 0) {
                gameView.invalidate()
            }
        }, 200)
        gameView.onRevealListener = { row, col, exploded, won ->
            // Анимация волной от точки клика по всем открытым в этом ходе клеткам
            gameView.animateRevealWave(engine.lastRevealed, row, col)
            if (exploded) {
                sound?.play(SoundManager.Type.EXPLODE)
            } else if (won) {
                sound?.play(SoundManager.Type.WIN)
            } else {
                sound?.play(SoundManager.Type.REVEAL)
                // No-Guess Solver: проверить на софтлок после открытия.
                val state = engine.analyzeSoftlock()
                if (state is com.zminesweeper.game.NoGuessSolver.SoftlockState.Deadlock) {
                    // Чистый софтлок генерации — перетасовываем мины.
                    engine.reshuffleMinesForLogicalMove()
                    gameView.invalidate()
                }
            }
            if (exploded || won) showGameOver(won)
            updateMinesLabel()
        }
        gameView.onFlagListener = { _, _ ->
            sound?.play(SoundManager.Type.FLAG)
            updateMinesLabel()
        }

        findViewById<ImageView>(R.id.btnFlagMode).setOnClickListener {
            gameView.flagMode = !gameView.flagMode
            it.isSelected = gameView.flagMode
            // Меняем иконку: флажок / кирка (dig mode).
            val resId = if (gameView.flagMode)
                R.drawable.item_flag  // в режиме флажка показываем флажок
            else
                R.drawable.tile_open  // в режиме копания — открытая плитка
            (it as ImageView).setImageResource(resId)
            sound?.play(SoundManager.Type.CLICK)
        }
        findViewById<ImageView>(R.id.btnMenu).setOnClickListener {
            confirmExit()
        }

        updateModeLabel()
        updateMinesLabel()
        findViewById<TextView>(R.id.tvShift).text = if (engine.mode.shifts) (if (engine.mode.coversAllAfterShift) 25 else save.shiftInterval()).toString() else "—"
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
        handler.removeCallbacks(autoSaveRunnable)
        handler.post(tickRunnable)
        handler.postDelayed(autoSaveRunnable, 5000)  // автосохранение каждые 5с
        if (engine.mode.shifts) {
            shiftRemainingSec = if (engine.mode.coversAllAfterShift) 25 else save.shiftInterval()
            handler.post(shiftRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(shiftRunnable)
        // Автосохранение при любом выходе (сворачивание, переключение приложения,
        // кнопка Home и т.д.) — только если игра ещё идёт.
        if (!engine.gameOver) {
            save.saveGame(engine.serialize())
        } else if (savedFromLoaded) {
            save.clearSavedGame()
        }
    }

    override fun onStop() {
        super.onStop()
        // Дополнительное сохранение в onStop — Activity может быть убита
        // между onPause и onDestroy без возвращения.
        if (!engine.gameOver) {
            save.saveGame(engine.serialize())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(shiftRunnable)
        handler.removeCallbacks(autoSaveRunnable)
        sound?.release()
        sound = null
        // Финальное сохранение — на всякий случай.
        if (!engine.gameOver) {
            save.saveGame(engine.serialize())
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        confirmExit()
    }

    private fun confirmExit() {
        // Пауза: останавливаем таймеры пока диалог открыт.
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(shiftRunnable)
        AlertDialog.Builder(this)
            .setTitle(R.string.pause_title)
            .setMessage(R.string.pause_message)
            .setPositiveButton(R.string.exit) { _, _ ->
                if (!engine.gameOver) save.saveGame(engine.serialize())
                finish()
            }
            .setNegativeButton(R.string.continue_game) { _, _ ->
                // Возобновляем таймеры с учётом паузы.
                startTimeMs = System.currentTimeMillis() - elapsedSec * 1000L
                handler.post(tickRunnable)
                if (engine.mode.shifts && !engine.gameOver) {
                    handler.post(shiftRunnable)
                }
            }
            .setNeutralButton(R.string.restart) { _, _ ->
                restartGame()
            }
            .setCancelable(false)
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
        findViewById<TextView>(R.id.tvShift).text = if (mode.shifts) (if (engine.mode.coversAllAfterShift) 25 else save.shiftInterval()).toString() else "—"
        findViewById<View>(R.id.llShift).visibility = if (mode.shifts) View.VISIBLE else View.INVISIBLE

        // Сброс таймера
        startTimeMs = System.currentTimeMillis()
        elapsedSec = 0
        findViewById<TextView>(R.id.tvTime).text = "0:00"

        // Сброс счётчика сдвига
        if (mode.shifts) {
            shiftRemainingSec = if (mode.coversAllAfterShift) 25 else save.shiftInterval()
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
        // Маскот: победа → mascot_victory, поражение → mascot_gameover
        val mascotResId = if (won) R.drawable.mascot_victory else R.drawable.mascot_gameover
        view.findViewById<android.widget.ImageView>(R.id.ivMascot).setImageResource(mascotResId)
        view.findViewById<TextView>(R.id.tvResultTitle).text =
            if (won) getString(R.string.win) else getString(R.string.lose)
        val shifts = if (engine.mode.shifts) getString(R.string.shifts_label, engine.shiftsCount) else ""
        view.findViewById<TextView>(R.id.tvResultDetails).text =
            getString(R.string.time_label, formatTime(elapsedSec)) + "\n" + shifts
        view.findViewById<android.widget.Button>(R.id.btnPlayAgain).setOnClickListener {
            dialog?.dismiss()
            restartGame()
        }
        view.findViewById<android.widget.Button>(R.id.btnToMenu).setOnClickListener {
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
