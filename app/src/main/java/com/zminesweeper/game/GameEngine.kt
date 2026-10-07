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
        val actualRows: Int
        val actualCols: Int
        val actualMines: Int
        if (difficulty == Difficulty.CUSTOM) {
            actualRows = MinesweeperApp.customRows
            actualCols = MinesweeperApp.customCols
            actualMines = calcMineCount(actualRows, actualCols)
        } else {
            actualRows = difficulty.rows
            actualCols = difficulty.cols
            actualMines = difficulty.mineCount
        }
        initGameCustom(mode, actualRows, actualCols, actualMines, difficulty)
    }

    /**
     * Количество мин для кастомного поля. Формула:
     * ~15% для маленьких полей (до 64 клеток), ~13% для больших.
     */
    private fun calcMineCount(rows: Int, cols: Int): Int {
        val total = rows * cols
        val pct = if (total <= 256) 0.15 else 0.13
        return (total * pct).toInt().coerceAtLeast(1)
    }

    /** Прямая инициализация с заданными параметрами (для custom и MP). */
    fun initGameCustom(mode: GameMode, rows: Int, cols: Int, mineCount: Int, difficulty: Difficulty) {
        this.mode = mode
        this.difficulty = difficulty
        this.rows = rows
        this.cols = cols
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

        this.mineCount = if (mode.hasMineLimit) {
            mineCount
        } else {
            val total = rows * cols
            1 + rng.nextInt(total)   // 1..total включительно
        }
        placeMinesRandomly(emptyList())
        enforceNoFullySurroundedMines()
        if (this.mineCount == 0) {
            forcePlaceOneMine()
        }
    }

    /** Принудительно ставит 1 мину в любую доступную закрытую клетку. */
    private fun forcePlaceOneMine() {
        val available = ArrayList<Pair<Int, Int>>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!revealed[r][c] && !mines[r][c]) {
                    available.add(r to c)
                }
            }
        }
        if (available.isNotEmpty()) {
            val (r, c) = available[rng.nextInt(available.size)]
            mines[r][c] = true
            mineCount = 1
        }
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
        // Защита: если target = 0, но в Анархии должны быть мины — берём минимум 1.
        // (target уже >= 1 для Анархии из initGame/shiftMines, но на всякий случай.)
        val effectiveTarget = if (!mode.hasMineLimit) maxOf(1, target) else target
        val actual = minOf(effectiveTarget, available.size)
        for (i in 0 until actual) {
            val (r, c) = available[i]
            mines[r][c] = true
        }
        mineCount = actual
    }


    /**
     * ПРАВИЛО: мина не может быть окружена минами со всех сторон.
     * Хотя бы одна соседняя клетка должна быть безопасной.
     * Вызывается ТОЛЬКО при генерации поля и при сдвиге — НЕ во время игры.
     */
    private fun enforceNoFullySurroundedMines() {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!mines[r][c]) continue
                val neighbors = ArrayList<Pair<Int, Int>>()
                for (dr in -1..1) for (dc in -1..1) {
                    if (dr == 0 && dc == 0) continue
                    val nr = r + dr; val nc = c + dc
                    if (nr in 0 until rows && nc in 0 until cols) {
                        neighbors.add(nr to nc)
                    }
                }
                if (neighbors.isEmpty()) continue
                if (neighbors.all { (nr, nc) -> mines[nr][nc] }) {
                    val (mr, mc) = neighbors[rng.nextInt(neighbors.size)]
                    mines[mr][mc] = false
                    mineCount--
                }
            }
        }
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

        // ПРАВИЛО: мина не может быть окружена минами со всех сторон.
        // Применяется после КАЖДОГО сдвига (включая Лавину, Дрейф, Хаос, Анархию).
        enforceNoFullySurroundedMines()

        // Финальный проход: защищаем «цифры» от обнуления.
        // В Лавине не применяется — клетки всё равно закроются.
        if (!mode.coversAllAfterShift) {
            enforceNumberedInvariant(numberedRevealed)
        }

        // Защита от 0 мин в Анархии: если после всех вычислений mineCount = 0,
        // но есть доступные закрытые клетки — принудительно ставим 1 мину.
        if (mineCount == 0) {
            forcePlaceOneMine()
        }

        // Лавина: после сдвига всё поле закрывается заново.
        // Флажки остаются (они защищают мины от перемещения и клетки от закрытия).
        // Первый клик после покрытия безопасен.
        if (mode.coversAllAfterShift) {
            // ПРОВЕРКА НЕПРАВИЛЬНЫХ ФЛАГОВ: если у игрока есть флаги НЕ на минах,
            // после сдвига это поражение. Логика: Лавина наказывает за ошибки.
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if (flagged[r][c] && !mines[r][c]) {
                        // Найден неверный флаг → поражение.
                        // explodedAt указывает на неверный флаг (для renderer).
                        explodedAt[0] = r; explodedAt[1] = c
                        gameOver = true
                        return  // не закрываем поле, показываем финальное состояние
                    }
                }
            }
            // Все флаги корректны — закрываем поле.
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    revealed[r][c] = false
                }
            }
            firstClickDone = false
            revealedCount = 0
            _lastRevealed.clear()
        }
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
            // Превентивная генерация без софтлоков: перебираем раскладки мин,
            // пока после виртуального открытия клетки NoGuessSolver не найдёт
            // логический ход. Заменяет реактивный reshuffleMinesForLogicalMove.
            ensureNoSoftlockOnFirstClick(row, col)
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
                // После открытия клетки софтлок мог исчезнуть — обновим.
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
        // НО: мины под флажками НЕ ТРОГАЕМ — они заморожены (в Дрейфе и Лавине).
        val taken = ArrayList(safe)
        val occupied = ArrayList<Pair<Int, Int>>()
        for (r in 0 until rows) for (c in 0 until cols) {
            if (mines[r][c] && taken.none { it.first == r && it.second == c }) {
                occupied.add(r to c)
            }
        }
        // Сбросить мины с безопасной зоны, КРОМЕ мин под флажками.
        for ((r, c) in safe) {
            if (!flagged[r][c]) mines[r][c] = false  // флаг = замороженная мина, не трогаем
        }
        // Доступные места — все не занятые, не входящие в safe, не под флагами.
        val available = ArrayList<Pair<Int, Int>>()
        for (r in 0 until rows) for (c in 0 until cols) {
            if (taken.any { it.first == r && it.second == c }) continue
            if (occupied.any { it.first == r && it.second == c }) continue
            if (flagged[r][c]) continue  // не ставим мины под флагами
            available.add(r to c)
        }
        available.shuffle(rng)
        // Считаем сколько мин нужно добавить (вычитая замороженные под флагами в safe).
        var frozenInSafe = 0
        for ((r, c) in safe) if (flagged[r][c] && mines[r][c]) frozenInSafe++
        val needed = mineCount - occupied.size - frozenInSafe
        for (i in 0 until minOf(needed, available.size)) {
            val (r, c) = available[i]
            mines[r][c] = true
        }
    }

    /**
     * Превентивная генерация без софтлоков для первого клика.
     *
     * После [ensureSafeStart] мины расставлены с учётом safe zone, но раскладка
     * может оказаться софтлоком (нет логического хода). Эта функция перебирает
     * разные раскладки мин (сохраняя safe zone) и проверяет NoGuessSolver на
     * виртуально открытом поле (клетка + floodReveal зона).
     *
     * Алгоритм:
     *  1. Сохраняем snapshot массивов mines и revealed.
     *  2. В цикле до [MAX_PREVENTIVE_ATTEMPTS]:
     *     a. Перегенерируем мины вне safe zone (если не первая попытка).
     *     b. Виртуально открываем клетку (row, col) + floodReveal зону.
     *     c. Проверяем NoGuessSolver.findLogicalMove().
     *     d. Откатываем виртуальное открытие.
     *     e. Если есть логический ход — выходим, мины оставляем.
     *  3. Если за N попыток не получилось — оставляем последнюю раскладку
     *     (софтлок, но не критично — reshuffleMinesForLogicalMove в onRevealListener
     *     сделает вторую попытку с сохранением чисел).
     *
     * Важно: функция НЕ должна менять `revealed` или `revealedCount` — она работает
     * с виртуальным открытием, которое откатывается.
     */
    private fun ensureNoSoftlockOnFirstClick(row: Int, col: Int) {
        // Safe zone — клетки, где не должно быть мин (клик + 8 соседей).
        val safeZone = ArrayList<Pair<Int, Int>>()
        safeZone.add(row to col)
        for (dr in -1..1) for (dc in -1..1) {
            val nr = row + dr; val nc = col + dc
            if (nr in 0 until rows && nc in 0 until cols) safeZone.add(nr to nc)
        }

        // Считаем сколько мин должно быть вне safe zone.
        // mineCount — общее число мин. Замороженные (под флагами в safe zone) не трогаем.
        var frozenInSafe = 0
        for ((r, c) in safeZone) if (flagged[r][c] && mines[r][c]) frozenInSafe++
        val targetMinesOutside = mineCount - frozenInSafe

        // Кандидаты для размещения мин: не в safe zone, не открытые, не под флагами.
        val candidates = ArrayList<Pair<Int, Int>>()
        for (r in 0 until rows) for (c in 0 until cols) {
            if (safeZone.any { it.first == r && it.second == c }) continue
            if (revealed[r][c]) continue
            if (flagged[r][c]) continue
            candidates.add(r to c)
        }
        if (candidates.isEmpty() || targetMinesOutside <= 0) return

        // Сохраняем snapshot revealed для отката виртуального открытия.
        val revealedSnapshot = Array(rows) { r -> revealed[r].copyOf() }
        val savedRevealedCount = revealedCount

        // Сохраняем snapshot mines для отката перегенерации.
        val minesSnapshot = Array(rows) { r -> mines[r].copyOf() }

        var bestAttempt = -1  // индекс попытки с логическим ходом, -1 = не найдено

        for (attempt in 0 until MAX_PREVENTIVE_ATTEMPTS) {
            // Перегенерируем мины вне safe zone (на первой попытке оставляем как есть —
            // ensureSafeStart уже расставил, проверим эту раскладку).
            if (attempt > 0) {
                // Сбрасываем мины вне safe zone.
                for ((r, c) in candidates) mines[r][c] = false
                // Случайно расставляем targetMinesOutside мин.
                candidates.shuffle(rng)
                for (i in 0 until minOf(targetMinesOutside, candidates.size)) {
                    val (r, c) = candidates[i]
                    mines[r][c] = true
                }
            }

            // Виртуально открываем клетку (row, col).
            revealed[row][col] = true
            revealedCount++
            // Если 0 мин вокруг — виртуальный floodReveal.
            if (adjacentMines(row, col) == 0) {
                virtualFloodReveal(row, col)
            }

            // Проверяем NoGuessSolver.
            val hasLogicalMove = NoGuessSolver(this).findLogicalMove() != null

            // Откатываем виртуальное открытие.
            for (r in 0 until rows) {
                revealed[r] = revealedSnapshot[r].copyOf()
            }
            revealedCount = savedRevealedCount

            if (hasLogicalMove) {
                bestAttempt = attempt
                break  // успех — мины оставляем как есть
            }
            // Иначе — пробуем другую раскладку.
        }

        // Если ни одна попытка не удалась — восстанавливаем исходные мины.
        // Это лучше, чем оставлять случайную раскладку: поле останется корректным.
        if (bestAttempt == -1) {
            for (r in 0 until rows) {
                mines[r] = minesSnapshot[r].copyOf()
            }
        }
    }

    /**
     * Виртуальный floodReveal для симуляции открытия клетки с 0 мин вокруг.
     * Используется в [ensureNoSoftlockOnFirstClick]. Помечает клетки как открытые
     * в массиве `revealed` (откатывается вызывающим кодом через snapshot).
     *
     * В отличие от [floodReveal], НЕ обновляет `_lastRevealed` — это симуляция.
     */
    private fun virtualFloodReveal(row: Int, col: Int) {
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
            }
            if (adjacentMines(r, c) == 0) {
                for (dr in -1..1) for (dc in -1..1) {
                    if (dr == 0 && dc == 0) continue
                    val nr = r + dr; val nc = c + dc
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

    /** Переключить флаг. Возвращает true если флаг изменён, false если нельзя. */
    fun toggleFlag(row: Int, col: Int): Boolean {
        if (gameOver || won) return false
        if (row !in 0 until rows || col !in 0 until cols) return false
        if (revealed[row][col]) return false
        flagged[row][col] = !flagged[row][col]
        flaggedCount += if (flagged[row][col]) 1 else -1
        // В Лавине победа — когда все мины флагнуты. Проверяем после каждого флага.
        if (flagged[row][col] && mode.coversAllAfterShift && checkWin()) {
            won = true
            gameOver = true
        }
        // Проверяем софтлок: все закрытые клетки помечены флагами, но победы нет.
        return true
    }

    private fun checkWin(): Boolean {
        if (mode.coversAllAfterShift) {
            // Лавина: победа, когда ВСЕ мины помечены флажками И нет неверных флагов.
            // Если игрок замуровал всё флагами, но часть флагов НЕ на минах — НЕ победа.
            for (r in 0 until rows) for (c in 0 until cols) {
                if (mines[r][c] && !flagged[r][c]) return false  // есть мина без флага
                if (!mines[r][c] && flagged[r][c]) return false  // есть флаг не на мине
            }
            return true
        }
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

    /**
     * No-Guess Solver: проверить состояние поля на софтлок.
     * Возвращает состояние: None / PlayerError / Deadlock.
     */
    fun analyzeSoftlock(): NoGuessSolver.SoftlockState {
        return NoGuessSolver(this).analyze()
    }

    /**
     * Перетасовать скрытые мины (не под флагами, не открытые) так, чтобы
     * создать хотя бы один логический ход на границе.
     *
     * Алгоритм:
     *  1. Собрать все скрытые клетки без флага (кандидаты для перемещения мин).
     *  2. Убрать все мины из этих клеток.
     *  3. Пытаться случайно расставить мины обратно так, чтобы NoGuessSolver
     *     нашёл логический ход. Максимум 50 попыток.
     *
     * Если не получилось за 50 попыток — оставить как есть (крайне редкий случай).
     *
     * ВНИМАНИЕ: вызывается только если analyzeSoftlock() == Deadlock.
     * При PlayerError перетасовка НЕ делается.
     *
     * ВАЖНО (v1.2.3): перетасовка сохраняет числа открытых клеток.
     * Если за [MAX_RESHUFFLE_ATTEMPTS] попыток не удалось найти раскладку,
     * которая (а) сохраняет все числа открытых клеток и (б) даёт логический
     * ход — мины возвращаются в исходные позиции. Это лучше, чем сломать
     * цифры (софтлок остаётся, но поле не «дёргается»).
     */
    fun reshuffleMinesForLogicalMove() {
        if (gameOver || won) return

        // 1. Собираем скрытые клетки без флага (кандидаты для перемещения).
        val candidates = ArrayList<Pair<Int, Int>>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!revealed[r][c] && !flagged[r][c]) candidates.add(r to c)
            }
        }
        if (candidates.isEmpty()) return

        // 2. Сохраняем SNAPSHOT чисел всех открытых клеток (до любых изменений).
        //    После перетасовки каждая открытая клетка должна иметь то же число.
        val originalNumbers = HashMap<Pair<Int, Int>, Int>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (revealed[r][c]) {
                    originalNumbers[r to c] = adjacentMines(r, c)
                }
            }
        }

        // 3. Сохраняем исходные позиции мин на кандидатах (для отката).
        val originalMinePositions = ArrayList<Pair<Int, Int>>()
        for ((r, c) in candidates) {
            if (mines[r][c]) originalMinePositions.add(r to c)
        }
        val currentMines = originalMinePositions.size

        // 4. Снимаем все мины с кандидатов.
        for ((r, c) in candidates) mines[r][c] = false

        // 5. Пытаемся расставить currentMines мин случайно, проверяя:
        //    (а) числа открытых клеток совпадают с snapshot
        //    (б) есть логический ход
        for (attempt in 0 until MAX_RESHUFFLE_ATTEMPTS) {
            candidates.shuffle(rng)
            for (i in 0 until currentMines) {
                val (r, c) = candidates[i]
                mines[r][c] = true
            }

            if (numbersMatchSnapshot(originalNumbers) &&
                NoGuessSolver(this).findLogicalMove() != null) {
                return  // успех — мины оставляем как есть
            }

            // Сбрасываем мины для следующей попытки.
            for ((r, c) in candidates) mines[r][c] = false
        }

        // 6. Не получилось за 50 попыток — ОТКАТ к исходным позициям.
        //    Софтлок остаётся, но числа не ломаются.
        for ((r, c) in originalMinePositions) {
            mines[r][c] = true
        }
    }

    /**
     * Проверяет, что текущие числа открытых клеток совпадают с [snapshot].
     * Используется в [reshuffleMinesForLogicalMove] для гарантии, что
     * перетасовка не меняет видимые игроку цифры.
     */
    private fun numbersMatchSnapshot(snapshot: Map<Pair<Int, Int>, Int>): Boolean {
        for ((key, expected) in snapshot) {
            val (r, c) = key
            if (adjacentMines(r, c) != expected) return false
        }
        return true
    }

    enum class RevealResult {
        NO_CHANGE, REVEALED, EXPLODED, WON
    }

    // ---- Сериализация для сохранений ----

    fun serialize(): String {
        // Пересчитываем фактическое число мин.
        var actualMines = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (mines[r][c]) actualMines++
            }
        }
        mineCount = actualMines

        android.util.Log.d("MinesweeperSave", "serialize: ${rows}x${cols}, mines=$actualMines, revealed=$revealedCount, flagged=$flaggedCount, firstClick=$firstClickDone, mode=${mode.key}")

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
        val result = sb.toString()
        android.util.Log.d("MinesweeperSave", "serialize: result length=${result.length}, first 100 chars: ${result.take(100)}")
        return result
    }

    companion object {
        private const val MAX_RESHUFFLE_ATTEMPTS = 50
        private const val MAX_PREVENTIVE_ATTEMPTS = 30

        fun deserialize(data: String): GameEngine? {
            return try {
                val lines = data.split('\n')
                android.util.Log.d("MinesweeperSave", "deserialize: ${lines.size} lines, mode=${lines[0]}, diff=${lines[1]}")
                if (lines.size < 5) {
                    android.util.Log.e("MinesweeperSave", "deserialize: too few lines (${lines.size})")
                    return null
                }
                val mode = GameMode.fromKey(lines[0])
                val diff = Difficulty.fromKey(lines[1])
                val (r, c) = lines[2].split(',').let { it[0].toInt() to it[1].toInt() }
                if (r <= 0 || c <= 0 || r > 100 || c > 100) {
                    android.util.Log.e("MinesweeperSave", "deserialize: bad dimensions $r x $c")
                    return null
                }
                val meta = lines[3].split(',')
                if (meta.size < 6) {
                    android.util.Log.e("MinesweeperSave", "deserialize: meta too short (${meta.size})")
                    return null
                }
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
                var actualMineCount = 0
                for (rr in 0 until r) {
                    engine.mines.add(BooleanArray(c))
                    engine.revealed.add(BooleanArray(c))
                    engine.flagged.add(BooleanArray(c))
                    val line = lines[4 + rr]
                    if (line.length < c) {
                        android.util.Log.e("MinesweeperSave", "deserialize: line $rr too short (${line.length} < $c)")
                        return null
                    }
                    for (cc in 0 until c) {
                        val v = line[cc].digitToInt()
                        engine.mines[rr][cc] = (v shr 2) and 1 == 1
                        engine.revealed[rr][cc] = (v shr 1) and 1 == 1
                        engine.flagged[rr][cc] = v and 1 == 1
                        if (engine.mines[rr][cc]) actualMineCount++
                        if (engine.revealed[rr][cc]) engine.revealedCount++
                    }
                }
                // Если mineCount не совпадает — ИСПРАВЛЯЕМ, а не сбрасываем.
                if (actualMineCount != engine.mineCount) {
                    android.util.Log.w("MinesweeperSave", "deserialize: mineCount mismatch (saved=${engine.mineCount}, actual=$actualMineCount) — fixing")
                    engine.mineCount = actualMineCount
                }
                android.util.Log.d("MinesweeperSave", "deserialize: OK, ${r}x${c}, mines=$actualMineCount, revealed=${engine.revealedCount}")
                engine
            } catch (e: Exception) {
                android.util.Log.e("MinesweeperSave", "deserialize: exception", e)
                null
            }
        }
    }
}
