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

    // ---------- Файловое сохранение игры (бинарный формат v1.2.8) ----------

    /** Папка для сохранений: /data/data/.../files/save/ */
    private val saveDir: File by lazy {
        File(appContext.filesDir, "save").also { it.mkdirs() }
    }
    /** Файл сохранения: save/game.bin (бинарный формат) */
    private val saveFile: File by lazy { File(saveDir, "game.bin") }
    /** Старый текстовый файл (для миграции) */
    private val legacyFile: File by lazy { File(saveDir, "game.txt") }

    /**
     * Сохранить игровое состояние в бинарный файл.
     * Атомарная запись: временный файл + rename — защищает от повреждений
     * при сбоях во время записи.
     */
    fun saveGame(data: ByteArray) {
        try {
            saveDir.mkdirs()
            // Пишем во временный файл, потом атомарно переименовываем.
            val tmpFile = File(saveDir, "game.bin.tmp")
            tmpFile.writeBytes(data)
            // renameTo — атомарная операция на большинстве ФС.
            if (!tmpFile.renameTo(saveFile)) {
                // Если rename не удался — fallback: копируем.
                tmpFile.copyTo(saveFile, overwrite = true)
                tmpFile.delete()
            }
            android.util.Log.d("MinesweeperSave", "saveGame: written ${data.size} bytes to ${saveFile.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("MinesweeperSave", "saveGame: FAILED", e)
        }
    }

    /** Legacy — для старого текстового формата. Не используется в v1.2.8+. */
    fun saveGame(state: String) {
        // Пробуем интерпретировать как бинарный (если строка содержит бинарные данные).
        // На практике не вызывается — GameActivity использует saveGame(ByteArray).
        try {
            saveDir.mkdirs()
            saveFile.writeBytes(state.toByteArray())
        } catch (e: Exception) {
            android.util.Log.e("MinesweeperSave", "saveGame(legacy): FAILED", e)
        }
    }

    /**
     * Загрузить игровое состояние из бинарного файла.
     * Возвращает null если файла нет или он повреждён.
     */
    fun loadGame(): ByteArray? {
        return try {
            if (!saveFile.exists() || saveFile.length() == 0L) {
                android.util.Log.d("MinesweeperSave", "loadGame: binary file not found, trying legacy")
                return null
            }
            val data = saveFile.readBytes()
            android.util.Log.d("MinesweeperSave", "loadGame: read ${data.size} bytes from ${saveFile.absolutePath}")
            data
        } catch (e: Exception) {
            android.util.Log.e("MinesweeperSave", "loadGame: FAILED", e)
            null
        }
    }

    /** Legacy — загрузка текстового формата. Для миграции со старых версий. */
    fun loadLegacyGame(): String? {
        return try {
            if (!legacyFile.exists() || legacyFile.length() == 0L) return null
            val data = legacyFile.readText()
            android.util.Log.d("MinesweeperSave", "loadLegacyGame: read ${data.length} chars from ${legacyFile.absolutePath}")
            data
        } catch (e: Exception) {
            null
        }
    }

    fun hasSavedGame(): Boolean = saveFile.exists() && saveFile.length() > 0

    fun clearSavedGame() {
        try {
            if (saveFile.exists()) saveFile.delete()
            if (legacyFile.exists()) legacyFile.delete()
            android.util.Log.d("MinesweeperSave", "clearSavedGame: deleted")
        } catch (e: Exception) {
            android.util.Log.e("MinesweeperSave", "clearSavedGame: FAILED", e)
        }
    }

    fun lastSaveTime(): Long = if (saveFile.exists()) saveFile.lastModified() else 0L

    // ---------- Настройки (в SharedPreferences — это нормально) ----------

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
