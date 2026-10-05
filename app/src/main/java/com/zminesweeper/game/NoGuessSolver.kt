package com.zminesweeper.game

/**
 * No-Guess Solver: анализирует текущее состояние поля и определяет,
 * есть ли хотя бы один логический ход на активной границе.
 *
 * Логический ход — это клетка, которую можно безопасно открыть или
 * пометить флагом строго по цифрам (без угадайки).
 *
 * Если логических ходов нет — это софтлок генерации. В этом случае
 * [resolveSoftlock] перетасовывает скрытые мины так, чтобы создать
 * логический ход. Если игрок поставил неверные флаги — перетасовка
 * НЕ делается (это ошибка игрока, не софтлок).
 */
class NoGuessSolver(private val engine: GameEngine) {

    data class Move(val row: Int, val col: Int, val isFlag: Boolean)

    /**
     * Найти любой логический ход на текущей границе поля.
     * Возвращает null, если логических ходов нет.
     */
    fun findLogicalMove(): Move? {
        // Проходим по всем открытым клеткам с цифрой > 0.
        for (r in 0 until engine.rows) {
            for (c in 0 until engine.cols) {
                if (!engine.isRevealed(r, c) || engine.isMine(r, c)) continue
                val n = engine.adjacentMines(r, c)
                if (n == 0) continue

                // Считаем флаги и скрытые без флага вокруг.
                var flagged = 0
                val hidden = ArrayList<Pair<Int, Int>>()
                for (dr in -1..1) for (dc in -1..1) {
                    if (dr == 0 && dc == 0) continue
                    val nr = r + dr; val nc = c + dc
                    if (nr !in 0 until engine.rows || nc !in 0 until engine.cols) continue
                    if (engine.isFlagged(nr, nc)) flagged++
                    else if (!engine.isRevealed(nr, nc)) hidden.add(nr to nc)
                }
                // Правило 1: если флагов == числу, все остальные скрытые безопасны.
                if (flagged == n && hidden.isNotEmpty()) {
                    return Move(hidden.first().first, hidden.first().second, isFlag = false)
                }
                // Правило 2: если флагов + скрытых == числу, все скрытые — мины.
                if (flagged + hidden.size == n && hidden.isNotEmpty()) {
                    return Move(hidden.first().first, hidden.first().second, isFlag = true)
                }
            }
        }
        return null
    }

    /**
     * Проверить, есть ли на поле неверные флаги игрока (флаг не на мине).
     * Если есть — это ошибка игрока, не софтлок генерации.
     */
    fun hasPlayerErrors(): Boolean {
        for (r in 0 until engine.rows) {
            for (c in 0 until engine.cols) {
                if (engine.isFlagged(r, c) && !engine.isMine(r, c)) return true
            }
        }
        return false
    }

    /**
     * Проверить, есть ли хотя бы одна скрытая клетка без флага.
     * Если нет — игрок замуровал всё, хода нет.
     */
    fun hasHiddenUnflagged(): Boolean {
        for (r in 0 until engine.rows) {
            for (c in 0 until engine.cols) {
                if (!engine.isRevealed(r, c) && !engine.isFlagged(r, c)) return true
            }
        }
        return false
    }

    /**
     * Главный метод: определить состояние игры с точки зрения софтлока.
     *
     * @return одно из:
     *  - [SoftlockState.None] — логический ход есть, всё ок.
     *  - [SoftlockState.PlayerError] — у игрока неверные флаги, софтлока генерации нет.
     *  - [SoftlockState.Deadlock] — логических ходов нет, флаги корректны (или их нет).
     *    Нужно перетасовать мины.
     */
    fun analyze(): SoftlockState {
        // Если есть скрытые клетки без флага и есть логический ход — всё ок.
        if (findLogicalMove() != null) return SoftlockState.None
        // Если нет скрытых без флага — игрок замуровал всё. Проверим ошибки.
        if (!hasHiddenUnflagged()) {
            // Все закрытые клетки помечены флагами. Если есть неверные — ошибка игрока.
            return if (hasPlayerErrors()) SoftlockState.PlayerError else SoftlockState.None
        }
        // Логических ходов нет, но скрытые без флага есть. Проверим ошибки игрока.
        if (hasPlayerErrors()) return SoftlockState.PlayerError
        // Чистый софтлок генерации — нужно перетасовать мины.
        return SoftlockState.Deadlock
    }

    sealed class SoftlockState {
        /** Всё ок, логический ход есть. */
        object None : SoftlockState()
        /** У игрока неверные флаги. Не делаем безопасный прорыв. */
        object PlayerError : SoftlockState()
        /** Чистый софтлок генерации. Перетасовываем мины. */
        object Deadlock : SoftlockState()
    }
}
