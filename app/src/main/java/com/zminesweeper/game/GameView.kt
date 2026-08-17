package com.zminesweeper.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max

/**
 * Кастомная вьюшка, рисующая игровое поле сапёра.
 * Поддерживает: короткий тап = копать, долгое нажатие = флажок (настраиваемо),
 * режим «флажок» (если включён — короткий тап ставит флажок).
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
            invalidate()
        }

    /** Если true — короткий тап ставит флажок, долгий — копает. */
    var flagMode: Boolean = false
        set(value) { field = value; invalidate() }

    var onRevealListener: ((row: Int, col: Int, exploded: Boolean, won: Boolean) -> Unit)? = null
    var onFlagListener: ((row: Int, col: Int) -> Unit)? = null

    private var cellSize: Float = 0f

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
    private val paintFlagBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B71C1C")
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
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0D10")
        style = Paint.Style.STROKE
        strokeWidth = 1f
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
            // В обычном режиме долгое нажатие = флажок, в режиме флажка = копать.
            val engine = this.engine ?: return@Runnable
            if (flagMode) {
                val res = engine.reveal(downRow, downCol)
                handleRevealResult(downRow, downCol, res)
            } else {
                if (engine.toggleFlag(downRow, downCol)) {
                    onFlagListener?.invoke(downRow, downCol)
                    performHaptic()
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
        cellSize = minOf(
            desiredCell,
            // верхний предел, чтобы мегаполе не занимало весь экран
            maxOf(availW.toFloat() / engine.cols, availH.toFloat() / engine.rows)
        )
        // Не больше 80dp
        val maxCell = 80f * resources.displayMetrics.density
        if (cellSize > maxCell) cellSize = maxCell
        // Но не меньше 18dp
        val minCell = 18f * resources.displayMetrics.density
        if (cellSize < minCell) cellSize = minCell

        val w = (cellSize * engine.cols).toInt()
        val h = (cellSize * engine.rows).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val engine = engine ?: return
        val pad = 1f
        for (r in 0 until engine.rows) {
            for (c in 0 until engine.cols) {
                val left = c * cellSize + pad
                val top = r * cellSize + pad
                val right = (c + 1) * cellSize - pad
                val bottom = (r + 1) * cellSize - pad
                rect.set(left, top, right, bottom)

                val isExploded = engine.explodedRow() == r && engine.explodedCol() == c
                when {
                    engine.isRevealed(r, c) && engine.isMine(r, c) -> {
                        canvas.drawRoundRect(rect, 4f, 4f, if (isExploded) paintMineExploded else paintMine)
                        drawMine(canvas, rect)
                    }
                    engine.isRevealed(r, c) -> {
                        canvas.drawRoundRect(rect, 4f, 4f, paintRevealed)
                        val n = engine.adjacentMines(r, c)
                        if (n > 0) drawNumber(canvas, n, rect)
                    }
                    engine.isFlagged(r, c) -> {
                        canvas.drawRoundRect(rect, 4f, 4f, paintHidden)
                        // Тонкая верхняя «полочка»
                        rect.set(left, top, right, top + (bottom - top) * 0.4f)
                        canvas.drawRoundRect(rect, 4f, 4f, paintHiddenTop)
                        drawFlag(canvas, RectF(left, top, right, bottom))
                    }
                    else -> {
                        canvas.drawRoundRect(rect, 4f, 4f, paintHidden)
                        rect.set(left, top, right, top + (bottom - top) * 0.4f)
                        canvas.drawRoundRect(rect, 4f, 4f, paintHiddenTop)
                    }
                }
            }
        }
    }

    private fun drawMine(canvas: Canvas, r: RectF) {
        val cx = r.centerX()
        val cy = r.centerY()
        val radius = (r.right - r.left) * 0.22f
        paintMineNormal.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius, paintMineNormal)
        // лучи
        paintMineNormal.strokeWidth = (radius * 0.25f)
        paintMineNormal.style = Paint.Style.STROKE
        val len = radius * 1.5f
        canvas.drawLine(cx - len, cy, cx + len, cy, paintMineNormal)
        canvas.drawLine(cx, cy - len, cx, cy + len, paintMineNormal)
        canvas.drawLine(cx - len * 0.7f, cy - len * 0.7f, cx + len * 0.7f, cy + len * 0.7f, paintMineNormal)
        canvas.drawLine(cx + len * 0.7f, cy - len * 0.7f, cx - len * 0.7f, cy + len * 0.7f, paintMineNormal)
    }

    private fun drawFlag(canvas: Canvas, r: RectF) {
        val cx = r.centerX()
        val cy = r.centerY()
        val w = r.right - r.left
        val h = r.bottom - r.top
        // Флагшток
        paintFlagPole.color = Color.parseColor("#ECEFF1")
        paintFlagPole.strokeWidth = w * 0.06f
        canvas.drawLine(cx - w * 0.05f, r.top + h * 0.18f, cx - w * 0.05f, r.bottom - h * 0.18f, paintFlagPole)
        // Треугольник-флаг (красный)
        val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF5252"); style = Paint.Style.FILL }
        val path = android.graphics.Path()
        path.moveTo(cx - w * 0.05f, r.top + h * 0.2f)
        path.lineTo(cx + w * 0.3f, r.top + h * 0.32f)
        path.lineTo(cx - w * 0.05f, r.top + h * 0.45f)
        path.close()
        canvas.drawPath(path, flagPaint)
        // Основание
        flagPaint.color = Color.parseColor("#607D8B")
        canvas.drawRect(cx - w * 0.18f, r.bottom - h * 0.22f, cx + w * 0.08f, r.bottom - h * 0.12f, flagPaint)
    }

    private fun drawNumber(canvas: Canvas, n: Int, r: RectF) {
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
        paintNumber.textSize = (r.right - r.left) * 0.6f
        val baseline = r.centerY() - (paintNumber.descent() + paintNumber.ascent()) / 2f
        canvas.drawText(n.toString(), r.centerX(), baseline, paintNumber)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val engine = engine ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                downCol = (event.x / cellSize).toInt().coerceIn(0, engine.cols - 1)
                downRow = (event.y / cellSize).toInt().coerceIn(0, engine.rows - 1)
                hasMoved = false
                longPressFired = false
                handler.postDelayed(longPressRunnable, longPressTimeout)
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
                if (!hasMoved && !longPressFired && downRow >= 0) {
                    // Короткий тап.
                    if (flagMode) {
                        if (engine.toggleFlag(downRow, downCol)) {
                            onFlagListener?.invoke(downRow, downCol)
                        }
                    } else {
                        val res = engine.reveal(downRow, downCol)
                        handleRevealResult(downRow, downCol, res)
                        if (res == GameEngine.RevealResult.REVEALED || res == GameEngine.RevealResult.WON) {
                            performHaptic()
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
            (context as? GameActivity)?.haptic(heavy)
        } catch (_: Exception) {}
    }
}
