package com.zminesweeper.game

/**
 * Режимы игры.
 *  CLASSIC   — классический сапёр, мины стоят на месте.
 *  DRIFT     — «Дрейф»: каждые N секунд мины, не находящиеся под флажком,
 *              меняют местоположение. Флажки сохраняются и фиксируют мины.
 *  CHAOS     — «Хаос»: каждые N секунд все мины перемещаются,
 *              все флажки сбрасываются.
 *  ANARCHY   — «Анархия»: как Хаос, но количество мин каждый сдвиг случайно
 *              (от 1 до размера поля). Лимита мин нет.
 *  AVALANCHE — «Лавина»: как Дрейф (мины под флажком остаются, остальные
 *              перемещаются), но каждые 25 секунд ВСЁ поле закрывается заново.
 *              Цель — поставить флажки на все мины. Первый клик после
 *              каждого покрытия безопасен.
 */
enum class GameMode(
    val key: String,
    val display: String,
    val shortDesc: String,
    val hasMineLimit: Boolean,
    val shifts: Boolean,
    val preservesFlags: Boolean,
    val coversAllAfterShift: Boolean = false,
) {
    CLASSIC(
        key = "classic",
        display = "Классика",
        shortDesc = "Классический сапёр. Мины стоят на месте.",
        hasMineLimit = true,
        shifts = false,
        preservesFlags = true,
    ),
    DRIFT(
        key = "drift",
        display = "Дрейф",
        shortDesc = "Мины дрейфуют каждые 10 сек. Под флажком — остаются.",
        hasMineLimit = true,
        shifts = true,
        preservesFlags = true,
    ),
    CHAOS(
        key = "chaos",
        display = "Хаос",
        shortDesc = "Все мины перемещаются, флажки сбрасываются.",
        hasMineLimit = true,
        shifts = true,
        preservesFlags = false,
    ),
    ANARCHY(
        key = "anarchy",
        display = "Анархия",
        shortDesc = "Хаос + случайное число мин. Лимита нет!",
        hasMineLimit = false,
        shifts = true,
        preservesFlags = false,
    ),
    AVALANCHE(
        key = "avalanche",
        display = "Лавина",
        shortDesc = "Каждые 25 сек всё поле закрывается. Мины под флажком остаются. Цель — флажки на все мины.",
        hasMineLimit = true,
        shifts = true,
        preservesFlags = true,
        coversAllAfterShift = true,
    );

    companion object {
        fun fromKey(key: String?): GameMode =
            entries.firstOrNull { it.key == key } ?: CLASSIC
    }
}

/**
 * Подрежимы (сложность).
 *  BEGINNER  — 8 × 8, 10 мин.
 *  VETERAN   — 16 × 16, 40 мин.
 *  MASTER    — 16 × 30, 99 мин.
 */
enum class Difficulty(
    val key: String,
    val display: String,
    val shortDesc: String,
    val rows: Int,
    val cols: Int,
    val mineCount: Int,
) {
    BEGINNER("beginner", "Новичок", "8 × 8 · 10 мин", 8, 8, 10),
    VETERAN("veteran", "Ветеран", "16 × 16 · 40 мин", 16, 16, 40),
    MASTER("master", "Мастер", "16 × 30 · 99 мин", 16, 30, 99),
    CUSTOM("custom", "Своя", "Свой размер поля", 16, 30, 0);  // rows/cols берём из SaveManager

    companion object {
        fun fromKey(key: String?): Difficulty =
            entries.firstOrNull { it.key == key } ?: BEGINNER
    }
}
