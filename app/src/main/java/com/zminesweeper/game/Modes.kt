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
 *
 *  displayRes / shortDescRes — ссылки на строковые ресурсы, чтобы
 *  имена режимов и их описания локализовались (RU/EN/ES).
 */
enum class GameMode(
    val key: String,
    val displayRes: Int,
    val shortDescRes: Int,
    val hasMineLimit: Boolean,
    val shifts: Boolean,
    val preservesFlags: Boolean,
    val coversAllAfterShift: Boolean = false,
) {
    CLASSIC(
        key = "classic",
        displayRes = R.string.mode_classic,
        shortDescRes = R.string.mode_classic_desc,
        hasMineLimit = true,
        shifts = false,
        preservesFlags = true,
    ),
    DRIFT(
        key = "drift",
        displayRes = R.string.mode_drift,
        shortDescRes = R.string.mode_drift_desc,
        hasMineLimit = true,
        shifts = true,
        preservesFlags = true,
    ),
    CHAOS(
        key = "chaos",
        displayRes = R.string.mode_chaos,
        shortDescRes = R.string.mode_chaos_desc,
        hasMineLimit = true,
        shifts = true,
        preservesFlags = false,
    ),
    ANARCHY(
        key = "anarchy",
        displayRes = R.string.mode_anarchy,
        shortDescRes = R.string.mode_anarchy_desc,
        hasMineLimit = false,
        shifts = true,
        preservesFlags = false,
    ),
    AVALANCHE(
        key = "avalanche",
        displayRes = R.string.mode_avalanche,
        shortDescRes = R.string.mode_avalanche_desc,
        hasMineLimit = true,
        shifts = true,
        preservesFlags = true,
        coversAllAfterShift = true,
    );

    /** Локализованное имя режима. Доступ к ресурсам через MinesweeperApp.instance. */
    val display: String
        get() = MinesweeperApp.instance.getString(displayRes)

    /** Локализованное описание. */
    val shortDesc: String
        get() = MinesweeperApp.instance.getString(shortDescRes)

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
    val displayRes: Int,
    val shortDescRes: Int,
    val rows: Int,
    val cols: Int,
    val mineCount: Int,
) {
    BEGINNER("beginner", R.string.diff_beginner, R.string.diff_beginner_desc, 8, 8, 10),
    VETERAN("veteran", R.string.diff_veteran, R.string.diff_veteran_desc, 16, 16, 40),
    MASTER("master", R.string.diff_master, R.string.diff_master_desc, 16, 30, 99),
    CUSTOM("custom", R.string.diff_custom, R.string.diff_custom_desc, 16, 30, 0);  // rows/cols берём из SaveManager

    val display: String
        get() = MinesweeperApp.instance.getString(displayRes)

    val shortDesc: String
        get() = MinesweeperApp.instance.getString(shortDescRes)

    companion object {
        fun fromKey(key: String?): Difficulty =
            entries.firstOrNull { it.key == key } ?: BEGINNER
    }
}
