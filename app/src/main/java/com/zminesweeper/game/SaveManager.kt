package com.zminesweeper.game

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Менеджер сохранений.
 *
 * Игровое состояние хранится В ФАЛЕ: /data/data/com.zminesweeper.game/files/save/game.txt
 * НЕ в SharedPreferences. Файл — надёжнее: данные гарантированно на диске.
 *
 * Настройки (звук, вибрация, ник) — в SharedPreferences, это нормально.
 * Игровое состояние (поле, мины, флаги) — в файле.
 */
class SaveManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- Сохранение игры в SharedPreferences (v1.2.9.2) ----------
    // Прозрачно, надёжно, синхронная запись через commit().
    // Состояние: JSON строка в SharedPreferences.

    /** Сохранить игровое состояние (JSON строка) в SharedPreferences. */
    fun saveGame(jsonStr: String) {
        try {
            prefs.edit().putString(KEY_GAME_STATE, jsonStr).commit()
            android.util.Log.d("MinesweeperSave", "saveGame: committed ${jsonStr.length} chars to SharedPreferences")
        } catch (e: Exception) {
            android.util.Log.e("MinesweeperSave", "saveGame: FAILED", e)
        }
    }

    /** Legacy — для ByteArray. */
    fun saveGame(data: ByteArray) {
        saveGame(String(data, Charsets.UTF_8))
    }

    /** Загрузить игровое состояние из SharedPreferences. Возвращает JSON строку или null. */
    fun loadGame(): String? {
        return try {
            val state = prefs.getString(KEY_GAME_STATE, null)
            android.util.Log.d("MinesweeperSave", "loadGame: read ${state?.length ?: 0} chars from SharedPreferences")
            state
        } catch (e: Exception) {
            android.util.Log.e("MinesweeperSave", "loadGame: FAILED", e)
            null
        }
    }

    fun hasSavedGame(): Boolean {
        val state = prefs.getString(KEY_GAME_STATE, null)
        val result = !state.isNullOrBlank()
        android.util.Log.d("MinesweeperSave", "hasSavedGame: state is ${if (state.isNullOrBlank()) "null/blank" else "${state.length} chars"}, result=$result")
        return result
    }

    fun clearSavedGame() {
        try {
            prefs.edit().remove(KEY_GAME_STATE).commit()
            android.util.Log.d("MinesweeperSave", "clearSavedGame: removed from SharedPreferences")
        } catch (e: Exception) {
            android.util.Log.e("MinesweeperSave", "clearSavedGame: FAILED", e)
        }
    }

    fun lastSaveTime(): Long = 0L  // Не используется в v1.2.9.2

    // ---------- Настройки (в SharedPreferences — это нормально) ----------

    // Язык: "system" (по умолчанию), "ru", "en", "es".
    fun setLanguage(lang: String) = prefs.edit().putString(KEY_LANGUAGE, lang).apply()
    fun language(): String = prefs.getString(KEY_LANGUAGE, "system") ?: "system"

    fun setVibration(enabled: Boolean) = prefs.edit().putBoolean(KEY_VIBRATION, enabled).apply()
    fun isVibration(): Boolean = prefs.getBoolean(KEY_VIBRATION, true)

    fun setSound(enabled: Boolean) = prefs.edit().putBoolean(KEY_SOUND, enabled).apply()
    fun isSound(): Boolean = prefs.getBoolean(KEY_SOUND, true)

    fun setLongPressFlag(enabled: Boolean) = prefs.edit().putBoolean(KEY_LONG_PRESS, enabled).apply()
    fun isLongPressFlag(): Boolean = prefs.getBoolean(KEY_LONG_PRESS, true)

    fun setShiftInterval(sec: Int) = prefs.edit().putInt(KEY_SHIFT_INTERVAL, sec).apply()
    fun shiftInterval(): Int = prefs.getInt(KEY_SHIFT_INTERVAL, 10)

    // ---------- Никнейм ----------

    fun setNickname(name: String?) {
        if (name.isNullOrBlank()) {
            prefs.edit().remove(KEY_NICKNAME).apply()
        } else {
            prefs.edit().putString(KEY_NICKNAME, name.trim().take(20)).apply()
        }
    }
    fun nickname(): String? = prefs.getString(KEY_NICKNAME, null)?.takeIf { it.isNotBlank() }

    // ---------- Последний режим/сложность ----------

    fun setLastMode(mode: GameMode) = prefs.edit().putString(KEY_LAST_MODE, mode.key).apply()
    fun lastMode(): GameMode = GameMode.fromKey(prefs.getString(KEY_LAST_MODE, null))

    fun setLastDifficulty(diff: Difficulty) = prefs.edit().putString(KEY_LAST_DIFF, diff.key).apply()
    fun lastDifficulty(): Difficulty = Difficulty.fromKey(prefs.getString(KEY_LAST_DIFF, null))

    fun setCustomSize(rows: Int, cols: Int) =
        prefs.edit().putInt(KEY_CUSTOM_ROWS, rows).putInt(KEY_CUSTOM_COLS, cols).apply()
    fun customRows(): Int = prefs.getInt(KEY_CUSTOM_ROWS, 16)
    fun customCols(): Int = prefs.getInt(KEY_CUSTOM_COLS, 30)

    // ---------- Сетевые настройки ----------

    fun setLastHostIp(ip: String?) =
        prefs.edit().apply {
            if (ip.isNullOrBlank()) remove(KEY_LAST_HOST_IP) else putString(KEY_LAST_HOST_IP, ip)
        }.apply()
    fun lastHostIp(): String? = prefs.getString(KEY_LAST_HOST_IP, null)

    fun setLastRelayUrl(url: String?) =
        prefs.edit().apply {
            if (url.isNullOrBlank()) remove(KEY_LAST_RELAY_URL) else putString(KEY_LAST_RELAY_URL, url)
        }.apply()
    fun lastRelayUrl(): String? = prefs.getString(KEY_LAST_RELAY_URL, null)

    // ---------- Статистика ----------

    fun recordGame(mode: GameMode, diff: Difficulty, won: Boolean, timeSec: Int) {
        val playedKey = "played_${mode.key}_${diff.key}"
        val wonKey = "won_${mode.key}_${diff.key}"
        val bestKey = "best_${mode.key}_${diff.key}"
        val played = prefs.getInt(playedKey, 0) + 1
        val wonTotal = prefs.getInt(wonKey, 0) + if (won) 1 else 0
        prefs.edit().putInt(playedKey, played).putInt(wonKey, wonTotal).apply()
        if (won) {
            val cur = prefs.getInt(bestKey, Int.MAX_VALUE)
            if (timeSec < cur) prefs.edit().putInt(bestKey, timeSec).apply()
        }
    }

    fun getPlayed(mode: GameMode, diff: Difficulty): Int =
        prefs.getInt("played_${mode.key}_${diff.key}", 0)

    fun getWon(mode: GameMode, diff: Difficulty): Int =
        prefs.getInt("won_${mode.key}_${diff.key}", 0)

    fun getBestTime(mode: GameMode, diff: Difficulty): Int? {
        val v = prefs.getInt("best_${mode.key}_${diff.key}", Int.MAX_VALUE)
        return if (v == Int.MAX_VALUE) null else v
    }

    fun resetStats() {
        val ed = prefs.edit()
        prefs.all.keys.filter { it.startsWith("played_") || it.startsWith("won_") || it.startsWith("best_") }
            .forEach { ed.remove(it) }
        ed.apply()
    }

    companion object {
        private const val PREFS = "minesweeper_prefs"
        private const val KEY_GAME_STATE = "game_state_json"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_VIBRATION = "vibration"
        private const val KEY_SOUND = "sound"
        private const val KEY_LONG_PRESS = "long_press_flag"
        private const val KEY_SHIFT_INTERVAL = "shift_interval"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_LAST_HOST_IP = "last_host_ip"
        private const val KEY_LAST_RELAY_URL = "last_relay_url"
        private const val KEY_LAST_MODE = "last_mode"
        private const val KEY_LAST_DIFF = "last_diff"
        private const val KEY_CUSTOM_ROWS = "custom_rows"
        private const val KEY_CUSTOM_COLS = "custom_cols"
    }
}
