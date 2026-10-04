package com.zminesweeper.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Менеджер коротких звуковых эффектов.
 * Звуки лежат в res/raw (файлы .wav), загружаются через SoundPool.
 *
 * Использование:
 *   val snd = SoundManager(context)
 *   snd.play(SoundManager.Type.REVEAL)
 *
 *   snd.release()  // когда активность уничтожается
 *
 * Настройка звука хранится в SaveManager.isSound().
 */
class SoundManager(context: Context) {

    enum class Type {
        REVEAL,
        EXPLODE,
        FLAG,
        WIN,
        SHIFT,
        CLICK,
    }

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = HashMap<Type, Int>()
    @Volatile var enabled: Boolean = true

    init {
        val lookup = mapOf(
            Type.REVEAL  to R.raw.reveal,
            Type.EXPLODE to R.raw.explode,
            Type.FLAG    to R.raw.flag,
            Type.WIN     to R.raw.win,
            Type.SHIFT   to R.raw.shift,
            Type.CLICK   to R.raw.click,
        )
        for ((type, resId) in lookup) {
            ids[type] = pool.load(context, resId, 1)
        }
    }

    fun play(type: Type, volume: Float = 1.0f) {
        if (!enabled) return
        val id = ids[type] ?: return
        // SoundPool.play возвращает ненулевой streamId, если звук запущен.
        // Если файл ещё не загрузился — он просто не заиграет; ничего страшного.
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    fun release() {
        pool.release()
    }
}
