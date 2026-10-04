package com.zminesweeper.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max

/**
 * Кастомная вьюшка, рисующая игровое поле сапёра.
 * Поддерживает: короткий тап = копать, долгое нажатие = флажок (настраиваемо),
 * режим «флажок» (если включён — короткий тап ставит флажок).
 *
 * Масштаб: pinch-to-zoom (от 1.0x до 3.0x) + двойной тап переключает 1x ↔ 2x.
 * Сделано для близоруких игроков и крупных полей.
 *
 * Анимации:
 *  - reveal: при открытии клетки — масштаб 0.3 → 1.0 + альфа 0 → 1, ~250 мс
 *  - flood reveal: волна от точки клика — задержка растёт с расстоянием
 *  - flag: при постановке флажка — пульсация масштаба, ~300 мс
 *  - shift: жёлтая вспышка на всех скрытых клетках, ~600 мс
 */
class GameView : View {
    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {
        init()
    }

    private fun init() {
        // no-op; placeholders for future view setup
    }

    var engine: GameEngine? = null
        set(value) {
            field = value
            rebuildAnims()
            invalidate()
        }

    /** Если true — короткий тап ставит флажок, долгий — копает. */
    var flagMode: Boolean = false
        set(value) { field = value; invalidate() }

    /**
     * Если задан — все локальные тапы идут в этот колбэк вместо прямого вызова engine.
     * Аргументы: (row, col, isFlag) — isFlag true если игрок хотел поставить/снять флажок
     * (через long-press или flag mode).
     *
     * Используется мультиплеером, чтобы хост и клиент сами решали, как обрабатывать тап.
     * Если null — обычное поведение (engine.reveal / engine.toggleFlag).
     */
    var externalClickListener: ((row: Int, col: Int, isFlag: Boolean) -> Unit)? = null

    /** Если false — тапы игнорируются (ход другого игрока в мультиплеере). */
    var inputEnabled: Boolean = true

    /**
     * Коэффициент масштабирования поля. 1.0 = «как влезло в экран».
     * Пользователь может пинчить от 1.0 до 3.0 (для близоруких и крупных полей).
     * Двойной тап переключает между 1.0 и 2.0.
     */
    var zoomFactor: Float = 1.0f
        set(value) {
            field = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
            requestLayout()
            invalidate()
        }

    private val scaleDetector: ScaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // Пока пользователь пинчит — просим родительский ScrollView не перехватывать.
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomFactor *= detector.scaleFactor
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
    }).apply {
        // Быстрый (low-latency) режим на новых API.
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                isQuickScaleEnabled = true
            }
        } catch (_: Throwable) {}
    }

    companion object {
        const val MIN_ZOOM = 1.0f
        const val MAX_ZOOM = 3.0f
    }

    var onRevealListener: ((row: Int, col: Int, exploded: Boolean, won: Boolean) -> Unit)? = null
    var onFlagListener: ((row: Int, col: Int) -> Unit)? = null

    private var cellSize: Float = 0f

    // ----- Анимации -----

    private data class CellAnim(
        var revealStartedAt: Long = 0L,   // 0 = нет анимации
        var revealDelay: Long = 0L,
        var flagStartedAt: Long = 0L,
    )
    private val cellAnims = ArrayList<ArrayList<CellAnim>>()
    private var shiftStartedAt: Long = 0L

    private val animHandler = Handler(Looper.getMainLooper())
    @Volatile private var animRunning = false
    private val animRunnable = object : Runnable {
        override fun run() {
            if (hasActiveAnims()) {
                invalidate()
                animHandler.postDelayed(this, 16)  // ~60 FPS
            } else {
                animRunning = false
            }
        }
    }

    private fun startAnimLoop() {
        if (!animRunning) {
            animRunning = true
            animHandler.post(animRunnable)
        }
    }

    private fun hasActiveAnims(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (shiftStartedAt > 0 && now - shiftStartedAt < 700) return true
        for (row in cellAnims) {
            for (cell in row) {
                if (cell.revealStartedAt > 0 && now - cell.revealStartedAt - cell.revealDelay < 300) return true
                if (cell.flagStartedAt > 0 && now - cell.flagStartedAt < 350) return true
            }
        }
        return false
    }

    private fun rebuildAnims() {
        cellAnims.clear()
        val e = engine ?: return
        for (r in 0 until e.rows) {
            val row = ArrayList<CellAnim>()
            for (c in 0 until e.cols) {
                row.add(CellAnim())
            }
            cellAnims.add(row)
        }
    }

    /**
     * Анимировать волну открытия от точки клика.
     * Клетки рядом с (originRow, originCol) появляются первыми, дальние — с задержкой.
     */
    fun animateRevealWave(cells: List<Pair<Int, Int>>, originRow: Int, originCol: Int) {
        if (cells.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        for ((r, c) in cells) {
            if (r !in cellAnims.indices) continue
            if (c !in cellAnims[r].indices) continue
            val dist = max(abs(r - originRow), abs(c - originCol))
            val delay = dist * 22L  // 22 мс на шаг дистанции
            cellAnims[r][c].revealStartedAt = now
            cellAnims[r][c].revealDelay = delay
        }
        startAnimLoop()
    }

    fun animateFlag(r: Int, c: Int) {
        if (r !in cellAnims.indices) return
        if (c !in cellAnims[r].indices) return
        cellAnims[r][c].flagStartedAt = SystemClock.uptimeMillis()
        startAnimLoop()
    }

    fun animateShift() {
        shiftStartedAt = SystemClock.uptimeMillis()
        startAnimLoop()
    }

    private fun revealProgress(r: Int, c: Int): Float {
        if (r !in cellAnims.indices) return 1f
        if (c !in cellAnims[r].indices) return 1f
        val start = cellAnims[r][c].revealStartedAt
        if (start == 0L) return 1f
        val delay = cellAnims[r][c].revealDelay
        val elapsed = SystemClock.uptimeMillis() - start - delay
        if (elapsed < 0) return 0f
        val dur = 220L
        if (elapsed >= dur) {
            cellAnims[r][c].revealStartedAt = 0L
            return 1f
        }
        val t = elapsed.toFloat() / dur
        // Замедление (decelerate): быстрый старт, плавный конец
        return 1f - (1f - t) * (1f - t)
    }

    private fun flagScale(r: Int, c: Int): Float {
        if (r !in cellAnims.indices) return 1f
        if (c !in cellAnims[r].indices) return 1f
        val start = cellAnims[r][c].flagStartedAt
        if (start == 0L) return 1f
        val elapsed = SystemClock.uptimeMillis() - start
        val dur = 300L
        if (elapsed >= dur) {
            cellAnims[r][c].flagStartedAt = 0L
            return 1f
        }
        val t = elapsed.toFloat() / dur
        // Punch: 1 → 1.3 → 1, пик на t=0.4
        val peak = 0.4f
        return if (t < peak) {
            1f + 0.3f * (t / peak)
        } else {
            1f + 0.3f * (1f - (t - peak) / (1f - peak))
        }
    }

    private fun shiftFlashAlpha(): Float {
        if (shiftStartedAt == 0L) return 0f
        val elapsed = SystemClock.uptimeMillis() - shiftStartedAt
        val dur = 700L
        if (elapsed >= dur) {
            shiftStartedAt = 0L
            return 0f
        }
        val t = elapsed.toFloat() / dur
        // Пик на t=0.15, плавный затухание
        return if (t < 0.15f) t / 0.15f else 1f - (t - 0.15f) / 0.85f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animHandler.removeCallbacks(animRunnable)
    }

    // ----- Рисование -----

    private val paintHidden = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E3640")
        style = Paint.Style.FILL
    }
    private val paintHiddenTop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3D4651")
        style = Paint.Style.FILL
    }
    private val paintRevealed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1F26")
        style = Paint.Style.FILL
    }
    private val paintMine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        style = Paint.Style.FILL
    }
    private val paintMineExploded = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.FILL
    }
    private val paintMineNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ECEFF1")
        style = Paint.Style.FILL
    }
    private val paintFlagPole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ECEFF1")
        style = Paint.Style.FILL
        textSize = 1f
    }
    private val paintNumber = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val paintShiftFlash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFC107")
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val handler = Handler(Looper.getMainLooper())

    private var downX = 0f
    private var downY = 0f
    private var downRow = -1
    private var downCol = -1
    private var hasMoved = false
    private var longPressFired = false

    private val longPressRunnable = Runnable {
        if (!hasMoved && downRow >= 0) {
            longPressFired = true
            val engine = this.engine ?: return@Runnable
            // Долгий тап — всегда «противоположное» действие к flag mode:
            //  - если flagMode off → долгий тап ставит флажок
            //  - если flagMode on  → долгий тап копает
            val isFlag = !flagMode
            if (!inputEnabled) return@Runnable
            val ext = externalClickListener
            if (ext != null) {
                ext.invoke(downRow, downCol, isFlag)
            } else {
                if (isFlag) {
                    if (engine.toggleFlag(downRow, downCol)) {
                        animateFlag(downRow, downCol)
                        onFlagListener?.invoke(downRow, downCol)
                        performHaptic()
                    }
                } else {
                    val res = engine.reveal(downRow, downCol)
                    handleRevealResult(downRow, downCol, res)
                }
            }
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val engine = engine
        if (engine == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val availW = MeasureSpec.getSize(widthMeasureSpec)
        val availH = MeasureSpec.getSize(heightMeasureSpec)
        val desiredCell = max(24f, minOf(
            availW.toFloat() / engine.cols,
            availH.toFloat() / engine.rows
        ))
        var baseCell = minOf(
            desiredCell,
            maxOf(availW.toFloat() / engine.cols, availH.toFloat() / engine.rows)
        )
        val maxCell = 80f * resources.displayMetrics.density
        if (baseCell > maxCell) baseCell = maxCell
        val minCell = 18f * resources.displayMetrics.density
        if (baseCell < minCell) baseCell = minCell

        // Применяем масштабирование (pinch-to-zoom).
        // Базовый размер — это «как влезло в экран», zoomFactor > 1 увеличивает клетки,
        // и тогда родительский ScrollView позволяет прокручивать увеличенное поле.
        cellSize = baseCell * zoomFactor

        val w = (cellSize * engine.cols).toInt()
        val h = (cellSize * engine.rows).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val engine = engine ?: return
        val pad = 1f
        val flashAlpha = shiftFlashAlpha()

        for (r in 0 until engine.rows) {
            for (c in 0 until engine.cols) {
                val left = c * cellSize + pad
                val top = r * cellSize + pad
                val right = (c + 1) * cellSize - pad
                val bottom = (r + 1) * cellSize - pad
                rect.set(left, top, right, bottom)

                val isExploded = engine.explodedRow() == r && engine.explodedCol() == c
                val revealProg = revealProgress(r, c)
                val flagScale = flagScale(r, c)

                when {
                    engine.isRevealed(r, c) && engine.isMine(r, c) -> {
                        // Для мины — лёгкая пульсация при первом открытии
                        canvas.save()
                        val cx = rect.centerX()
                        val cy = rect.centerY()
                        val sc = 0.5f + 0.5f * revealProg
                        canvas.scale(sc, sc, cx, cy)
                        val alpha = (255 * revealProg).toInt().coerceIn(0, 255)
                        val bg = if (isExploded) paintMineExploded else paintMine
                        bg.alpha = alpha
                        canvas.drawRoundRect(rect, 4f, 4f, bg)
                        paintMineNormal.alpha = alpha
                        drawMine(canvas, rect, alpha)
                        bg.alpha = 255
                        paintMineNormal.alpha = 255
                        canvas.restore()
                    }
                    engine.isRevealed(r, c) -> {
                        // Анимация открытия: масштаб + альфа
                        canvas.save()
                        val cx = rect.centerX()
                        val cy = rect.centerY()
                        val sc = 0.4f + 0.6f * revealProg
                        canvas.scale(sc, sc, cx, cy)
                        val alpha = (255 * revealProg).toInt().coerceIn(0, 255)
                        paintRevealed.alpha = alpha
                        canvas.drawRoundRect(rect, 4f, 4f, paintRevealed)
                        val n = engine.adjacentMines(r, c)
                        if (n > 0) drawNumber(canvas, n, rect, alpha)
                        paintRevealed.alpha = 255
                        paintNumber.alpha = 255
                        canvas.restore()
                    }
                    engine.isFlagged(r, c) -> {
                        canvas.save()
                        val cx = rect.centerX()
                        val cy = rect.centerY()
                        canvas.scale(flagScale, flagScale, cx, cy)
                        canvas.drawRoundRect(rect, 4f, 4f, paintHidden)
                        rect.set(left, top, right, top + (bottom - top) * 0.4f)
                        canvas.drawRoundRect(rect, 4f, 4f, paintHiddenTop)
                        drawFlag(canvas, RectF(left, top, right, bottom))
                        canvas.restore()
                    }
                    else -> {
                        canvas.drawRoundRect(rect, 4f, 4f, paintHidden)
                        rect.set(left, top, right, top + (bottom - top) * 0.4f)
                        canvas.drawRoundRect(rect, 4f, 4f, paintHiddenTop)
                        // Вспышка при сдвиге мин
                        if (flashAlpha > 0f) {
                            paintShiftFlash.alpha = (flashAlpha * 110).toInt()
                            rect.set(left, top, right, bottom)
                            canvas.drawRoundRect(rect, 4f, 4f, paintShiftFlash)
                        }
                    }
                }
            }
        }
    }

    private fun drawMine(canvas: Canvas, r: RectF, alpha: Int) {
        val cx = r.centerX()
        val cy = r.centerY()
        val radius = (r.right - r.left) * 0.22f
        paintMineNormal.style = Paint.Style.FILL
        paintMineNormal.alpha = alpha
        canvas.drawCircle(cx, cy, radius, paintMineNormal)
        paintMineNormal.strokeWidth = (radius * 0.25f)
        paintMineNormal.style = Paint.Style.STROKE
        val len = radius * 1.5f
        canvas.drawLine(cx - len, cy, cx + len, cy, paintMineNormal)
        canvas.drawLine(cx, cy - len, cx, cy + len, paintMineNormal)
        canvas.drawLine(cx - len * 0.7f, cy - len * 0.7f, cx + len * 0.7f, cy + len * 0.7f, paintMineNormal)
        canvas.drawLine(cx + len * 0.7f, cy - len * 0.7f, cx - len * 0.7f, cy + len * 0.7f, paintMineNormal)
        paintMineNormal.style = Paint.Style.FILL
        paintMineNormal.alpha = 255
    }

    private fun drawFlag(canvas: Canvas, r: RectF) {
        val cx = r.centerX()
        val w = r.right - r.left
        val h = r.bottom - r.top
        paintFlagPole.color = Color.parseColor("#ECEFF1")
        paintFlagPole.strokeWidth = w * 0.06f
        canvas.drawLine(cx - w * 0.05f, r.top + h * 0.18f, cx - w * 0.05f, r.bottom - h * 0.18f, paintFlagPole)
        val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF5252"); style = Paint.Style.FILL }
        val path = android.graphics.Path()
        path.moveTo(cx - w * 0.05f, r.top + h * 0.2f)
        path.lineTo(cx + w * 0.3f, r.top + h * 0.32f)
        path.lineTo(cx - w * 0.05f, r.top + h * 0.45f)
        path.close()
        canvas.drawPath(path, flagPaint)
        flagPaint.color = Color.parseColor("#607D8B")
        canvas.drawRect(cx - w * 0.18f, r.bottom - h * 0.22f, cx + w * 0.08f, r.bottom - h * 0.12f, flagPaint)
    }

    private fun drawNumber(canvas: Canvas, n: Int, r: RectF, alpha: Int) {
        val color = when (n) {
            1 -> Color.parseColor("#42A5F5")
            2 -> Color.parseColor("#66BB6A")
            3 -> Color.parseColor("#EF5350")
            4 -> Color.parseColor("#AB47BC")
            5 -> Color.parseColor("#FF7043")
            6 -> Color.parseColor("#26C6DA")
            7 -> Color.parseColor("#B0BEC5")
            else -> Color.parseColor("#607D8B")
        }
        paintNumber.color = color
        paintNumber.alpha = alpha
        paintNumber.textSize = (r.right - r.left) * 0.6f
        val baseline = r.centerY() - (paintNumber.descent() + paintNumber.ascent()) / 2f
        canvas.drawText(n.toString(), r.centerX(), baseline, paintNumber)
        paintNumber.alpha = 255
    }

    /** Время последнего «UP» — для двойного тапа (toggle zoom). */
    private var lastUpTimeMs: Long = 0L
    private var lastUpX: Float = 0f
    private var lastUpY: Float = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val engine = engine ?: return false

        // Сначала отдаём событие пинч-детектору. Он сам поймёт, 1 палец или 2.
        scaleDetector.onTouchEvent(event)
        // Если пинч в процессе — НЕ обрабатываем как тап/долгое нажатие вообще.
        if (scaleDetector.isInProgress) {
            hasMoved = true
            handler.removeCallbacks(longPressRunnable)
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                downCol = (event.x / cellSize).toInt().coerceIn(0, engine.cols - 1)
                downRow = (event.y / cellSize).toInt().coerceIn(0, engine.rows - 1)
                hasMoved = false
                longPressFired = false
                // Если только что был UP в радиусе 40px и за <300мс — это двойной тап,
                // игнорируем long-press, ждём UP для toggling zoom.
                val now = SystemClock.uptimeMillis()
                val isDoubleClick = (now - lastUpTimeMs) < 300 &&
                    abs(event.x - lastUpX) < 40f * resources.displayMetrics.density &&
                    abs(event.y - lastUpY) < 40f * resources.displayMetrics.density
                if (!isDoubleClick) {
                    handler.postDelayed(longPressRunnable, longPressTimeout)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                    hasMoved = true
                    handler.removeCallbacks(longPressRunnable)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) return true

                val now = SystemClock.uptimeMillis()
                val isDoubleClick = !longPressFired && !hasMoved &&
                    (now - lastUpTimeMs) < 300 &&
                    abs(event.x - lastUpX) < 40f * resources.displayMetrics.density &&
                    abs(event.y - lastUpY) < 40f * resources.displayMetrics.density

                if (isDoubleClick) {
                    // Toggle zoom: 1.0 ↔ 2.0
                    zoomFactor = if (zoomFactor > 1.5f) 1.0f else 2.0f
                    lastUpTimeMs = 0L  // не даём тройной клик дать серию тоглов
                    performHaptic()
                    downRow = -1
                    return true
                }

                // Запоминаем UP для возможного double-tap
                lastUpTimeMs = now
                lastUpX = event.x
                lastUpY = event.y

                if (!hasMoved && !longPressFired && downRow >= 0) {
                    if (!inputEnabled) return true
                    val ext = externalClickListener
                    if (ext != null) {
                        // В external-режиме flag mode означает «короткий тап = флажок»
                        ext.invoke(downRow, downCol, flagMode)
                    } else {
                        if (flagMode) {
                            if (engine.toggleFlag(downRow, downCol)) {
                                animateFlag(downRow, downCol)
                                onFlagListener?.invoke(downRow, downCol)
                            }
                        } else {
                            val res = engine.reveal(downRow, downCol)
                            handleRevealResult(downRow, downCol, res)
                            if (res == GameEngine.RevealResult.REVEALED || res == GameEngine.RevealResult.WON) {
                                performHaptic()
                            }
                        }
                    }
                    invalidate()
                }
                downRow = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleRevealResult(row: Int, col: Int, res: GameEngine.RevealResult) {
        when (res) {
            GameEngine.RevealResult.EXPLODED -> {
                performHaptic(heavy = true)
                onRevealListener?.invoke(row, col, true, false)
            }
            GameEngine.RevealResult.WON -> {
                performHaptic()
                onRevealListener?.invoke(row, col, false, true)
            }
            GameEngine.RevealResult.REVEALED -> onRevealListener?.invoke(row, col, false, false)
            GameEngine.RevealResult.NO_CHANGE -> {}
        }
    }

    private fun performHaptic(heavy: Boolean = false) {
        try {
            val act = context
            if (act is GameActivity) {
                act.haptic(heavy)
            } else if (act is com.zminesweeper.game.mp.MpGameActivity) {
                act.haptic(heavy)
            }
        } catch (_: Exception) {}
    }
}
