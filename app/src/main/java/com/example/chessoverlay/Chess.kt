package com.example.chessoverlay

import kotlin.math.abs

data class Move(val from: Int, val to: Int, val promo: Char = ' ') {
    override fun toString(): String {
        val s = "" + ('a' + from % 8) + (8 - from / 8) + ('a' + to % 8) + (8 - to / 8)
        return if (promo != ' ') s + promo else s
    }
}

class Position(
    val b: CharArray = CharArray(64) { '.' },
    var white: Boolean = true,
    var castle: String = "KQkq",
    var ep: Int = -1
) {
    companion object {
        private val DIRS = arrayOf(
            intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1),
            intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1)
        )
        private val KN = arrayOf(
            intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, -2), intArrayOf(-1, 2),
            intArrayOf(1, -2), intArrayOf(1, 2), intArrayOf(2, -1), intArrayOf(2, 1)
        )
        const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        fun fromFen(fen: String): Position? {
            try {
                val p = fen.trim().split(Regex("\\s+"))
                val rows = p[0].split("/")
                if (rows.size != 8) return null
                val pos = Position()
                for (r in 0 until 8) {
                    var f = 0
                    for (c in rows[r]) {
                        if (c.isDigit()) f += c - '0'
                        else {
                            if (f > 7 || "pnbrqkPNBRQK".indexOf(c) < 0) return null
                            pos.b[r * 8 + f] = c
                            f++
                        }
                    }
                    if (f != 8) return null
                }
                pos.white = p.getOrElse(1) { "w" } == "w"
                val cs = p.getOrElse(2) { "-" }
                pos.castle = if (cs == "-") "" else cs
                val e = p.getOrElse(3) { "-" }
                pos.ep = if (e.length == 2) (8 - (e[1] - '0')) * 8 + (e[0] - 'a') else -1
                if (pos.b.indexOf('K') < 0 || pos.b.indexOf('k') < 0) return null
                return pos
            } catch (ex: Exception) {
                return null
            }
        }
    }

    private fun addPawn(res: MutableList<Move>, from: Int, to: Int, promo: Boolean) {
        if (promo) {
            for (p in "qrbn") res.add(Move(from, to, p))
        } else res.add(Move(from, to))
    }

    fun pseudo(side: Boolean): MutableList<Move> {
        val res = ArrayList<Move>()
        for (sq in 0 until 64) {
            val c = b[sq]
            if (c == '.' || c.isUpperCase() != side) continue
            val r = sq / 8
            val f = sq % 8
            when (c.lowercaseChar()) {
                'p' -> {
                    val dir = if (side) -1 else 1
                    val startRow = if (side) 6 else 1
                    val lastRow = if (side) 0 else 7
                    val r1 = r + dir
                    if (r1 in 0..7) {
                        if (b[r1 * 8 + f] == '.') {
                            addPawn(res, sq, r1 * 8 + f, r1 == lastRow)
                            val r2 = r + 2 * dir
                            if (r == startRow && b[r2 * 8 + f] == '.') res.add(Move(sq, r2 * 8 + f))
                        }
                        for (df in intArrayOf(-1, 1)) {
                            val nf = f + df
                            if (nf < 0 || nf > 7) continue
                            val t = r1 * 8 + nf
                            val tc = b[t]
                            if (tc != '.' && tc.isUpperCase() != side) {
                                addPawn(res, sq, t, r1 == lastRow)
                            } else if (tc == '.' && t == ep) {
                                val victim = b[r * 8 + nf]
                                if (victim.lowercaseChar() == 'p' && victim.isUpperCase() != side) res.add(Move(sq, t))
                            }
                        }
                    }
                }
                'n' -> for (d in KN) {
                    val nr = r + d[0]
                    val nf = f + d[1]
                    if (nr < 0 || nr > 7 || nf < 0 || nf > 7) continue
                    val tc = b[nr * 8 + nf]
                    if (tc == '.' || tc.isUpperCase() != side) res.add(Move(sq, nr * 8 + nf))
                }
                'k' -> {
                    for (d in DIRS) {
                        val nr = r + d[0]
                        val nf = f + d[1]
                        if (nr < 0 || nr > 7 || nf < 0 || nf > 7) continue
                        val tc = b[nr * 8 + nf]
                        if (tc == '.' || tc.isUpperCase() != side) res.add(Move(sq, nr * 8 + nf))
                    }
                    val base = if (side) 56 else 0
                    val ks = if (side) "K" else "k"
                    val qs = if (side) "Q" else "q"
                    if (sq == base + 4 && attacked(sq, side.not()).not()) {
                        if (castle.contains(ks) && b[base + 7] == (if (side) 'R' else 'r') &&
                            b[base + 5] == '.' && b[base + 6] == '.' &&
                            attacked(base + 5, side.not()).not() && attacked(base + 6, side.not()).not()
                        ) res.add(Move(sq, base + 6))
                        if (castle.contains(qs) && b[base] == (if (side) 'R' else 'r') &&
                            b[base + 1] == '.' && b[base + 2] == '.' && b[base + 3] == '.' &&
                            attacked(base + 3, side.not()).not() && attacked(base + 2, side.not()).not()
                        ) res.add(Move(sq, base + 2))
                    }
                }
                else -> {
                    val lc = c.lowercaseChar()
                    for (i in 0 until 8) {
                        if (lc == 'b' && i < 4) continue
                        if (lc == 'r' && i >= 4) continue
                        var nr = r + DIRS[i][0]
                        var nf = f + DIRS[i][1]
                        while (nr in 0..7 && nf in 0..7) {
                            val tc = b[nr * 8 + nf]
                            if (tc == '.') res.add(Move(sq, nr * 8 + nf))
                            else {
                                if (tc.isUpperCase() != side) res.add(Move(sq, nr * 8 + nf))
                                break
                            }
                            nr += DIRS[i][0]
                            nf += DIRS[i][1]
                        }
                    }
                }
            }
        }
        return res
    }

    fun attacked(sq: Int, by: Boolean): Boolean {
        val r = sq / 8
        val f = sq % 8
        val pr = if (by) r + 1 else r - 1
        if (pr in 0..7) {
            for (df in intArrayOf(-1, 1)) {
                val nf = f + df
                if (nf in 0..7 && b[pr * 8 + nf] == (if (by) 'P' else 'p')) return true
            }
        }
        for (d in KN) {
            val nr = r + d[0]
            val nf = f + d[1]
            if (nr in 0..7 && nf in 0..7 && b[nr * 8 + nf] == (if (by) 'N' else 'n')) return true
        }
        for (d in DIRS) {
            val nr = r + d[0]
            val nf = f + d[1]
            if (nr in 0..7 && nf in 0..7 && b[nr * 8 + nf] == (if (by) 'K' else 'k')) return true
        }
        for (i in 0 until 8) {
            var nr = r + DIRS[i][0]
            var nf = f + DIRS[i][1]
            while (nr in 0..7 && nf in 0..7) {
                val tc = b[nr * 8 + nf]
                if (tc != '.') {
                    if (tc.isUpperCase() == by) {
                        val l = tc.lowercaseChar()
                        if (l == 'q' || (l == 'r' && i < 4) || (l == 'b' && i >= 4)) return true
                    }
                    break
                }
                nr += DIRS[i][0]
                nf += DIRS[i][1]
            }
        }
        return false
    }

    fun inCheck(side: Boolean): Boolean {
        val k = b.indexOf(if (side) 'K' else 'k')
        return k >= 0 && attacked(k, side.not())
    }

    fun make(m: Move): Position {
        val n = Position(b.copyOf(), white.not(), castle, -1)
        val c = n.b[m.from]
        val w = c.isUpperCase()
        val tc = n.b[m.to]
        n.b[m.to] = c
        n.b[m.from] = '.'
        if (m.promo != ' ') n.b[m.to] = if (w) m.promo.uppercaseChar() else m.promo.lowercaseChar()
        val fr = m.from / 8
        val ff = m.from % 8
        val tr = m.to / 8
        val tf = m.to % 8
        if (c.lowercaseChar() == 'p') {
            if (m.to == ep && ff != tf && tc == '.') n.b[fr * 8 + tf] = '.'
            if (abs(tr - fr) == 2) n.ep = ((fr + tr) / 2) * 8 + ff
        }
        if (c.lowercaseChar() == 'k' && abs(tf - ff) == 2) {
            if (tf == 6) {
                n.b[tr * 8 + 5] = n.b[tr * 8 + 7]
                n.b[tr * 8 + 7] = '.'
            } else {
                n.b[tr * 8 + 3] = n.b[tr * 8]
                n.b[tr * 8] = '.'
            }
        }
        var cs = castle
        if (c == 'K') cs = cs.replace("K", "").replace("Q", "")
        if (c == 'k') cs = cs.replace("k", "").replace("q", "")
        for (s in intArrayOf(m.from, m.to)) {
            when (s) {
                63 -> cs = cs.replace("K", "")
                56 -> cs = cs.replace("Q", "")
                7 -> cs = cs.replace("k", "")
                0 -> cs = cs.replace("q", "")
            }
        }
        n.castle = cs
        return n
    }

    fun legal(): List<Move> = pseudo(white).filter { m ->
        val n = make(m)
        n.inCheck(white).not()
    }
}

/** Результат поиска. mateIn > 0 — найден форсированный мат в mateIn ходов (наших). */
class EngineResult(val move: Move?, val mateIn: Int, val depth: Int, val book: Boolean)

/** Дебютная книга: линии идут от начальной позиции; ключ = расстановка + очередь хода. */
object Book {
    private val LINES = arrayOf(
        // ---- Испанская партия ----
        "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6 b5a4 g8f6 e1g1 f8e7 f1e1 b7b5 a4b3 d7d6 c2c3 e8g8 h2h3 c6a5 b3c2 c7c5 d2d4 d8c7",
        "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6 b5a4 g8f6 e1g1 f8e7 f1e1 b7b5 a4b3 d7d6 c2c3 e8g8 h2h3 c6b8 d2d4 b8d7",
        "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6 b5a4 g8f6 e1g1 f6e4 d2d4 b7b5 a4b3 d7d5 d4e5 c8e6 b1d2 e4c5 c2c3 f8e7 b3c2 e8g8",
        "e2e4 e7e5 g1f3 b8c6 f1b5 g8f6 e1g1 f6e4 d2d4 e4d6 b5c6 d7c6 d4e5 d6f5 d1d8 e8d8 b1c3 d8e8 h2h3 h7h5",
        "e2e4 e7e5 g1f3 b8c6 f1b5 g8f6 d2d3 f8c5 c2c3 e8g8 e1g1 d7d6",
        "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6 b5c6 d7c6 e1g1 f7f6 d2d4 e5d4 f3d4 c6c5 d4b3 d8d1 f1d1 c8e6 b1c3",
        // ---- Итальянская партия, Четырёх коней ----
        "e2e4 e7e5 g1f3 b8c6 f1c4 f8c5 c2c3 g8f6 d2d3 d7d6 e1g1 e8g8 f1e1 a7a6 a2a4 c5a7",
        "e2e4 e7e5 g1f3 b8c6 f1c4 g8f6 d2d3 f8c5 c2c3 d7d6 e1g1 e8g8 b2b4 c5b6 a2a4 a7a6",
        "e2e4 e7e5 g1f3 b8c6 f1c4 f8c5 d2d3 g8f6 e1g1 d7d6 c2c3 e8g8 b1d2 a7a6",
        "e2e4 e7e5 g1f3 b8c6 b1c3 g8f6 f1b5 f8b4 e1g1 e8g8 d2d3 d7d6 c1g5 b4c3 b2c3 d8e7 f1e1 c6d8 d2d4 d8e6",
        // ---- Шотландская, Петров ----
        "e2e4 e7e5 g1f3 b8c6 d2d4 e5d4 f3d4 g8f6 d4c6 b7c6 e4e5 d8e7 d1e2 f6d5 c2c4 c8a6",
        "e2e4 e7e5 g1f3 b8c6 d2d4 e5d4 f3d4 f8c5 c1e3 d8f6 c2c3 g8e7 f1c4 e8g8",
        "e2e4 e7e5 g1f3 g8f6 f3e5 d7d6 e5f3 f6e4 d2d4 d6d5 f1d3 f8e7 e1g1 b8c6 c2c4 c6b4 d3e2 e8g8 b1c3 c8f5",
        "e2e4 e7e5 g1f3 g8f6 d2d4 f6e4 f1d3 d7d5 f3e5 b8d7 e5d7 c8d7 e1g1 f8d6 c2c4 c7c6",
        // ---- Сицилианская защита ----
        "e2e4 c7c5 g1f3 d7d6 d2d4 c5d4 f3d4 g8f6 b1c3 a7a6 c1e3 e7e5 d4b3 c8e6 f2f3 f8e7 d1d2 e8g8 e1c1 b8d7 g2g4 b7b5",
        "e2e4 c7c5 g1f3 d7d6 d2d4 c5d4 f3d4 g8f6 b1c3 a7a6 f1e2 e7e5 d4b3 f8e7 e1g1 e8g8 c1e3 c8e6",
        "e2e4 c7c5 g1f3 d7d6 d2d4 c5d4 f3d4 g8f6 b1c3 a7a6 c1g5 e7e6 f2f4 f8e7 d1f3 d8c7 e1c1 b8d7 g2g4 b7b5",
        "e2e4 c7c5 g1f3 d7d6 d2d4 c5d4 f3d4 g8f6 b1c3 g7g6 c1e3 f8g7 f2f3 e8g8 d1d2 b8c6 f1c4 c8d7 e1c1 a8c8 c4b3 c6e5",
        "e2e4 c7c5 g1f3 e7e6 d2d4 c5d4 f3d4 g8f6 b1c3 d7d6 f1e2 f8e7 e1g1 e8g8 c1e3 a7a6 f2f4 d8c7",
        "e2e4 c7c5 g1f3 b8c6 d2d4 c5d4 f3d4 g8f6 b1c3 e7e5 d4b5 d7d6 c1g5 a7a6 b5a3 b7b5 g5f6 g7f6 c3d5 f6f5",
        "e2e4 c7c5 g1f3 e7e6 d2d4 c5d4 f3d4 b8c6 b1c3 d8c7 f1e2 a7a6 e1g1 g8f6 c1e3 f8b4",
        "e2e4 c7c5 c2c3 g8f6 e4e5 f6d5 d2d4 c5d4 g1f3 b8c6 c3d4 d7d6 f1c4 d5b6 c4b3 d6e5 f3e5 c6e5 d4e5",
        "e2e4 c7c5 g1f3 b8c6 f1b5 g7g6 e1g1 f8g7 f1e1 g8f6 c2c3 e8g8",
        "e2e4 c7c5 b1c3 b8c6 g2g3 g7g6 f1g2 f8g7 d2d3 d7d6 f2f4 e7e6 g1f3 g8e7 e1g1 e8g8",
        // ---- Французская защита ----
        "e2e4 e7e6 d2d4 d7d5 b1c3 g8f6 c1g5 f8e7 e4e5 f6d7 g5e7 d8e7 f2f4 e8g8 g1f3 c7c5 d1d2 b8c6 e1c1 a7a6",
        "e2e4 e7e6 d2d4 d7d5 b1c3 f8b4 e4e5 c7c5 a2a3 b4c3 b2c3 g8e7 g1f3 b8c6 a3a4 d8a5 c1d2 c8d7",
        "e2e4 e7e6 d2d4 d7d5 e4e5 c7c5 c2c3 b8c6 g1f3 d8b6 a2a3 c5c4 b1d2 c6a5 f1e2 c8d7",
        "e2e4 e7e6 d2d4 d7d5 b1d2 g8f6 e4e5 f6d7 f1d3 c7c5 c2c3 b8c6 g1e2 c5d4 c3d4 f7f6 e5f6 d7f6 e1g1 f8d6",
        "e2e4 e7e6 d2d4 d7d5 e4d5 e6d5 g1f3 g8f6 f1d3 f8d6 e1g1 e8g8 c1g5 c8g4",
        // ---- Каро-Канн, Скандинавская, Пирц, Алёхин ----
        "e2e4 c7c6 d2d4 d7d5 b1c3 d5e4 c3e4 c8f5 e4g3 f5g6 h2h4 h7h6 g1f3 b8d7 h4h5 g6h7 f1d3 h7d3 d1d3 g8f6 c1d2 e7e6 e1c1 f8e7",
        "e2e4 c7c6 d2d4 d7d5 e4e5 c8f5 g1f3 e7e6 f1e2 c6c5 e1g1 b8c6 c2c3 g8e7",
        "e2e4 c7c6 d2d4 d7d5 e4d5 c6d5 c2c4 g8f6 b1c3 e7e6 g1f3 f8e7 c4d5 f6d5 f1d3 b8c6 e1g1 e8g8 f1e1 d5f6",
        "e2e4 d7d5 e4d5 d8d5 b1c3 d5a5 d2d4 g8f6 g1f3 c7c6 f1c4 c8f5 c1d2 e7e6",
        "e2e4 d7d6 d2d4 g8f6 b1c3 g7g6 g1f3 f8g7 f1e2 e8g8 e1g1 c7c6 a2a4 b8d7",
        "e2e4 g8f6 e4e5 f6d5 d2d4 d7d6 g1f3 c8g4 f1e2 e7e6 e1g1 f8e7 c2c4 d5b6",
        // ---- Ферзевый гамбит ----
        "d2d4 d7d5 c2c4 e7e6 b1c3 g8f6 c1g5 f8e7 e2e3 e8g8 g1f3 h7h6 g5h4 b7b6 c4d5 f6d5 h4e7 d8e7 c3d5 e6d5 a1c1 c8e6",
        "d2d4 d7d5 c2c4 e7e6 b1c3 g8f6 c1g5 b8d7 e2e3 f8e7 g1f3 e8g8 a1c1 c7c6 f1d3 d5c4 d3c4 f6d5 g5e7 d8e7 e1g1 d5c3 c1c3 e6e5",
        "d2d4 d7d5 c2c4 e7e6 b1c3 g8f6 c4d5 e6d5 c1g5 c7c6 e2e3 f8e7 f1d3 b8d7 d1c2 e8g8 g1e2 f8e8",
        "d2d4 d7d5 c2c4 d5c4 g1f3 g8f6 e2e3 e7e6 f1c4 c7c5 e1g1 a7a6 d1e2 b7b5 c4b3 c8b7 f1d1 b8d7 b1c3 d8b6",
        "d2d4 d7d5 c2c4 c7c6 g1f3 g8f6 b1c3 d5c4 a2a4 c8f5 e2e3 e7e6 f1c4 f8b4 e1g1 b8d7 d1e2 f5g6",
        "d2d4 d7d5 c2c4 c7c6 g1f3 g8f6 b1c3 e7e6 e2e3 b8d7 f1d3 d5c4 d3c4 b7b5 c4d3 a7a6 e3e4 c6c5",
        "d2d4 g8f6 c2c4 e7e6 g2g3 d7d5 f1g2 f8e7 g1f3 e8g8 e1g1 d5c4 d1c2 a7a6 c2c4 b7b5 c4c2 c8b7",
        "d2d4 d7d5 g1f3 g8f6 c1f4 e7e6 e2e3 c7c5 c2c3 b8c6 b1d2 f8d6 f4g3 e8g8 f1d3 d8c7",
        // ---- Защита Грюнфельда ----
        "d2d4 g8f6 c2c4 g7g6 b1c3 d7d5 c4d5 f6d5 e2e4 d5c3 b2c3 f8g7 g1f3 c7c5 f1e2 e8g8 e1g1 b8c6 c1e3 c5d4 c3d4 c8g4",
        "d2d4 g8f6 c2c4 g7g6 b1c3 d7d5 g1f3 f8g7 d1b3 d5c4 b3c4 e8g8 e2e4 c8g4 c1e3 f6d7",
        // ---- Староиндийская, Нимцо-индийская, Ферзевый индийская, Бенони, Голландская ----
        "d2d4 g8f6 c2c4 g7g6 b1c3 f8g7 e2e4 d7d6 g1f3 e8g8 f1e2 e7e5 e1g1 b8c6 d4d5 c6e7 f3e1 f6d7 c1e3 f7f5 f2f3 f5f4 e3f2 g6g5",
        "d2d4 g8f6 c2c4 g7g6 b1c3 f8g7 e2e4 d7d6 f2f3 e8g8 c1e3 e7e5 g1e2 c7c6 d1d2 b8d7",
        "d2d4 g8f6 c2c4 g7g6 g2g3 f8g7 f1g2 e8g8 b1c3 d7d6 g1f3 b8d7 e1g1 e7e5 e2e4 c7c6 h2h3 d8b6",
        "d2d4 g8f6 c2c4 e7e6 b1c3 f8b4 e2e3 e8g8 f1d3 d7d5 g1f3 c7c5 e1g1 b8c6 a2a3 b4c3 b2c3 d5c4 d3c4 d8c7",
        "d2d4 g8f6 c2c4 e7e6 b1c3 f8b4 d1c2 e8g8 a2a3 b4c3 c2c3 b7b6 c1g5 c8b7 e2e3 d7d6 f1d3 b8d7",
        "d2d4 g8f6 c2c4 e7e6 g1f3 b7b6 g2g3 c8b7 f1g2 f8e7 e1g1 e8g8 b1c3 f6e4 d1c2 e4c3 c2c3 c7c5",
        "d2d4 g8f6 c2c4 c7c5 d4d5 e7e6 b1c3 e6d5 c4d5 d7d6 e2e4 g7g6 g1f3 f8g7 f1e2 e8g8 e1g1 f8e8",
        "d2d4 f7f5 g2g3 g8f6 f1g2 e7e6 g1f3 f8e7 e1g1 e8g8 c2c4 d7d6 b1c3 d8e8",
        // ---- Английское начало, Рети ----
        "c2c4 e7e5 b1c3 g8f6 g1f3 b8c6 g2g3 d7d5 c4d5 f6d5 f1g2 d5b6 e1g1 f8e7 d2d3 e8g8 a2a3 a7a5",
        "c2c4 c7c5 g1f3 g8f6 b1c3 b8c6 g2g3 g7g6 f1g2 f8g7 e1g1 e8g8 d2d4 c5d4 f3d4 c6d4 d1d4 d7d6",
        "c2c4 g8f6 b1c3 e7e6 g1f3 d7d5 d2d4 f8e7 c1g5 e8g8 e2e3 h7h6 g5h4 f6e4",
        "g1f3 d7d5 g2g3 g8f6 f1g2 e7e6 e1g1 f8e7 d2d4 e8g8 c2c4 d5c4 d1c2 b7b5",
        "g1f3 g8f6 c2c4 g7g6 b1c3 f8g7 e2e4 d7d6 d2d4 e8g8"
    )
    private val map = HashMap<String, ArrayList<Move>>()
    private var built = false
    private val rnd = java.util.Random()

    private fun key(p: Position): String = String(p.b) + (if (p.white) "w" else "b")

    private fun build() {
        for (line in LINES) {
            var p = Position.fromFen(Position.START) ?: continue
            for (tok in line.split(" ")) {
                val m = p.legal().firstOrNull { it.toString() == tok } ?: break
                val list = map.getOrPut(key(p)) { ArrayList() }
                if (list.contains(m).not()) list.add(m)
                p = p.make(m)
            }
        }
        built = true
    }

    fun pick(p: Position): Move? {
        if (built.not()) build()
        val l = map[key(p)] ?: return null
        val legal = p.legal()
        val c = l.filter { legal.contains(it) }
        if (c.isEmpty()) return null
        return c[rnd.nextInt(c.size)]
    }
}

object Engine {
    private class TimeUp : RuntimeException()

    private const val MATE = 100000
    private const val INF = 1000000
    private const val TT_SIZE = 1 shl 19

    private val ttKey = LongArray(TT_SIZE)
    private val ttVal = IntArray(TT_SIZE)
    private val ttMeta = IntArray(TT_SIZE)
    private val Z = LongArray(12 * 64)
    private val CZ = LongArray(4)
    private val EZ = LongArray(8)
    private var SIDE = 0L
    private val PI = IntArray(128)
    private val killers = Array(100) { IntArray(2) }
    private val hist = Array(2) { IntArray(4096) }
    private val mateFail = HashMap<Long, Int>()
    private var deadline = 0L
    private var nodes = 0L
    private var extLimit = 8
    private var mateMove: Move? = null

    init {
        val rnd = java.util.Random(20260101L)
        for (i in Z.indices) Z[i] = rnd.nextLong()
        for (i in CZ.indices) CZ[i] = rnd.nextLong()
        for (i in EZ.indices) EZ[i] = rnd.nextLong()
        SIDE = rnd.nextLong()
        val s = "PNBRQKpnbrqk"
        for (i in s.indices) PI[s[i].code] = i
    }

    private fun tick() {
        nodes++
        if ((nodes and 1023L) == 0L && System.currentTimeMillis() > deadline) throw TimeUp()
    }

    private fun value(c: Char): Int = when (c.lowercaseChar()) {
        'p' -> 100; 'n' -> 320; 'b' -> 330; 'r' -> 500; 'q' -> 900; else -> 0
    }

    private fun promoIdx(c: Char): Int = when (c) {
        'q' -> 1; 'r' -> 2; 'b' -> 3; 'n' -> 4; else -> 0
    }

    private fun code(m: Move): Int = m.from or (m.to shl 6) or (promoIdx(m.promo) shl 12)

    private fun hash(p: Position): Long {
        var h = if (p.white) 0L else SIDE
        val b = p.b
        for (i in 0 until 64) {
            val c = b[i]
            if (c != '.') h = h xor Z[PI[c.code] * 64 + i]
        }
        for (ch in p.castle) {
            val k = when (ch) {
                'K' -> 0; 'Q' -> 1; 'k' -> 2; else -> 3
            }
            h = h xor CZ[k]
        }
        if (p.ep >= 0) h = h xor EZ[p.ep and 7]
        return h
    }

    private fun edge(sq: Int): Int = abs(2 * (sq / 8) - 7) + abs(2 * (sq % 8) - 7)

    private fun shield(b: CharArray, k: Int, white: Boolean): Int {
        val r = k / 8
        val f = k % 8
        var v = 0
        val pc = if (white) 'P' else 'p'
        val d = if (white) -1 else 1
        for (df in -1..1) {
            val nf = f + df
            if (nf < 0 || nf > 7) continue
            val r1 = r + d
            val r2 = r + 2 * d
            if (r1 in 0..7 && b[r1 * 8 + nf] == pc) v += 10
            else if (r2 in 0..7 && b[r2 * 8 + nf] == pc) v += 5
            else v -= 6
        }
        return v
    }

    private fun eval(p: Position): Int {
        val b = p.b
        var s = 0
        val wp = IntArray(8)
        val bp = IntArray(8)
        val bMin = IntArray(8) { 8 }
        val wMax = IntArray(8) { -1 }
        var npm = 0
        var wBish = 0
        var bBish = 0
        var wk = -1
        var bk = -1
        for (sq in 0 until 64) {
            val c = b[sq]
            if (c == '.') continue
            val r = sq / 8
            val f = sq % 8
            when (c) {
                'P' -> {
                    wp[f]++
                    if (r > wMax[f]) wMax[f] = r
                }
                'p' -> {
                    bp[f]++
                    if (r < bMin[f]) bMin[f] = r
                }
                'B' -> { wBish++; npm += 330 }
                'b' -> { bBish++; npm += 330 }
                'N', 'n' -> npm += 320
                'R', 'r' -> npm += 500
                'Q', 'q' -> npm += 900
                'K' -> wk = sq
                'k' -> bk = sq
            }
        }
        val endgame = npm < 2600
        for (sq in 0 until 64) {
            val c = b[sq]
            if (c == '.') continue
            val r = sq / 8
            val f = sq % 8
            val white = c.isUpperCase()
            var v = value(c)
            val cd = edge(sq)
            when (c.lowercaseChar()) {
                'p' -> {
                    val adv = if (white) 6 - r else r - 1
                    v += adv * 6
                    var passed = true
                    for (df in -1..1) {
                        val nf = f + df
                        if (nf < 0 || nf > 7) continue
                        if (white) {
                            if (bMin[nf] < r) passed = false
                        } else {
                            if (wMax[nf] > r) passed = false
                        }
                    }
                    if (passed) v += 12 + adv * adv * 3
                    if (f in 2..5) v += 4
                    val own = if (white) wp else bp
                    if (own[f] > 1) v -= 10
                    val nb = (if (f > 0) own[f - 1] else 0) + (if (f < 7) own[f + 1] else 0)
                    if (nb == 0) v -= 8
                }
                'n' -> v += 28 - cd * 3
                'b' -> v += 18 - cd * 2
                'r' -> {
                    val own = if (white) wp[f] else bp[f]
                    val opp = if (white) bp[f] else wp[f]
                    if (own == 0) v += if (opp == 0) 18 else 9
                    if (if (white) r == 1 else r == 6) v += 15
                }
                'k' -> {
                    if (endgame) v += (14 - cd) * 4
                    else {
                        val adv = if (white) 7 - r else r
                        v -= adv * 18
                        if (f <= 2 || f >= 6) v += 14
                    }
                }
            }
            s += if (white) v else -v
        }
        if (wBish >= 2) s += 30
        if (bBish >= 2) s -= 30
        if (endgame.not()) {
            if (wk >= 0) s += shield(b, wk, true)
            if (bk >= 0) s -= shield(b, bk, false)
        }
        if (endgame && wk >= 0 && bk >= 0) {
            val dist = abs(wk / 8 - bk / 8) + abs(wk % 8 - bk % 8)
            if (s > 200) s += edge(bk) * 4 + (14 - dist) * 3
            else if (s < -200) s -= edge(wk) * 4 + (14 - dist) * 3
        }
        return if (p.white) s else -s
    }

    private fun hasPieces(p: Position): Boolean {
        val w = p.white
        for (i in 0 until 64) {
            val c = p.b[i]
            if (c == '.' || c.isUpperCase() != w) continue
            val l = c.lowercaseChar()
            if (l == 'n' || l == 'b' || l == 'r' || l == 'q') return true
        }
        return false
    }

    private fun moveScore(p: Position, m: Move, ttMove: Int, ply: Int): Int {
        val cd = code(m)
        if (cd == ttMove) return 1000000
        val t = p.b[m.to]
        var s = 0
        if (t != '.') s = 100000 + value(t) * 10 - value(p.b[m.from]) / 10
        else if (p.b[m.from].lowercaseChar() == 'p' && m.to == p.ep) s = 100900
        if (m.promo == 'q') s += 90000
        if (s == 0) {
            if (ply < 99 && killers[ply][0] == cd) s = 90000
            else if (ply < 99 && killers[ply][1] == cd) s = 80000
            else s = hist[if (p.white) 0 else 1][m.from * 64 + m.to]
        }
        return s
    }

    private fun qsearch(p: Position, alpha0: Int, beta: Int, qd: Int): Int {
        tick()
        val stand = eval(p)
        if (stand >= beta) return stand
        var alpha = if (stand > alpha0) stand else alpha0
        if (qd <= 0) return alpha
        if (stand + 1100 < alpha) return alpha
        val caps = ArrayList<Move>()
        for (m in p.pseudo(p.white)) {
            val t = p.b[m.to]
            if (t != '.' || m.promo == 'q' || (m.to == p.ep && p.b[m.from].lowercaseChar() == 'p')) caps.add(m)
        }
        caps.sortByDescending { moveScore(p, it, 0, 99) }
        for (m in caps) {
            val c = p.make(m)
            if (c.inCheck(p.white)) continue
            val s = -qsearch(c, -beta, -alpha, qd - 1)
            if (s >= beta) return s
            if (s > alpha) alpha = s
        }
        return alpha
    }

    private fun search(p: Position, depth0: Int, alpha0: Int, beta: Int, ply: Int, nullOk: Boolean): Int {
        tick()
        var depth = depth0
        var alpha = alpha0
        val inChk = p.inCheck(p.white)
        if (inChk && ply < extLimit) depth++
        if (depth <= 0) return qsearch(p, alpha, beta, 4)

        val h = hash(p)
        val idx = (h and (TT_SIZE - 1).toLong()).toInt()
        var ttMove = 0
        if (ttKey[idx] == h) {
            val meta = ttMeta[idx]
            ttMove = meta shr 10
            if ((meta and 0xFF) >= depth) {
                var sc = ttVal[idx]
                if (sc > MATE - 1000) sc -= ply else if (sc < -MATE + 1000) sc += ply
                val fl = (meta shr 8) and 3
                if (fl == 0) return sc
                if (fl == 1 && sc >= beta) return sc
                if (fl == 2 && sc <= alpha) return sc
            }
        }

        if (nullOk && inChk.not() && depth >= 3 && beta < MATE - 1000 && hasPieces(p) && eval(p) >= beta) {
            val np = Position(p.b, p.white.not(), p.castle, -1)
            val s = -search(np, depth - 3, -beta, -beta + 1, ply + 1, false)
            if (s >= beta && s < MATE - 1000) return beta
        }

        val moves = p.pseudo(p.white)
        val n = moves.size
        val sc = IntArray(n)
        for (i in 0 until n) sc[i] = moveScore(p, moves[i], ttMove, ply)
        var legal = 0
        var best = -INF
        var bestCode = 0
        var flag = 2
        for (i in 0 until n) {
            var bi = i
            for (j in i + 1 until n) if (sc[j] > sc[bi]) bi = j
            if (bi != i) {
                val tm = moves[i]; moves[i] = moves[bi]; moves[bi] = tm
                val ts = sc[i]; sc[i] = sc[bi]; sc[bi] = ts
            }
            val m = moves[i]
            val c = p.make(m)
            if (c.inCheck(p.white)) continue
            legal++
            val quiet = p.b[m.to] == '.' && m.promo == ' '
            var s: Int
            if (legal == 1) {
                s = -search(c, depth - 1, -beta, -alpha, ply + 1, true)
            } else {
                var red = 0
                if (legal > 4 && depth >= 3 && quiet && inChk.not() && c.inCheck(c.white).not()) {
                    red = if (legal > 10 && depth >= 5) 2 else 1
                }
                s = -search(c, depth - 1 - red, -alpha - 1, -alpha, ply + 1, true)
                if (s > alpha && red > 0) s = -search(c, depth - 1, -alpha - 1, -alpha, ply + 1, true)
                if (s > alpha && s < beta) s = -search(c, depth - 1, -beta, -alpha, ply + 1, true)
            }
            if (s > best) {
                best = s
                bestCode = code(m)
            }
            if (s > alpha) {
                alpha = s
                flag = 0
            }
            if (alpha >= beta) {
                flag = 1
                if (quiet) {
                    val cd = code(m)
                    if (ply < 99 && killers[ply][0] != cd) {
                        killers[ply][1] = killers[ply][0]
                        killers[ply][0] = cd
                    }
                    val hi = if (p.white) 0 else 1
                    val nv = hist[hi][m.from * 64 + m.to] + depth * depth
                    hist[hi][m.from * 64 + m.to] = if (nv > 70000) 70000 else nv
                }
                break
            }
        }
        if (legal == 0) return if (inChk) -MATE + ply else 0
        var store = best
        if (store > MATE - 1000) store += ply else if (store < -MATE + 1000) store -= ply
        ttKey[idx] = h
        ttVal[idx] = store
        ttMeta[idx] = (depth and 0xFF) or (flag shl 8) or (bestCode shl 10)
        return best
    }

    /**
     * Поиск форсированного мата: сторона на ходу ходит ТОЛЬКО шахами, защитник перебирает все ответы.
     * mateFail запоминает позиции, где мата за n ходов нет (кэш живёт между ходами партии).
     */
    private fun mateIn(p: Position, n: Int, root: Boolean): Boolean {
        tick()
        var h = 0L
        if (root.not()) {
            h = hash(p)
            val f = mateFail[h]
            if (f != null && f >= n) return false
        }
        for (m in p.legal()) {
            val c = p.make(m)
            if (c.inCheck(c.white).not()) continue
            val replies = c.legal()
            if (replies.isEmpty()) {
                if (root) mateMove = m
                return true
            }
            if (n <= 1) continue
            var all = true
            val ordered = replies.sortedByDescending { if (c.b[it.to] != '.') 1 else 0 }
            for (r in ordered) {
                if (mateIn(c.make(r), n - 1, false).not()) {
                    all = false
                    break
                }
            }
            if (all) {
                if (root) mateMove = m
                return true
            }
        }
        if (root.not()) {
            if (mateFail.size > 400000) mateFail.clear()
            mateFail[h] = n
        }
        return false
    }

    /**
     * depth — максимальная глубина, timeMs — лимит времени (итеративное углубление, по таймеру поиск обрывается
     * и возвращается лучший ход последней завершённой/частичной итерации).
     * Сначала всегда ищется форсированный мат (приоритет над материалом):
     *   гроссмейстер — до 15 ходов, до 40% времени; блиц — до 3 ходов, до 10% времени.
     * grandmaster=true: ещё и дебютная книга.
     */
    fun think(pos: Position, depth: Int, timeMs: Long, grandmaster: Boolean): EngineResult {
        val t0 = System.currentTimeMillis()
        val legal = pos.legal()
        if (legal.isEmpty()) return EngineResult(null, 0, 0, false)
        if (legal.size == 1) return EngineResult(legal[0], 0, 0, false)

        if (grandmaster) {
            val bm = Book.pick(pos)
            if (bm != null) return EngineResult(bm, 0, 0, true)
        }

        // 1) форсированный мат
        val mateMax = if (grandmaster) 15 else 3
        val mateFrac = if (grandmaster) 40L else 10L
        nodes = 0
        deadline = t0 + timeMs * mateFrac / 100
        try {
            for (n in 1..mateMax) {
                mateMove = null
                if (mateIn(pos, n, true)) {
                    val mm = mateMove
                    if (mm != null) return EngineResult(mm, n, n * 2 - 1, false)
                }
            }
        } catch (e: TimeUp) {
        }

        // 2) обычный поиск (итеративное углубление + PVS)
        nodes = 0
        deadline = t0 + timeMs
        for (k in killers) {
            k[0] = 0
            k[1] = 0
        }
        for (hh in hist) java.util.Arrays.fill(hh, 0)
        var ms: List<Move> = legal.sortedByDescending { moveScore(pos, it, 0, 99) }
        var best = ms[0]
        var bestScore = 0
        var reached = 0
        for (d in 1..depth) {
            extLimit = 2 * d + 2
            var cur: Move? = null
            var alpha = -INF
            var complete = false
            try {
                for (m in ms) {
                    val c = pos.make(m)
                    var s: Int
                    if (cur == null) {
                        s = -search(c, d - 1, -INF, INF, 1, true)
                    } else {
                        s = -search(c, d - 1, -alpha - 1, -alpha, 1, true)
                        if (s > alpha) s = -search(c, d - 1, -INF, -alpha, 1, true)
                    }
                    if (cur == null || s > alpha) {
                        alpha = s
                        cur = m
                    }
                }
                complete = true
            } catch (e: TimeUp) {
            }
            val cc = cur
            if (cc != null) {
                best = cc
                bestScore = alpha
                ms = listOf(cc) + ms.filter { it != cc }
            }
            if (complete) reached = d else break
            if (bestScore >= MATE - 300) break
            if (System.currentTimeMillis() - t0 > timeMs * 65 / 100) break
        }
        val mate = if (bestScore >= MATE - 300) (MATE - bestScore + 1) / 2 else 0
        return EngineResult(best, mate, reached, false)
    }
}
