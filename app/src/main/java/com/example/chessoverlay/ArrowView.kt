package com.example.chessoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class ArrowView(c: Context) : View(c) {
    var bx = 0f; var by = 0f; var bs = 0f
    var flip = false
    var frame = false
    var from = -1; var to = -1

    private val pf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFEB3B.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f }
    private val pg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFEB3B; style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val pa = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCE53935.toInt(); style = Paint.Style.STROKE; strokeWidth = 18f; strokeCap = Paint.Cap.ROUND }
    private val ph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCE53935.toInt(); style = Paint.Style.FILL }

    private fun cx(s: Int): Float { val f = s % 8; return bx + ((if (flip) 7 - f else f) + 0.5f) * bs / 8 }
    private fun cy(s: Int): Float { val r = s / 8; return by + ((if (flip) r else 7 - r) + 0.5f) * bs / 8 }

    override fun onDraw(cv: Canvas) {
        if (frame) {
            for (i in 1..7) {
                cv.drawLine(bx + i * bs / 8, by, bx + i * bs / 8, by + bs, pg)
                cv.drawLine(bx, by + i * bs / 8, bx + bs, by + i * bs / 8, pg)
            }
            cv.drawRect(bx, by, bx + bs, by + bs, pf)
        }
        if (from >= 0 && to >= 0) {
            val x1 = cx(from); val y1 = cy(from); val x2 = cx(to); val y2 = cy(to)
            val a = atan2(y2 - y1, x2 - x1)
            val head = bs / 8 * 0.5f
            cv.drawLine(x1, y1, x2 - cos(a) * head * 0.8f, y2 - sin(a) * head * 0.8f, pa)
            val path = Path()
            path.moveTo(x2, y2)
            path.lineTo(x2 - head * cos(a - 0.5f), y2 - head * sin(a - 0.5f))
            path.lineTo(x2 - head * cos(a + 0.5f), y2 - head * sin(a + 0.5f))
            path.close()
            cv.drawPath(path, ph)
        }
    }
}
