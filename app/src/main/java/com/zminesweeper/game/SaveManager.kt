package com.zminesweeper.game

import android.content.Context
import android.content.SharedPreferences

/**
 * Менеджер сохранений и статистики через SharedPreferences.
 *  - autosave: автоматическое сохранение при выходе из GameActivity
 *  - manualSave: ручное сохранение
 *  - statsPerMode: победы/игры/лучшее время для каждой пары (mode, difficulty)
 */
class SaveManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- Активное сохранение игры ----------

    fun saveGame(state: String) {
        prefs.edit().putString(KEY_GAME_STATE, state).putLong(KEY_SAVE_TIME, System.currentTimeMillis()).apply()
    }

    fun loadGame(): String? = prefs.getString(KEY_GAME_STATE, null)

    fun hasSavedGame(): Boolean = prefs.getString(KEY_GAME_STATE, null) != null

    fun clearSavedGame() {
        prefs.edit().remove(KEY_GAME_STATE).remove(KEY_SAVE_TIME).apply()
    }

    fun lastSaveTime(): Long = prefs.getLong(KEY_SAVE_TIME, 0L)

    // ---------- Настройки ----------

    fun setVibration(enabled: Boolean) = prefs.edit().putBoolean(KEY_VIBRATION, enabled).apply()
    fun isVibration(): Boolean = prefs.getBoolean(KEY_VIBRATION, true)

    fun setSound(enabled: Boolean) = prefs.edit().putBoolean(KEY_SOUND, enabled).apply()
    fun isSound(): Boolean = prefs.getBoolean(KEY_SOUND, true)

    fun setLongPressFlag(enabled: Boolean) = prefs.edit().putBoolean(KEY_LONG_PRESS, enabled).apply()
    fun isLongPressFlag(): Boolean = prefs.getBoolean(KEY_LONG_PRESS, true)

    fun setShiftInterval(sec: Int) = prefs.edit().putInt(KEY_SHIFT_INTERVAL, sec).apply()
    fun shiftInterval(): Int = prefs.getInt(KEY_SHIFT_INTERVAL, 10)

    // ---------- Никнейм для мультиплеера ----------

    fun setNickname(name: String?) {
        if (name.isNullOrBlank()) {
            prefs.edit().remove(KEY_NICKNAME).apply()
        } else {
            prefs.edit().putString(KEY_NICKNAME, name.trim().take(20)).apply()
        }
    }
    fun nickname(): String? = prefs.getString(KEY_NICKNAME, null)?.takeIf { it.isNotBlank() }

    // ---------- Последний IP хоста для мультиплеера ----------

    fun setLastHostIp(ip: String?) =
        prefs.edit().apply {
            if (ip.isNullOrBlank()) remove(KEY_LAST_HOST_IP) else putString(KEY_LAST_HOST_IP, ip)
        }.apply()
    fun lastHostIp(): String? = prefs.getString(KEY_LAST_HOST_IP, null)

    // ---------- URL relay-сервера для кроссплатформенного мультиплеера ----------

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
        private const val KEY_GAME_STATE = "game_state"
        private const val KEY_SAVE_TIME = "save_time"
        private const val KEY_VIBRATION = "vibration"
        private const val KEY_SOUND = "sound"
        private const val KEY_LONG_PRESS = "long_press_flag"
        private const val KEY_SHIFT_INTERVAL = "shift_interval"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_LAST_HOST_IP = "last_host_ip"
        private const val KEY_LAST_RELAY_URL = "last_relay_url"
    }
}
