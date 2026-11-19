package com.example.chessoverlay

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
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
import kotlin.math.abs

/** turn: 1 = наш ход, 0 = ход соперника, -1 = не определили по подсветке последнего хода */
class Rec(val fen: String?, val msg: String, val turn: Int = -1)

object BoardRecognizer {
    const val START_PLACEMENT = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
    private const val S = 64          // размер клетки после нормализации, px
    private const val N = 32          // размер маски-силуэта для сравнения
    private const val MIN_SCORE = 0.38f
    private const val VER = 7         // версия формата эталонов (эталоны из v7/v8 остаются валидными)

    private var cvReady = false
    private val tpl = HashMap<Char, FloatArray>()
    private var thr = -1f
    private var lastLearnAt = 0L

    private class Cell(val occupied: Boolean, val mask: FloatArray?, val bright: Float, val bg: IntArray)
    private class LearnRes(val ok: Boolean, val msg: String)

    private fun <T : Mat> T.keep(l: ArrayList<Mat>): T {
        l.add(this)
        return this
    }

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

    /** Расстановка фигур (первое поле FEN) из массива 8x8, индекс = (8 - ранг) * 8 + файл. */
    fun placement(b: CharArray): String {
        val sb = StringBuilder()
        for (rr in 0..7) {
            var empty = 0
            for (ff in 0..7) {
                val ch = b[rr * 8 + ff]
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
        return sb.toString()
    }

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
     * Клетка -> (занята?, силуэт 32x32, яркость внутри силуэта, цвет фона клетки).
     * Все временные Mat освобождаются сразу (меньше работы для GC при частых сканах).
     */
    private fun analyze(cell: Mat): Cell {
        val tmp = ArrayList<Mat>()
        try {
            tmp.add(cell)
            val gray = Mat().keep(tmp)
            Imgproc.cvtColor(cell, gray, Imgproc.COLOR_BGR2GRAY)

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
            val medB = med(bs)
            val medG = med(gs)
            val medR = med(rs)
            val bgI = intArrayOf(medB, medG, medR)
            val bg = Scalar(medB.toDouble(), medG.toDouble(), medR.toDouble())
            val bgMat = Mat(S, S, CvType.CV_8UC3, bg).keep(tmp)
            val diff = Mat().keep(tmp)
            Core.absdiff(cell, bgMat, diff)
            val ch = ArrayList<Mat>()
            Core.split(diff, ch)
            tmp.addAll(ch)
            val dmax = Mat().keep(tmp)
            Core.max(ch[0], ch[1], dmax)
            Core.max(dmax, ch[2], dmax)

            val blur = Mat().keep(tmp)
            Imgproc.GaussianBlur(gray, blur, Size(3.0, 3.0), 0.0)
            val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0)).keep(tmp)
            val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0)).keep(tmp)
            val grad = Mat().keep(tmp)
            Imgproc.morphologyEx(blur, grad, Imgproc.MORPH_GRADIENT, k3)

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
            val md = Mat().keep(tmp)
            val mg = Mat().keep(tmp)
            Imgproc.threshold(dmax, md, td.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            Imgproc.threshold(grad, mg, tg.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            val mask = Mat().keep(tmp)
            Core.bitwise_or(md, mg, mask)

            val roi = Mat.zeros(S, S, CvType.CV_8UC1).keep(tmp)
            val roiSub = roi.submat(Rect(4, 4, S - 8, S - 8)).keep(tmp)
            roiSub.setTo(Scalar(255.0))
            Core.bitwise_and(mask, roi, mask)

            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, k5)
            val contours = ArrayList<MatOfPoint>()
            val mc = mask.clone().keep(tmp)
            val hier = Mat().keep(tmp)
            Imgproc.findContours(mc, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            tmp.addAll(contours)
            var maxA = 0.0
            var big: MatOfPoint? = null
            for (c in contours) {
                val a = Imgproc.contourArea(c)
                if (a > maxA) {
                    maxA = a
                    big = c
                }
            }
            val bigC = big ?: return Cell(false, null, 0f, bgI)
            val br = Imgproc.boundingRect(bigC)
            if (maxA < 0.08 * S * S || br.height < 0.38 * S) return Cell(false, null, 0f, bgI)

            val keepC = ArrayList<MatOfPoint>()
            for (c in contours) {
                val a = Imgproc.contourArea(c)
                if (a >= maxOf(60.0, 0.12 * maxA)) keepC.add(c)
            }
            val filled = Mat.zeros(S, S, CvType.CV_8UC1).keep(tmp)
            Imgproc.drawContours(filled, keepC, -1, Scalar(255.0), -1)

            val inner = Mat().keep(tmp)
            Imgproc.erode(filled, inner, k5)
            Imgproc.erode(inner, inner, k5)
            val src = if (Core.countNonZero(inner) < 30) filled else inner
            val bright = Core.mean(gray, src).`val`[0]

            val small = Mat().keep(tmp)
            Imgproc.resize(filled, small, Size(N.toDouble(), N.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val sb = ByteArray(N * N)
            small.get(0, 0, sb)
            val m = FloatArray(N * N) { (sb[it].toInt() and 0xFF) / 255f }
            return Cell(true, m, bright.toFloat(), bgI)
        } finally {
            for (m in tmp) {
                try {
                    m.release()
                } catch (e: Throwable) {
                }
            }
        }
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

    /** Скриншот доски (только в памяти) -> сетка 8x8 клеток. Ряды анализируются параллельно (8 потоков). */
    private fun scanCells(bmp: Bitmap): Array<Array<Cell>> {
        val rgba = Mat()
        val bgr = Mat()
        val board = Mat()
        try {
            Utils.bitmapToMat(bmp, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.resize(bgr, board, Size((S * 8).toDouble(), (S * 8).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val res = Array(8) { arrayOfNulls<Cell>(8) }
            var err: Throwable? = null
            val ts = ArrayList<Thread>()
            for (r in 0..7) {
                val t = Thread {
                    try {
                        for (c in 0..7) {
                            val sub = board.submat(Rect(c * S, r * S, S, S))
                            val cl = sub.clone()
                            sub.release()
                            res[r][c] = analyze(cl)
                        }
                    } catch (e: Throwable) {
                        err = e
                    }
                }
                ts.add(t)
                t.start()
            }
            for (t in ts) t.join()
            val e0 = err
            if (e0 != null) throw e0
            return Array(8) { r -> Array(8) { c -> res[r][c] ?: throw IllegalStateException("cell $r,$c") } }
        } finally {
            rgba.release()
            bgr.release()
            board.release()
        }
    }

    // ---------- автообучение эталонов по начальной позиции ----------

    private fun startMismatch(cells: Array<Array<Cell>>, flip: Boolean): Pair<ArrayList<String>, ArrayList<String>> {
        val startB = Position.fromFen(Position.START)?.b ?: CharArray(64) { '.' }
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
        return Pair(missing, extra)
    }

    private fun learnFromCells(cells: Array<Array<Cell>>, flip: Boolean, prefs: SharedPreferences): LearnRes {
        val startB = Position.fromFen(Position.START)?.b ?: return LearnRes(false, "Внутренняя ошибка START")
        val mm = startMismatch(cells, flip)
        if (mm.first.size + mm.second.size > 4) {
            return LearnRes(false, "Не похоже на начальную позицию. Проверь рамку («Доска») и переключатель «Играю за».")
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
            return LearnRes(false, "Не хватило фигур для эталонов (нет типов: $lack). Нет фигур на: " + mm.first.joinToString(","))
        }
        if (nw == 0 || nb == 0) return LearnRes(false, "Не вижу и белых, и чёрных фигур — проверь рамку доски.")
        val aw = sw / nw
        val ab = sbk / nb
        if (aw <= ab + 8f) {
            return LearnRes(
                false,
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
        return LearnRes(true, "Эталоны сохранены автоматически ($samples фигур).")
    }

    // ---------- распознавание ----------

    private fun medCh(l: ArrayList<IntArray>, ch: Int): Int {
        val a = ArrayList<Int>()
        for (x in l) a.add(x[ch])
        return med(a)
    }

    /**
     * Скриншот доски -> FEN. Если на доске начальная позиция — эталоны фигур сохраняются сами
     * (первый раз, и потом обновляются при каждой новой партии, не чаще раза в 2 минуты).
     * На диск ничего не пишется, кроме самих эталонов в SharedPreferences.
     */
    fun recognize(bmp: Bitmap, playWhite: Boolean, prefs: SharedPreferences): Rec {
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

        var note = ""
        val mm = startMismatch(cells, flip)
        val nearStart = mm.first.size + mm.second.size <= 4
        val strictStart = mm.first.isEmpty() && mm.second.isEmpty()
        val now = SystemClock.elapsedRealtime()
        if ((hasTemplates().not() && nearStart) || (strictStart && now - lastLearnAt > 120000L)) {
            val lr = learnFromCells(cells, flip, prefs)
            if (lr.ok) {
                note = lr.msg + " "
                lastLearnAt = now
            } else if (hasTemplates().not()) {
                return Rec(null, lr.msg)
            }
        }
        if (hasTemplates().not()) {
            return Rec(null, "Эталонов ещё нет: нажми «Сканировать» в начальной позиции — они сохранятся сами.")
        }

        val grid = CharArray(64) { '.' }
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
        if (bad.isNotEmpty()) return Rec(null, note + "Не распознаны клетки: " + bad.joinToString(", "))
        if (wk != 1) return Rec(null, note + (if (wk == 0) "Не найден белый король." else "Найдено белых королей: $wk."))
        if (bk != 1) return Rec(null, note + (if (bk == 0) "Не найден чёрный король." else "Найдено чёрных королей: $bk."))

        // кто ходил последним: подсвеченная клетка (последний ход) с фигурой
        val par0 = ArrayList<IntArray>()
        val par1 = ArrayList<IntArray>()
        for (r in 0..7) for (c in 0..7) {
            if ((r + c) % 2 == 0) par0.add(cells[r][c].bg) else par1.add(cells[r][c].bg)
        }
        val m0 = intArrayOf(medCh(par0, 0), medCh(par0, 1), medCh(par0, 2))
        val m1 = intArrayOf(medCh(par1, 0), medCh(par1, 1), medCh(par1, 2))
        var ourHi = 0
        var oppHi = 0
        for (r in 0..7) {
            for (c in 0..7) {
                val cell = cells[r][c]
                if (cell.mask == null) continue
                val ref = if ((r + c) % 2 == 0) m0 else m1
                var d = 0
                for (k in 0..2) d = maxOf(d, abs(cell.bg[k] - ref[k]))
                if (d > 16) {
                    val whitePiece = cell.bright > thr
                    if (whitePiece == playWhite) ourHi++ else oppHi++
                }
            }
        }
        val turn = if (oppHi > 0 && ourHi == 0) 1 else if (ourHi > 0 && oppHi == 0) 0 else -1

        var cs = ""
        if (grid[60] == 'K' && grid[63] == 'R') cs += "K"
        if (grid[60] == 'K' && grid[56] == 'R') cs += "Q"
        if (grid[4] == 'k' && grid[7] == 'r') cs += "k"
        if (grid[4] == 'k' && grid[0] == 'r') cs += "q"
        if (cs.isEmpty()) cs = "-"
        val fen = placement(grid) + (if (playWhite) " w " else " b ") + cs + " - 0 1"
        return Rec(fen, note + "Фигур: $count.", turn)
    }
}
