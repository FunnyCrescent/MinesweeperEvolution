package com.zminesweeper.game

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.zminesweeper.game.mp.JoinClientActivity
import com.zminesweeper.game.mp.LobbyHostActivity
import com.zminesweeper.game.mp.RelayHostActivity
import com.zminesweeper.game.mp.RelayJoinActivity

class MainActivity : AppCompatActivity() {

    private lateinit var save: SaveManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        save = SaveManager(this)

        findViewById<TextView>(R.id.title).text = getString(R.string.app_name)

        updateContinueButton()

        // Quick Game — старт с последним режимом/сложностью.
        findViewById<View>(R.id.btnQuickGame).setOnClickListener {
            val mode = save.lastMode()
            val diff = save.lastDifficulty()
            save.clearSavedGame()
            val intent = Intent(this, GameActivity::class.java)
            intent.putExtra(GameActivity.EXTRA_MODE, mode.key)
            intent.putExtra(GameActivity.EXTRA_DIFFICULTY, diff.key)
            startActivity(intent)
        }
        findViewById<android.widget.Button>(R.id.btnNewGame).setOnClickListener {
            showNewGameDialog()
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            showSettingsDialog()
        }
        findViewById<View>(R.id.btnStats).setOnClickListener {
            showStatsDialog()
        }
        findViewById<android.widget.Button>(R.id.btnMpHost).setOnClickListener {
            ensureNicknameThen { startActivity(Intent(this, LobbyHostActivity::class.java)) }
        }
        findViewById<android.widget.Button>(R.id.btnMpJoin).setOnClickListener {
            ensureNicknameThen { startActivity(Intent(this, JoinClientActivity::class.java)) }
        }
        findViewById<android.widget.Button>(R.id.btnMpRelayHost).setOnClickListener {
            ensureNicknameThen { startActivity(Intent(this, RelayHostActivity::class.java)) }
        }
        findViewById<android.widget.Button>(R.id.btnMpRelayJoin).setOnClickListener {
            ensureNicknameThen { startActivity(Intent(this, RelayJoinActivity::class.java)) }
        }
    }

    /** Диалог выбора режима и сложности с подтверждением «Начать игру». */
    private fun showNewGameDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_new_game, null)
        val modesContainer = view.findViewById<LinearLayout>(R.id.newGameModesContainer)
        val diffContainer = view.findViewById<LinearLayout>(R.id.newGameDiffContainer)
        val btnStart = view.findViewById<android.widget.Button>(R.id.btnStartGame)

        var selectedMode: GameMode? = null
        var selectedDiff: Difficulty? = null

        // Строим карточки режимов
        modesContainer.removeAllViews()
        for (mode in GameMode.entries) {
            val card = layoutInflater.inflate(R.layout.item_mode_card, modesContainer, false)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = 12
            card.layoutParams = params
            card.findViewById<TextView>(R.id.tvModeName).text = mode.display
            card.findViewById<TextView>(R.id.tvModeDesc).text = mode.shortDesc
            // Иконка режима — по имени ресурса mode_<key>.
            val iconId = resources.getIdentifier("mode_${mode.key}", "drawable", packageName)
            if (iconId != 0) {
                card.findViewById<android.widget.ImageView>(R.id.ivModeIcon)
                    .setImageResource(iconId)
            }
            card.setOnClickListener {
                selectedMode = mode
                for (i in 0 until modesContainer.childCount) {
                    modesContainer.getChildAt(i).isSelected = (modesContainer.getChildAt(i) === card)
                }
            }
            modesContainer.addView(card)
        }

        // Строим кнопки сложности
        diffContainer.removeAllViews()
        for (diff in Difficulty.entries) {
            val btn = android.widget.Button(this)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(4, 0, 4, 0)
            btn.layoutParams = lp
            btn.text = if (diff == Difficulty.CUSTOM) {
                getString(R.string.custom_size, MinesweeperApp.customRows, MinesweeperApp.customCols)
            } else {
                "${diff.display}\n${diff.shortDesc}"
            }
            btn.textSize = 11f
            btn.setBackgroundResource(R.drawable.ui_button_normal)
            btn.setTextColor(getColor(R.color.text_primary))
            btn.setPadding(4, 12, 4, 12)
            btn.gravity = Gravity.CENTER
            btn.isAllCaps = false
            btn.setOnClickListener {
                if (diff == Difficulty.CUSTOM) {
                    // Показываем диалог ввода размеров.
                    showCustomSizeDialog { _ ->
                        selectedDiff = Difficulty.CUSTOM
                        for (i in 0 until diffContainer.childCount) {
                            (diffContainer.getChildAt(i) as android.widget.Button).isSelected = false
                        }
                        btn.isSelected = true
                        btn.text = getString(R.string.custom_size, MinesweeperApp.customRows, MinesweeperApp.customCols)
                    }
                } else {
                    selectedDiff = diff
                    for (i in 0 until diffContainer.childCount) {
                        (diffContainer.getChildAt(i) as android.widget.Button).isSelected = false
                    }
                    btn.isSelected = true
                }
            }
            diffContainer.addView(btn)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.new_game)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .create()

        btnStart.setOnClickListener {
            val mode = selectedMode
            val diff = selectedDiff
            if (mode == null) {
                android.widget.Toast.makeText(this, R.string.select_mode_required, android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (diff == null) {
                android.widget.Toast.makeText(this, R.string.select_diff_required, android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Сохраняем последние выборы для кнопки «Повтор».
            save.setLastMode(mode)
            save.setLastDifficulty(diff)
            save.clearSavedGame()
            val intent = Intent(this, GameActivity::class.java)
            intent.putExtra(GameActivity.EXTRA_MODE, mode.key)
            intent.putExtra(GameActivity.EXTRA_DIFFICULTY, diff.key)
            startActivity(intent)
            dialog.dismiss()
        }

        dialog.show()
    }

    /** Диалог ввода кастомного размера поля. */
    private fun showCustomSizeDialog(onSelected: (Difficulty) -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }
        val etRows = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.rows_hint)
            setText(MinesweeperApp.customRows.toString())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val etCols = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.cols_hint)
            setText(MinesweeperApp.customCols.toString())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        container.addView(etRows)
        container.addView(etCols)
        AlertDialog.Builder(this)
            .setTitle(R.string.custom_size_title)
            .setMessage(R.string.custom_size_message)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val r = etRows.text.toString().toIntOrNull() ?: 16
                val c = etCols.text.toString().toIntOrNull() ?: 30
                val safeR = r.coerceIn(5, 30)
                val safeC = c.coerceIn(5, 50)
                MinesweeperApp.customRows = safeR
                MinesweeperApp.customCols = safeC
                save.setCustomSize(safeR, safeC)
                onSelected(Difficulty.CUSTOM)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Если у пользователя ещё нет ника — спросим. Иначе — запускаем [action]. */
    private fun ensureNicknameThen(action: () -> Unit) {
        val cur = save.nickname()
        if (cur.isNullOrBlank()) {
            val input = android.widget.EditText(this).apply {
                hint = getString(R.string.mp_nickname_hint)
                setSingleLine()
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.nickname_for_mp_title)
                .setMessage(R.string.nickname_for_mp_message)
                .setView(input)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val n = input.text.toString().trim().ifBlank { getString(R.string.default_player_name) }
                    save.setNickname(n)
                    action()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            action()
        }
    }

    override fun onResume() {
        super.onResume()
        updateContinueButton()
    }

    private fun updateContinueButton() {
        val btn = findViewById<android.widget.Button>(R.id.btnContinue)
        if (save.hasSavedGame()) {
            btn.visibility = View.VISIBLE
            btn.setOnClickListener {
                val intent = Intent(this, GameActivity::class.java)
                intent.putExtra(GameActivity.EXTRA_LOAD_SAVE, true)
                startActivity(intent)
            }
        } else {
            btn.visibility = View.GONE
        }
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val swVibration = view.findViewById<SwitchCompat>(R.id.swVibration)
        val swSound = view.findViewById<SwitchCompat>(R.id.swSound)
        val swLongPress = view.findViewById<SwitchCompat>(R.id.swLongPress)
        val sliderShift = view.findViewById<SeekBar>(R.id.sliderShift)
        val tvShiftVal = view.findViewById<TextView>(R.id.tvShiftVal)

        swVibration.isChecked = save.isVibration()
        swSound.isChecked = save.isSound()
        swLongPress.isChecked = save.isLongPressFlag()
        sliderShift.max = 27   // 27 = 30 - 3
        sliderShift.progress = save.shiftInterval() - 3
        tvShiftVal.text = getString(R.string.shift_value, save.shiftInterval())
        sliderShift.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvShiftVal.text = getString(R.string.shift_value, progress + 3)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                save.setVibration(swVibration.isChecked)
                save.setSound(swSound.isChecked)
                save.setLongPressFlag(swLongPress.isChecked)
                save.setShiftInterval(sliderShift.progress + 3)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showStatsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_stats, null)
        val container = view.findViewById<LinearLayout>(R.id.statsContainer)
        container.removeAllViews()
        for (mode in GameMode.entries) {
            val title = TextView(this).apply {
                text = mode.display
                setTextColor(getColor(R.color.accent))
                textSize = 16f
                setPadding(0, 16, 0, 4)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            container.addView(title)
            for (diff in Difficulty.entries) {
                val played = save.getPlayed(mode, diff)
                val won = save.getWon(mode, diff)
                val best = save.getBestTime(mode, diff)
                val bestStr = if (best == null) "—" else formatTime(best)
                val row = TextView(this).apply {
                    text = getString(R.string.stats_row_format, diff.display, won, played, bestStr)
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 13f
                    setPadding(4, 4, 4, 4)
                }
                container.addView(row)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.stats)
            .setView(view)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.reset_stats) { _, _ ->
                save.resetStats()
                showStatsDialog()
            }
            .show()
    }

    private fun formatTime(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return "%d:%02d".format(m, s)
    }
}
