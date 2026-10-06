package com.zminesweeper.game

/**
 * JNI мост к нативному D-движку (libminesweeper_engine.so).
 *
 * Kotlin UI вызывает эти методы — D-движок обрабатывает игровую логику.
 * UI отвечает ТОЛЬКО за отрисовку Canvas/Bitmap, звуки, и передачу касаний.
 */
object NativeBridge {

    init {
        System.loadLibrary("minesweeper_engine")
    }

    // ===== Инициализация =====
    fun nativeInit(mode: Int, rows: Int, cols: Int, mineCount: Int) {
        _nativeInit(mode, rows, cols, mineCount)
    }

    // ===== Игровые действия =====
    fun reveal(row: Int, col: Int): Int = _nativeReveal(row, col)
    fun toggleFlag(row: Int, col: Int): Boolean = _nativeToggleFlag(row, col)
    fun shiftMines() = _nativeShiftMines()

    // ===== Запрос состояния =====
    fun isMine(row: Int, col: Int): Boolean = _nativeIsMine(row, col)
    fun isRevealed(row: Int, col: Int): Boolean = _nativeIsRevealed(row, col)
    fun isFlagged(row: Int, col: Int): Boolean = _nativeIsFlagged(row, col)
    fun adjacentMines(row: Int, col: Int): Int = _nativeAdjacentMines(row, col)
    fun minesLeft(): Int = _nativeGetMinesLeft()
    fun isGameOver(): Boolean = _nativeIsGameOver()
    fun isWon(): Boolean = _nativeIsWon()
    fun explodedRow(): Int = _nativeGetExplodedRow()
    fun explodedCol(): Int = _nativeGetExplodedCol()
    fun shiftsCount(): Int = _nativeGetShiftsCount()
    fun rows(): Int = _nativeGetRows()
    fun cols(): Int = _nativeGetCols()
    fun mineCount(): Int = _nativeGetMineCount()
    fun firstClickDone(): Boolean = _nativeIsFirstClickDone()

    // ===== Native declarations =====
    @JvmStatic private external fun _nativeInit(mode: Int, rows: Int, cols: Int, mineCount: Int)
    @JvmStatic private external fun _nativeReveal(row: Int, col: Int): Int
    @JvmStatic private external fun _nativeToggleFlag(row: Int, col: Int): Boolean
    @JvmStatic private external fun _nativeShiftMines()
    @JvmStatic private external fun _nativeIsMine(row: Int, col: Int): Boolean
    @JvmStatic private external fun _nativeIsRevealed(row: Int, col: Int): Boolean
    @JvmStatic private external fun _nativeIsFlagged(row: Int, col: Int): Boolean
    @JvmStatic private external fun _nativeAdjacentMines(row: Int, col: Int): Int
    @JvmStatic private external fun _nativeGetMinesLeft(): Int
    @JvmStatic private external fun _nativeIsGameOver(): Boolean
    @JvmStatic private external fun _nativeIsWon(): Boolean
    @JvmStatic private external fun _nativeGetExplodedRow(): Int
    @JvmStatic private external fun _nativeGetExplodedCol(): Int
    @JvmStatic private external fun _nativeGetShiftsCount(): Int
    @JvmStatic private external fun _nativeGetRows(): Int
    @JvmStatic private external fun _nativeGetCols(): Int
    @JvmStatic private external fun _nativeGetMineCount(): Int
    @JvmStatic private external fun _nativeIsFirstClickDone(): Boolean
}
