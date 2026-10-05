package com.zminesweeper.game

/**
 * Режим мультиплеера.
 *  COOP         — вместе: ходы по кругу, как сейчас.
 *  COMPETITIVE  — гонка: каждый играет на своём поле с одинаковым seed.
 *                 Кто первый открыл всё поле — победил.
 *                 При взрыве на мине → новый уровень (новый seed), не проигрыш.
 */
enum class MpMode(val key: String, val display: String, val shortDesc: String) {
    COOP("coop", "Вместе", "Ходы по кругу, одно поле на всех"),
    COMPETITIVE("competitive", "Гонка", "Каждый на своём поле, кто быстрее");

    companion object {
        fun fromKey(key: String?): MpMode =
            entries.firstOrNull { it.key == key } ?: COOP
    }
}
