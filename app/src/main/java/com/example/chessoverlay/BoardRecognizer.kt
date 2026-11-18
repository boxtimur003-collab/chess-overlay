package com.example.chessoverlay

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Base64
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class Rec(val fen: String?, val msg: String)

object BoardRecognizer {
    private const val S = 64          // размер клетки после нормализации, px
    private const val N = 32          // размер маски-силуэта для сравнения
    private const val MIN_SCORE = 0.38f

    private var cvReady = false
    private val tpl = HashMap<Char, FloatArray>()
    private var thr = -1f

    private class Cell(val occupied: Boolean, val mask: FloatArray?, val bright: Float)

    private fun ensureCv(): Boolean {
        if (cvReady.not()) {
            cvReady = try {
                OpenCVLoader.initLocal()
            } catch (e: Throwable) {
                false
            }
        }
        return cvReady
    }

    fun loadTemplates(p: SharedPreferences) {
        tpl.clear()
        for (t in "pnbrqk") {
            val s = p.getString("tpl_$t", null) ?: continue
            try {
                val by = Base64.decode(s, Base64.NO_WRAP)
                if (by.size == N * N) tpl[t] = FloatArray(N * N) { (by[it].toInt() and 0xFF) / 255f }
            } catch (e: Exception) {
            }
        }
        thr = p.getFloat("bright_thr", -1f)
    }

    private fun hasTemplates() = tpl.size == 6 && thr >= 0f

    private fun med(a: ArrayList<Int>): Int {
        a.sort()
        return a[a.size / 2]
    }

    private fun sqName(rr: Int, ff: Int) = "" + ('a' + ff) + (8 - rr)

    /** Анализ одной клетки: занята ли, силуэт 32x32 и яркость внутри силуэта. */
    private fun analyze(cell: Mat): Cell {
        // фон клетки = медиана цвета тонкой рамки у краёв (учитывает подсветку последнего хода)
        val buf = ByteArray(S * S * 3)
        cell.get(0, 0, buf)
        val bs = ArrayList<Int>()
        val gs = ArrayList<Int>()
        val rs = ArrayList<Int>()
        fun add(x: Int, y: Int) {
            val i = (y * S + x) * 3
            bs.add(buf[i].toInt() and 0xFF)
            gs.add(buf[i + 1].toInt() and 0xFF)
            rs.add(buf[i + 2].toInt() and 0xFF)
        }
        for (t in 1..3) {
            for (k in 16 until 48) {
                add(t, k); add(S - 1 - t, k); add(k, t); add(k, S - 1 - t)
            }
        }
        val bg = Scalar(med(bs).toDouble(), med(gs).toDouble(), med(rs).toDouble())
        val bgMat = Mat(S, S, CvType.CV_8UC3, bg)
        val diff = Mat()
        Core.absdiff(cell, bgMat, diff)
        val ch = ArrayList<Mat>()
        Core.split(diff, ch)
        val dmax = Mat()
        Core.max(ch[0], ch[1], dmax)
        Core.max(dmax, ch[2], dmax)
        val mask = Mat()
        Imgproc.threshold(dmax, mask, 40.0, 255.0, Imgproc.THRESH_BINARY)
        // отбрасываем края клетки (буквы/цифры координат, линии сетки)
        val roi = Mat.zeros(S, S, CvType.CV_8UC1)
        roi.submat(Rect(4, 4, S - 8, S - 8)).setTo(Scalar(255.0))
        Core.bitwise_and(mask, roi, mask)
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, k3)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, k5)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(mask.clone(), contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        var maxA = 0.0
        val keep = ArrayList<MatOfPoint>()
        for (c in contours) {
            val a = Imgproc.contourArea(c)
            if (a > maxA) maxA = a
            if (a >= 80.0) keep.add(c)
        }
        if (maxA < 0.10 * S * S) return Cell(false, null, 0f)

        // заливаем силуэт (белая фигура на светлой клетке часто видна только контуром)
        val filled = Mat.zeros(S, S, CvType.CV_8UC1)
        Imgproc.drawContours(filled, keep, -1, Scalar(255.0), -1)

        val gray = Mat()
        Imgproc.cvtColor(cell, gray, Imgproc.COLOR_BGR2GRAY)
        val inner = Mat()
        Imgproc.erode(filled, inner, k5)
        Imgproc.erode(inner, inner, k5)
        val src = if (Core.countNonZero(inner) < 30) filled else inner
        val bright = Core.mean(gray, src).`val`[0]

        val small = Mat()
        Imgproc.resize(filled, small, Size(N.toDouble(), N.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val sb = ByteArray(N * N)
        small.get(0, 0, sb)
        val m = FloatArray(N * N) { (sb[it].toInt() and 0xFF) / 255f }
        return Cell(true, m, bright.toFloat())
    }

    private fun softIou(a: FloatArray, b: FloatArray): Float {
        var mn = 0f
        var mx = 0f
        for (i in a.indices) {
            mn += minOf(a[i], b[i])
            mx += maxOf(a[i], b[i])
        }
        return if (mx <= 0f) 0f else mn / mx
    }

    /**
     * Скриншот доски -> FEN. Если на доске начальная позиция — заодно (пере)учит эталоны фигур.
     * playWhite=true: белые внизу.
     */
    fun recognize(bmp: Bitmap, playWhite: Boolean, prefs: SharedPreferences): Rec {
        if (ensureCv().not()) return Rec(null, "OpenCV не загрузился (initLocal вернул false)")
        val flip = playWhite.not()
        val rgba = Mat()
        val bgr = Mat()
        val board = Mat()
        try {
            Utils.bitmapToMat(bmp, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.resize(bgr, board, Size((S * 8).toDouble(), (S * 8).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val cells = Array(8) { r -> Array(8) { c -> analyze(board.submat(Rect(c * S, r * S, S, S)).clone()) } }

            var occCount = 0
            var startLike = true
            for (r in 0..7) {
                for (c in 0..7) {
                    if (cells[r][c].occupied) occCount++
                    val want = r <= 1 || r >= 6
                    if (cells[r][c].occupied != want) startLike = false
                }
            }
            if (occCount == 0) {
                return Rec(null, "Фигур не найдено: проверь рамку доски («Доска») — возможно, она сдвинута.")
            }

            var learnedNote = ""
            if (startLike) {
                val startB = Position.fromFen(Position.START)?.b ?: return Rec(null, "Внутренняя ошибка START")
                val sums = HashMap<Char, FloatArray>()
                val cnt = HashMap<Char, Int>()
                var sw = 0f
                var nw = 0
                var sbk = 0f
                var nb = 0
                for (r in 0..7) {
                    for (c in 0..7) {
                        val cell = cells[r][c]
                        val m = cell.mask ?: continue
                        val rr = if (flip) 7 - r else r
                        val ff = if (flip) 7 - c else c
                        val ch = startB[rr * 8 + ff]
                        if (ch == '.') continue
                        val t = ch.lowercaseChar()
                        val acc = sums.getOrPut(t) { FloatArray(N * N) }
                        for (i in 0 until N * N) acc[i] += m[i]
                        cnt[t] = (cnt[t] ?: 0) + 1
                        if (ch.isUpperCase()) {
                            sw += cell.bright; nw++
                        } else {
                            sbk += cell.bright; nb++
                        }
                    }
                }
                if (nw == 0 || nb == 0 || sums.size < 6) {
                    return Rec(null, "Начальная позиция найдена, но эталоны собрать не удалось — перерисуй доску («Доска»).")
                }
                val aw = sw / nw
                val ab = sbk / nb
                if (aw <= ab + 15f) {
                    return Rec(
                        null,
                        "Яркость белых и чёрных фигур не различается как ожидалось. Проверь переключатель «Играю за» — " +
                                "он должен совпадать с тем, кто стоит внизу доски."
                    )
                }
                thr = (aw + ab) / 2f
                val ed = prefs.edit()
                for ((t, acc) in sums) {
                    val n = cnt[t] ?: 1
                    val avg = FloatArray(N * N) { acc[it] / n }
                    tpl[t] = avg
                    val by = ByteArray(N * N) { (avg[it] * 255f).toInt().coerceIn(0, 255).toByte() }
                    ed.putString("tpl_$t", Base64.encodeToString(by, Base64.NO_WRAP))
                }
                ed.putFloat("bright_thr", thr).apply()
                learnedNote = "Эталоны фигур обновлены (начальная позиция). "
            }

            if (hasTemplates().not()) {
                return Rec(
                    null,
                    "Нет эталонов фигур. Открой партию в НАЧАЛЬНОЙ позиции и нажми «Сканировать» — эталоны запомнятся сами."
                )
            }

            val grid = CharArray(64) { '.' } // реальная ориентация: индекс = (8 - ранг) * 8 + файл
            val bad = ArrayList<String>()
            var count = 0
            var wk = 0
            var bk = 0
            for (r in 0..7) {
                for (c in 0..7) {
                    val cell = cells[r][c]
                    val m = cell.mask ?: continue
                    val rr = if (flip) 7 - r else r
                    val ff = if (flip) 7 - c else c
                    var best = 0f
                    var bt = 'p'
                    for ((t, tp) in tpl) {
                        val s = softIou(tp, m)
                        if (s > best) {
                            best = s
                            bt = t
                        }
                    }
                    val name = sqName(rr, ff)
                    if (best < MIN_SCORE) {
                        bad.add("$name (сходство ${(best * 100).toInt()}%)")
                        continue
                    }
                    val white = cell.bright > thr
                    val piece = if (white) bt.uppercaseChar() else bt
                    if (bt == 'p' && (rr == 0 || rr == 7)) {
                        bad.add("$name (пешка на крайней горизонтали)")
                        continue
                    }
                    if (piece == 'K') wk++
                    if (piece == 'k') bk++
                    grid[rr * 8 + ff] = piece
                    count++
                }
            }
            if (bad.isNotEmpty()) {
                return Rec(null, learnedNote + "Не распознаны клетки: " + bad.joinToString(", "))
            }
            if (wk != 1) return Rec(null, learnedNote + (if (wk == 0) "Не найден белый король." else "Найдено белых королей: $wk."))
            if (bk != 1) return Rec(null, learnedNote + (if (bk == 0) "Не найден чёрный король." else "Найдено чёрных королей: $bk."))

            val sb = StringBuilder()
            for (rr in 0..7) {
                var empty = 0
                for (ff in 0..7) {
                    val ch = grid[rr * 8 + ff]
                    if (ch == '.') empty++
                    else {
                        if (empty > 0) {
                            sb.append(empty)
                            empty = 0
                        }
                        sb.append(ch)
                    }
                }
                if (empty > 0) sb.append(empty)
                if (rr < 7) sb.append('/')
            }
            var cs = ""
            if (grid[60] == 'K' && grid[63] == 'R') cs += "K"
            if (grid[60] == 'K' && grid[56] == 'R') cs += "Q"
            if (grid[4] == 'k' && grid[7] == 'r') cs += "k"
            if (grid[4] == 'k' && grid[0] == 'r') cs += "q"
            if (cs.isEmpty()) cs = "-"
            val fen = sb.toString() + (if (playWhite) " w " else " b ") + cs + " - 0 1"
            return Rec(fen, learnedNote + "Фигур: $count.")
        } catch (e: Throwable) {
            return Rec(null, "Ошибка OpenCV: ${e.message}")
        } finally {
            rgba.release()
            bgr.release()
            board.release()
        }
    }
}
