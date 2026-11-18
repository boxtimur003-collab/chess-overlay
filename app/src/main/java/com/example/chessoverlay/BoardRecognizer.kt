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
    private const val VER = 7         // версия формата эталонов (старые игнорируются)

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

    // ---------- эталоны (SharedPreferences, хранятся постоянно) ----------

    fun loadTemplates(p: SharedPreferences) {
        tpl.clear()
        thr = -1f
        if (p.getInt("tpl_ver", 0) != VER) return
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

    fun hasTemplates() = tpl.size == 6 && thr >= 0f

    // ---------- анализ клетки ----------

    private fun med(a: ArrayList<Int>): Int {
        a.sort()
        return a[a.size / 2]
    }

    private fun p90(a: ArrayList<Int>): Int {
        a.sort()
        return a[(a.size * 9) / 10]
    }

    private fun sqName(rr: Int, ff: Int) = "" + ('a' + ff) + (8 - rr)

    /** Обход тонкой рамки у краёв клетки (там гарантированно фон, а не фигура). */
    private inline fun forRing(fn: (Int, Int) -> Unit) {
        for (t in 1..3) {
            for (k in 16 until 48) {
                fn(t, k); fn(S - 1 - t, k); fn(k, t); fn(k, S - 1 - t)
            }
        }
    }

    /**
     * Клетка -> (занята?, силуэт 32x32, яркость внутри силуэта).
     * Маска = цветовое отличие от фона клетки  ИЛИ  морфологический градиент яркости.
     * Оба порога адаптивные: считаются по шуму рамки именно этой клетки, поэтому
     * тёмная фигура на тёмной клетке (низкий контраст) всё равно находится.
     */
    private fun analyze(cell: Mat): Cell {
        val gray = Mat()
        Imgproc.cvtColor(cell, gray, Imgproc.COLOR_BGR2GRAY)

        // 1) фон клетки: медиана цвета рамки (учитывает подсветку хода/выделения)
        val buf = ByteArray(S * S * 3)
        cell.get(0, 0, buf)
        val bs = ArrayList<Int>()
        val gs = ArrayList<Int>()
        val rs = ArrayList<Int>()
        forRing { x, y ->
            val i = (y * S + x) * 3
            bs.add(buf[i].toInt() and 0xFF)
            gs.add(buf[i + 1].toInt() and 0xFF)
            rs.add(buf[i + 2].toInt() and 0xFF)
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

        // 2) градиент яркости (перепады) — ловит контур даже при почти одинаковом цвете
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(3.0, 3.0), 0.0)
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        val grad = Mat()
        Imgproc.morphologyEx(blur, grad, Imgproc.MORPH_GRADIENT, k3)

        // 3) адаптивные пороги по шуму фона этой клетки
        val db = ByteArray(S * S)
        val gb = ByteArray(S * S)
        dmax.get(0, 0, db)
        grad.get(0, 0, gb)
        val dRing = ArrayList<Int>()
        val gRing = ArrayList<Int>()
        forRing { x, y ->
            val i = y * S + x
            dRing.add(db[i].toInt() and 0xFF)
            gRing.add(gb[i].toInt() and 0xFF)
        }
        val td = (p90(dRing) * 2 + 6).coerceIn(14, 45)
        val tg = (p90(gRing) * 2 + 8).coerceIn(10, 40)
        val md = Mat()
        val mg = Mat()
        Imgproc.threshold(dmax, md, td.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        Imgproc.threshold(grad, mg, tg.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        val mask = Mat()
        Core.bitwise_or(md, mg, mask)

        // края клетки не смотрим (линии сетки, соседние клетки)
        val roi = Mat.zeros(S, S, CvType.CV_8UC1)
        roi.submat(Rect(4, 4, S - 8, S - 8)).setTo(Scalar(255.0))
        Core.bitwise_and(mask, roi, mask)

        // 4) замыкаем контур, ищем внешние контуры
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, k5)
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(mask.clone(), contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        var maxA = 0.0
        var big: MatOfPoint? = null
        for (c in contours) {
            val a = Imgproc.contourArea(c)
            if (a > maxA) {
                maxA = a
                big = c
            }
        }
        val bigC = big ?: return Cell(false, null, 0f)
        val br = Imgproc.boundingRect(bigC)
        // занята, если силуэт достаточно большой и высокий (отсекает подсказки-точки и буквы координат)
        if (maxA < 0.08 * S * S || br.height < 0.38 * S) return Cell(false, null, 0f)

        // 5) заливаем силуэт; мелкие отдельные пятна (буквы координат) не берём
        val keep = ArrayList<MatOfPoint>()
        for (c in contours) {
            val a = Imgproc.contourArea(c)
            if (a >= maxOf(60.0, 0.12 * maxA)) keep.add(c)
        }
        val filled = Mat.zeros(S, S, CvType.CV_8UC1)
        Imgproc.drawContours(filled, keep, -1, Scalar(255.0), -1)

        // 6) яркость внутри силуэта (без контура) — отличает белые фигуры от чёрных
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

    /** Скриншот доски (только в памяти) -> сетка 8x8 проанализированных клеток. */
    private fun scanCells(bmp: Bitmap): Array<Array<Cell>> {
        val rgba = Mat()
        val bgr = Mat()
        val board = Mat()
        try {
            Utils.bitmapToMat(bmp, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.resize(bgr, board, Size((S * 8).toDouble(), (S * 8).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            return Array(8) { r -> Array(8) { c -> analyze(board.submat(Rect(c * S, r * S, S, S)).clone()) } }
        } finally {
            rgba.release()
            bgr.release()
            board.release()
        }
    }

    // ---------- «Запомнить эталоны» ----------

    /** Берёт НАЧАЛЬНУЮ позицию на экране и запоминает силуэты фигур + порог яркости белых/чёрных. */
    fun learn(bmp: Bitmap, playWhite: Boolean, prefs: SharedPreferences): Rec {
        if (ensureCv().not()) return Rec(null, "OpenCV не загрузился (initLocal вернул false)")
        val flip = playWhite.not()
        val cells = try {
            scanCells(bmp)
        } catch (e: Throwable) {
            return Rec(null, "Ошибка OpenCV: ${e.message}")
        }
        val startB = Position.fromFen(Position.START)?.b ?: return Rec(null, "Внутренняя ошибка START")

        val missing = ArrayList<String>()
        val extra = ArrayList<String>()
        for (r in 0..7) {
            for (c in 0..7) {
                val rr = if (flip) 7 - r else r
                val ff = if (flip) 7 - c else c
                val exp = startB[rr * 8 + ff]
                val occ = cells[r][c].occupied
                if (exp != '.' && occ.not()) missing.add(sqName(rr, ff))
                if (exp == '.' && occ) extra.add(sqName(rr, ff))
            }
        }
        if (missing.size + extra.size > 4) {
            var m = "Это не похоже на начальную позицию."
            if (missing.isNotEmpty()) m += " Фигур нет на: " + missing.joinToString(",") + "."
            if (extra.isNotEmpty()) m += " Лишнее на: " + extra.joinToString(",") + "."
            m += " Проверь рамку («Доска») и переключатель «Играю за»."
            return Rec(null, m)
        }

        val sums = HashMap<Char, FloatArray>()
        val cnt = HashMap<Char, Int>()
        var sw = 0f
        var nw = 0
        var sbk = 0f
        var nb = 0
        var samples = 0
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
                samples++
                if (ch.isUpperCase()) {
                    sw += cell.bright; nw++
                } else {
                    sbk += cell.bright; nb++
                }
            }
        }
        if (sums.size < 6) {
            val lack = "pnbrqk".filter { sums.containsKey(it).not() }
            return Rec(null, "Не хватило фигур для эталонов (нет типов: $lack). Нет фигур на: " + missing.joinToString(","))
        }
        if (nw == 0 || nb == 0) return Rec(null, "Не вижу и белых, и чёрных фигур — проверь рамку доски.")
        val aw = sw / nw
        val ab = sbk / nb
        if (aw <= ab + 8f) {
            return Rec(
                null,
                "Белые и чёрные фигуры не различились по яркости (белые ${aw.toInt()}, чёрные ${ab.toInt()}). " +
                        "Проверь, что «Играю за» совпадает с тем, кто стоит внизу доски."
            )
        }
        val newThr = (aw + ab) / 2f
        val ed = prefs.edit()
        for ((t, acc) in sums) {
            val n = cnt[t] ?: 1
            val avg = FloatArray(N * N) { acc[it] / n }
            tpl[t] = avg
            val by = ByteArray(N * N) { (avg[it] * 255f).toInt().coerceIn(0, 255).toByte() }
            ed.putString("tpl_$t", Base64.encodeToString(by, Base64.NO_WRAP))
        }
        thr = newThr
        ed.putFloat("bright_thr", newThr).putInt("tpl_ver", VER).commit()
        var msg = "Эталоны сохранены ($samples фигур, порог яркости ${newThr.toInt()})."
        if (missing.isNotEmpty()) msg += " Не нашёл: " + missing.joinToString(",") + "."
        return Rec("", msg)
    }

    // ---------- «Сканировать» ----------

    /** Скриншот доски -> FEN по сохранённым эталонам. Ничего не учит и не пишет на диск. */
    fun recognize(bmp: Bitmap, playWhite: Boolean): Rec {
        if (hasTemplates().not()) return Rec(null, "Сначала нажми \"Запомнить эталоны\"")
        if (ensureCv().not()) return Rec(null, "OpenCV не загрузился (initLocal вернул false)")
        val flip = playWhite.not()
        val cells = try {
            scanCells(bmp)
        } catch (e: Throwable) {
            return Rec(null, "Ошибка OpenCV: ${e.message}")
        }
        var occ = 0
        for (r in 0..7) for (c in 0..7) if (cells[r][c].occupied) occ++
        if (occ == 0) return Rec(null, "Фигур не найдено: проверь рамку доски («Доска»).")

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
                if (bt == 'p' && (rr == 0 || rr == 7)) {
                    bad.add("$name (пешка на крайней горизонтали)")
                    continue
                }
                val piece = if (cell.bright > thr) bt.uppercaseChar() else bt
                if (piece == 'K') wk++
                if (piece == 'k') bk++
                grid[rr * 8 + ff] = piece
                count++
            }
        }
        if (bad.isNotEmpty()) return Rec(null, "Не распознаны клетки: " + bad.joinToString(", "))
        if (wk != 1) return Rec(null, if (wk == 0) "Не найден белый король." else "Найдено белых королей: $wk.")
        if (bk != 1) return Rec(null, if (bk == 0) "Не найден чёрный король." else "Найдено чёрных королей: $bk.")

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
        return Rec(fen, "Фигур: $count.")
    }
}
