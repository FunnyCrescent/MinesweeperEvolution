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
        TICK,
        // Chain explosion sounds (v1.3.0)
        EXPLOSION_FIRST,
        EXPLOSION_CHAIN,
        CHAIN_END,
        DIALOG_OPEN,
        FUSE_TICK,
    }

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = HashMap<Type, Int>()
    private val chainIds = ArrayList<Int>()  // sfx_explosion_01..04
    @Volatile var enabled: Boolean = true

    init {
        val lookup = mapOf(
            Type.REVEAL  to R.raw.reveal,
            Type.EXPLODE to R.raw.explode,
            Type.FLAG    to R.raw.flag,
            Type.WIN     to R.raw.win,
            Type.SHIFT   to R.raw.shift,
            Type.CLICK   to R.raw.click,
            Type.TICK    to R.raw.tick,
            Type.EXPLOSION_FIRST to R.raw.sfx_explosion_first,
            Type.CHAIN_END       to R.raw.sfx_chain_end,
            Type.DIALOG_OPEN     to R.raw.sfx_dialog_open,
            Type.FUSE_TICK       to R.raw.sfx_fuse_tick,
        )
        for ((type, resId) in lookup) {
            ids[type] = pool.load(context, resId, 1)
        }
        // Chain explosion variants (01..04)
        for (resId in intArrayOf(R.raw.sfx_explosion_01, R.raw.sfx_explosion_02, R.raw.sfx_explosion_03, R.raw.sfx_explosion_04)) {
            chainIds.add(pool.load(context, resId, 1))
        }
        ids[Type.EXPLOSION_CHAIN] = chainIds.firstOrNull() ?: 0
    }

    fun play(type: Type, volume: Float = 1.0f) {
        if (!enabled) return
        val id = ids[type] ?: return
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    /** Случайный звук взрыва из sfx_explosion_01..04. */
    fun playRandomChainExplosion(volume: Float = 0.7f) {
        if (!enabled) return
        if (chainIds.isEmpty()) return
        val id = chainIds[kotlin.random.Random.nextInt(chainIds.size)]
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    fun release() {
        pool.release()
    }
}
