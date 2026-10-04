package com.zminesweeper.game

import java.util.Random

/**
 * Игровая логика сапёра. Поддерживает 4 режима и 3 уровня сложности.
 *
 * Ключевые правила:
 *  - В ЛЮБОМ режиме первый клик всегда безопасен (мины убираются из клетки и её 8 соседей).
 *  - В ЛЮБОМ режиме мина не может появиться в уже открытой игроком клетке.
 *  - В режимах со сдвигом DRIFT/CHAOS/ANARCHY мины перемещаются каждые N секунд.
 *  - DRIFT: сохраняет флажки и мины под флажком (мины под флажком остаются на месте).
 *  - CHAOS: сбрасывает флажки, перемещает все мины.
 *  - ANARCHY: то же, что CHAOS, плюс случайное число мин каждый сдвиг.
 *  - Цифры на открытых клетках пересчитываются после каждого сдвига.
 *  - ПРАВИЛО ДРЕЙФА (v1.2): любая открытая клетка, на которой была цифра (>0 мин вокруг),
 *    после сдвига остаётся «цифрой» — пустоты (0) на месте бывших цифр быть не должно.
 *    Если случайное переразмещение обнуляет такую клетку — рядом с ней принудительно
 *    кладётся одна мина в любого доступного соседа.
 */
class GameEngine {

    var rows: Int = 0
        private set
    var cols: Int = 0
        private set
    var mode: GameMode = GameMode.CLASSIC
        private set
    var difficulty: Difficulty = Difficulty.BEGINNER
        private set

    /** Текущее количество мин на поле. В ANARCHY меняется каждый сдвиг. */
    var mineCount: Int = 0
        private set

    /** Флаги, расставленные игроком. */
    var flaggedCount: Int = 0
        private set

    var gameOver: Boolean = false
        private set
    var won: Boolean = false
        private set

    /** Первый клик ещё не сделан — мины будут размещены после него (безопасный старт). */
    var firstClickDone: Boolean = false
        private set

    /** Сколько сдвигов уже произошло (для статистики и UI). */
    var shiftsCount: Int = 0
        private set

    /** Была ли хотя бы одна открытая клетка до текущего сдвига. */
    var revealedCount: Int = 0
        private set

    private val mines = ArrayList<BooleanArray>()   // [row][col]
    private val revealed = ArrayList<BooleanArray>()
    private val flagged = ArrayList<BooleanArray>()
    private val explodedAt: IntArray = intArrayOf(-1, -1)  // куда наступил игрок (если поражение)

    /** Клетки, открытые последним вызовом reveal() — для анимации. */
    private val _lastRevealed = ArrayList<Pair<Int, Int>>()
    val lastRevealed: List<Pair<Int, Int>> get() = _lastRevealed

    private val rng = Random()

    /** Полная (пере)инициализация партии. */
    fun initGame(mode: GameMode, difficulty: Difficulty) {
        this.mode = mode
        this.difficulty = difficulty
        this.rows = difficulty.rows
        this.cols = difficulty.cols
        this.firstClickDone = false
        this.gameOver = false
        this.won = false
        this.shiftsCount = 0
        this.flaggedCount = 0
        this.revealedCount = 0
        explodedAt[0] = -1; explodedAt[1] = -1
        _lastRevealed.clear()

        mines.clear(); revealed.clear(); flagged.clear()
        for (r in 0 until rows) {
            mines.add(BooleanArray(cols))
            revealed.add(BooleanArray(cols))
            flagged.add(BooleanArray(cols))
        }

        // В классике и других режимах с лимитом — ставим стандартное число мин.
        // В Анархии — случайное число (1..total).
        mineCount = if (mode.hasMineLimit) {
            difficulty.mineCount
        } else {
            val total = rows * cols
            1 + rng.nextInt(total)   // 1..total включительно
        }
        placeMinesRandomly(emptyList())
    }

    /**
     * Размещает [count] мин в случайных местах, исключая клетки из [exclude].
     * Если count==null, используется текущий mineCount.
     * Мины никогда не размещаются в уже открытых клетках и в [exclude].
     */
    private fun placeMinesRandomly(exclude: List<Pair<Int, Int>>, count: Int? = null) {
        val target = count ?: mineCount
        // Очищаем все мины (но не флаги — флаги обрабатываются вызывающей стороной).
        for (r in 0 until rows) {
            java.util.Arrays.fill(mines[r], false)
        }
        // Список доступных клеток: не открытая, не в exclude.
        val available = ArrayList<Pair<Int, Int>>(rows * cols)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (revealed[r][c]) continue
                if (exclude.any { it.first == r && it.second == c }) continue
                available.add(r to c)
            }
        }
        available.shuffle(rng)
        val actual = minOf(target, available.size)
        for (i in 0 until actual) {
            val (r, c) = available[i]
            mines[r][c] = true
        }
        mineCount = actual
    }

    /** Сдвиг мин в соответствии с режимом. Вызывается по таймеру. */
    fun shiftMines() {
        if (gameOver || !mode.shifts) return

        // --- ПРАВИЛО ДРЕЙФА (v1.2) ---
        // Любая ОТКРЫТАЯ клетка, на которой сейчас есть цифра (>0 мин вокруг),
        // после сдвига должна остаться «цифрой» — то есть adjMines по-прежнему >0.
        // Пустоты (0) на месте бывших цифр быть не должно.
        val numberedRevealed = ArrayList<Pair<Int, Int>>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (revealed[r][c] && !mines[r][c] && adjacentMines(r, c) > 0) {
                    numberedRevealed.add(r to c)
                }
            }
        }

        // Список «замороженных» мин (под флажком) — для DRIFT.
        val frozen = ArrayList<Pair<Int, Int>>()
        if (mode.preservesFlags) {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if (flagged[r][c] && mines[r][c]) {
                        frozen.add(r to c)
                    }
                }
            }
        } else {
            // CHAOS и ANARCHY: сбрасываем все флаги.
            for (r in 0 until rows) java.util.Arrays.fill(flagged[r], false)
            flaggedCount = 0
        }

        // В Анархии — случайное число мин каждый сдвиг.
        val newCount = if (!mode.hasMineLimit) {
            val total = rows * cols
            1 + rng.nextInt(total)
        } else {
            mineCount
        }

        // Переразмещаем мины (кроме замороженных) в доступные клетки.
        for (r in 0 until rows) java.util.Arrays.fill(mines[r], false)
        for ((r, c) in frozen) mines[r][c] = true

        val available = ArrayList<Pair<Int, Int>>(rows * cols)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (mines[r][c]) continue               // замороженная
                if (revealed[r][c]) continue             // открытая
                available.add(r to c)
            }
        }
        available.shuffle(rng)

        val remaining = maxOf(0, newCount - frozen.size)
        val actual = minOf(remaining, available.size)
        for (i in 0 until actual) {
            val (r, c) = available[i]
            mines[r][c] = true
        }
        mineCount = frozen.size + actual
        shiftsCount++

        // Финальный проход: защищаем «цифры» от обнуления.
        enforceNumberedInvariant(numberedRevealed)
    }

    /**
     * Гарантирует, что каждая клетка из [guarded] (открытая, ранее имевшая >0 мин вокруг)
     * по-прежнему имеет >0 мин в окрестности. Если сдвиг обнулил её — кладём одну
     * мину в любого доступного (закрытого, без флага, не заминированного) соседа.
     */
    private fun enforceNumberedInvariant(guarded: List<Pair<Int, Int>>) {
        for ((r, c) in guarded) {
            if (adjacentMines(r, c) > 0) continue
            val candidates = ArrayList<Pair<Int, Int>>(8)
            for (dr in -1..1) for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = r + dr
                val nc = c + dc
                if (nr in 0 until rows && nc in 0 until cols) {
                    if (!revealed[nr][nc] && !flagged[nr][nc] && !mines[nr][nc]) {
                        candidates.add(nr to nc)
                    }
                }
            }
            if (candidates.isNotEmpty()) {
                val (mr, mc) = candidates[rng.nextInt(candidates.size)]
                mines[mr][mc] = true
                mineCount++
            }
        }
    }

    /** Количество мин вокруг клетки (8 соседей). */
    fun adjacentMines(row: Int, col: Int): Int {
        var count = 0
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val nr = row + dr
            val nc = col + dc
            if (nr in 0 until rows && nc in 0 until cols && mines[nr][nc]) count++
        }
        return count
    }

    /** Открыть клетку. Возвращает результат действия. */
    fun reveal(row: Int, col: Int): RevealResult {
        if (gameOver || won) return RevealResult.NO_CHANGE
        if (row !in 0 until rows || col !in 0 until cols) return RevealResult.NO_CHANGE
        if (revealed[row][col] || flagged[row][col]) return RevealResult.NO_CHANGE

        // Безопасный первый клик: убираем мины из клетки и её соседей.
        if (!firstClickDone) {
            firstClickDone = true
            ensureSafeStart(row, col)
        }

        _lastRevealed.clear()
        revealed[row][col] = true
        revealedCount++
        _lastRevealed.add(row to col)

        return if (mines[row][col]) {
            explodedAt[0] = row; explodedAt[1] = col
            gameOver = true
            RevealResult.EXPLODED
        } else {
            // Авто-раскрытие соседей, если вокруг 0 мин.
            if (adjacentMines(row, col) == 0) {
                floodReveal(row, col)
            }
            if (checkWin()) {
                won = true
                gameOver = true
                RevealResult.WON
            } else {
                RevealResult.REVEALED
            }
        }
    }

    private fun ensureSafeStart(row: Int, col: Int) {
        val safe = ArrayList<Pair<Int, Int>>()
        safe.add(row to col)
        for (dr in -1..1) for (dc in -1..1) {
            val nr = row + dr
            val nc = col + dc
            if (nr in 0 until rows && nc in 0 until cols) safe.add(nr to nc)
        }
        // Переставить мины с этих клеток куда-то ещё.
        val taken = ArrayList(safe)
        val occupied = ArrayList<Pair<Int, Int>>()
        for (r in 0 until rows) for (c in 0 until cols) {
            if (mines[r][c] && taken.none { it.first == r && it.second == c }) {
                occupied.add(r to c)
            }
        }
        // Сбросить мины с безопасной зоны.
        for ((r, c) in safe) mines[r][c] = false
        // Доступные места — все не занятые, не входящие в safe.
        val available = ArrayList<Pair<Int, Int>>()
        for (r in 0 until rows) for (c in 0 until cols) {
            if (taken.any { it.first == r && it.second == c }) continue
            if (occupied.any { it.first == r && it.second == c }) continue
            available.add(r to c)
        }
        available.shuffle(rng)
        val needed = mineCount - occupied.size
        for (i in 0 until minOf(needed, available.size)) {
            val (r, c) = available[i]
            mines[r][c] = true
        }
    }

    private fun floodReveal(row: Int, col: Int) {
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.addLast(row to col)
        val visited = HashSet<Pair<Int, Int>>()
        visited.add(row to col)
        while (queue.isNotEmpty()) {
            val (r, c) = queue.removeFirst()
            if (mines[r][c]) continue
            if (!revealed[r][c]) {
                revealed[r][c] = true
                revealedCount++
                _lastRevealed.add(r to c)
            }
            if (adjacentMines(r, c) == 0) {
                for (dr in -1..1) for (dc in -1..1) {
                    if (dr == 0 && dc == 0) continue
                    val nr = r + dr
                    val nc = c + dc
                    if (nr in 0 until rows && nc in 0 until cols) {
                        val key = nr to nc
                        if (!visited.contains(key) && !revealed[nr][nc] && !flagged[nr][nc] && !mines[nr][nc]) {
                            visited.add(key)
                            queue.addLast(key)
                        }
                    }
                }
            }
        }
    }

    /** Переключить флаг. */
    fun toggleFlag(row: Int, col: Int): Boolean {
        if (gameOver || won) return false
        if (row !in 0 until rows || col !in 0 until cols) return false
        if (revealed[row][col]) return false
        flagged[row][col] = !flagged[row][col]
        flaggedCount += if (flagged[row][col]) 1 else -1
        return true
    }

    private fun checkWin(): Boolean {
        // Победа, если все НЕ-минные клетки открыты.
        for (r in 0 until rows) for (c in 0 until cols) {
            if (!mines[r][c] && !revealed[r][c]) return false
        }
        return true
    }

    fun isMine(row: Int, col: Int): Boolean = mines[row][col]
    fun isRevealed(row: Int, col: Int): Boolean = revealed[row][col]
    fun isFlagged(row: Int, col: Int): Boolean = flagged[row][col]
    fun explodedRow(): Int = explodedAt[0]
    fun explodedCol(): Int = explodedAt[1]

    /** Сколько мин ещё не помечено флажком (для счётчика в UI). */
    fun minesLeft(): Int = mineCount - flaggedCount

    enum class RevealResult {
        NO_CHANGE, REVEALED, EXPLODED, WON
    }

    // ---- Сериализация для сохранений ----

    fun serialize(): String {
        val sb = StringBuilder()
        sb.append(mode.key).append('\n')
        sb.append(difficulty.key).append('\n')
        sb.append(rows).append(',').append(cols).append('\n')
        sb.append(mineCount).append(',').append(flaggedCount).append(',')
        sb.append(if (gameOver) 1 else 0).append(',').append(if (won) 1 else 0).append(',')
        sb.append(if (firstClickDone) 1 else 0).append(',').append(shiftsCount).append('\n')
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val v = (if (mines[r][c]) 1 else 0) shl 2 or
                        (if (revealed[r][c]) 1 else 0) shl 1 or
                        (if (flagged[r][c]) 1 else 0)
                sb.append(v)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    companion object {
        fun deserialize(data: String): GameEngine? {
            return try {
                val lines = data.split('\n')
                val mode = GameMode.fromKey(lines[0])
                val diff = Difficulty.fromKey(lines[1])
                val (r, c) = lines[2].split(',').let { it[0].toInt() to it[1].toInt() }
                val meta = lines[3].split(',')
                val engine = GameEngine()
                engine.mode = mode
                engine.difficulty = diff
                engine.rows = r
                engine.cols = c
                engine.mineCount = meta[0].toInt()
                engine.flaggedCount = meta[1].toInt()
                engine.gameOver = meta[2].toInt() == 1
                engine.won = meta[3].toInt() == 1
                engine.firstClickDone = meta[4].toInt() == 1
                engine.shiftsCount = meta[5].toInt()
                engine.mines.clear(); engine.revealed.clear(); engine.flagged.clear()
                for (rr in 0 until r) {
                    engine.mines.add(BooleanArray(c))
                    engine.revealed.add(BooleanArray(c))
                    engine.flagged.add(BooleanArray(c))
                    val line = lines[4 + rr]
                    for (cc in 0 until c) {
                        val v = line[cc].digitToInt()
                        engine.mines[rr][cc] = (v shr 2) and 1 == 1
                        engine.revealed[rr][cc] = (v shr 1) and 1 == 1
                        engine.flagged[rr][cc] = v and 1 == 1
                        if (engine.revealed[rr][cc]) engine.revealedCount++
                    }
                }
                engine
            } catch (e: Exception) {
                null
            }
        }
    }
}
