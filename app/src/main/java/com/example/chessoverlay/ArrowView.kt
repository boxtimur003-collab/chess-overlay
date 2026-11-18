package com.example.chessoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Рисует стрелку лучшего хода поверх доски. flip=true — доска перевёрнута (играем за чёрных). */
class ArrowView(ctx: Context) : View(ctx) {
    var board = RectF()
    var flip = false
    var move: Move? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 0, 200, 83)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 0, 200, 83)
        style = Paint.Style.FILL
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
        val m = move ?: return
        if (board.width() < 8f || board.height() < 8f) return
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

/** Полноэкранное затемнение: палец проводит прямоугольник от левого верхнего к правому нижнему углу доски. */
class BoardSelectView(ctx: Context, private val onDone: (RectF) -> Unit) : View(ctx) {
    private var sx = 0f
    private var sy = 0f
    private var ex = 0f
    private var ey = 0f
    private var drawing = false

    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 0, 200, 83)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val inner = Paint().apply { color = Color.argb(50, 0, 200, 83) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
    }

    init {
        setBackgroundColor(Color.argb(150, 0, 0, 0))
    }

    override fun onDraw(c: Canvas) {
        c.drawText("Проведите от левого верхнего", width / 2f, 140f, txt)
        c.drawText("до правого нижнего угла доски", width / 2f, 200f, txt)
        if (drawing) {
            val r = RectF(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey))
            c.drawRect(r, inner)
            c.drawRect(r, frame)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.x; sy = e.y; ex = e.x; ey = e.y
                drawing = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                ex = e.x; ey = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                ex = e.x; ey = e.y
                val r = RectF(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey))
                if (r.width() > 100f && r.height() > 100f) onDone(r)
                else {
                    drawing = false
                    invalidate()
                }
            }
        }
        return true
    }
}
