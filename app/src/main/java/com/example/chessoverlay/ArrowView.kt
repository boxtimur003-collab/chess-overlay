package com.example.chessoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Доска по верхней и нижней границе (проведён один вертикальный свайп).
 * Если высота ≈ ширине экрана — доска на всю ширину (квадрат по центру свайпа).
 * Иначе — квадрат со стороной = высоте свайпа, по центру экрана.
 */
fun boardFromBand(y1: Float, y2: Float, screenW: Float): RectF {
    val top = minOf(y1, y2)
    val bottom = maxOf(y1, y2)
    val h = bottom - top
    return if (h >= screenW * 0.85f) {
        val t = ((top + bottom) / 2f - screenW / 2f).coerceAtLeast(0f)
        RectF(0f, t, screenW, t + screenW)
    } else {
        val left = (screenW - h) / 2f
        RectF(left, top, left + h, bottom)
    }
}

/** Рисует стрелку лучшего хода поверх доски. flip=true — доска перевёрнута (играем за чёрных). */
class ArrowView(ctx: Context) : View(ctx) {
    var board = RectF()
    var flip = false
    var move: Move? = null
    var showFrame = false

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 0, 200, 83)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 0, 200, 83)
        style = Paint.Style.FILL
    }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 193, 7)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }

    private fun cx(sq: Int): Float {
        val f = sq % 8
        return board.left + ((if (flip) 7 - f else f) + 0.5f) * board.width() / 8f
    }

    private fun cy(sq: Int): Float {
        val r = sq / 8
        return board.top + ((if (flip) 7 - r else r) + 0.5f) * board.height() / 8f
    }

    override fun onDraw(c: Canvas) {
        if (board.width() < 8f || board.height() < 8f) return
        if (showFrame) c.drawRect(board, frame)
        val m = move ?: return
        val x1 = cx(m.from)
        val y1 = cy(m.from)
        val x2 = cx(m.to)
        val y2 = cy(m.to)
        val cell = board.width() / 8f
        line.strokeWidth = cell * 0.16f
        val ang = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val head = cell * 0.4f
        val bx = x2 - (head * 0.8f * cos(ang)).toFloat()
        val by = y2 - (head * 0.8f * sin(ang)).toFloat()
        c.drawCircle(x1, y1, cell * 0.14f, fill)
        c.drawLine(x1, y1, bx, by, line)
        val p = Path()
        p.moveTo(x2, y2)
        val a1 = ang + Math.PI * 5 / 6
        val a2 = ang - Math.PI * 5 / 6
        p.lineTo(x2 + (head * cos(a1)).toFloat(), y2 + (head * sin(a1)).toFloat())
        p.lineTo(x2 + (head * cos(a2)).toFloat(), y2 + (head * sin(a2)).toFloat())
        p.close()
        c.drawPath(p, fill)
    }
}

/**
 * Полноэкранное затемнение. Нужно провести пальцем ТОЛЬКО сверху вниз:
 * от верхней до нижней границы доски. Ширина подгоняется под экран автоматически.
 * Область сохраняется при отпускании пальца. «Отмена» — внизу.
 */
class BoardSelectView(
    ctx: Context,
    private val onDone: (RectF) -> Unit,
    private val onCancel: () -> Unit
) : View(ctx) {
    private var y1 = 0f
    private var y2 = 0f
    private var drawing = false

    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 0, 200, 83)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val inner = Paint().apply { color = Color.argb(50, 0, 200, 83) }
    private val bandLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 193, 7)
        strokeWidth = 5f
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
    }
    private val cancelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 200, 50, 50) }
    private val cancelRect = RectF()

    init {
        setBackgroundColor(Color.argb(150, 0, 0, 0))
    }

    override fun onDraw(c: Canvas) {
        c.drawText("Проведи пальцем сверху вниз:", width / 2f, 140f, txt)
        c.drawText("от верхнего до нижнего края доски", width / 2f, 200f, txt)
        cancelRect.set(width / 2f - 160f, height - 220f, width / 2f + 160f, height - 120f)
        c.drawRoundRect(cancelRect, 30f, 30f, cancelBg)
        c.drawText("Отмена", width / 2f, height - 155f, txt)
        if (drawing) {
            val r = boardFromBand(y1, y2, width.toFloat())
            c.drawRect(r, inner)
            c.drawRect(r, frame)
            c.drawLine(0f, y1, width.toFloat(), y1, bandLine)
            c.drawLine(0f, y2, width.toFloat(), y2, bandLine)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                if (cancelRect.contains(e.x, e.y)) {
                    onCancel()
                    return true
                }
                y1 = e.y
                y2 = e.y
                drawing = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                y2 = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (drawing.not()) return true
                y2 = e.y
                if (abs(y2 - y1) > 150f) onDone(boardFromBand(y1, y2, width.toFloat()))
                else {
                    drawing = false
                    invalidate()
                }
            }
        }
        return true
    }
}
