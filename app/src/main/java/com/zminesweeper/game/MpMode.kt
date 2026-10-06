package com.zminesweeper.game

/**
 * Режим мультиплеера.
 *  COOP         — вместе: ходы по кругу, как сейчас.
 *  COMPETITIVE  — гонка: каждый играет на своём поле с одинаковым seed.
 *                 Кто первый открыл всё поле — победил.
 *                 При взрыве на мине → новый уровень (новый seed), не проигрыш.
 */
enum class MpMode(
    val key: String,
    val displayRes: Int,
    val shortDescRes: Int,
) {
    COOP("coop", R.string.mp_mode_coop, R.string.mp_mode_coop_desc),
    COMPETITIVE("competitive", R.string.mp_mode_competitive, R.string.mp_mode_competitive_desc);

    val display: String
        get() = MinesweeperApp.instance.getString(displayRes)

    val shortDesc: String
        get() = MinesweeperApp.instance.getString(shortDescRes)

    companion object {
        fun fromKey(key: String?): MpMode =
            entries.firstOrNull { it.key == key } ?: COOP
    }
}
