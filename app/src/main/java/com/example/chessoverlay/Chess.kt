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
                    // белые идут вверх (row уменьшается), чёрные вниз (row растёт)
                    val dir = if (side) -1 else 1
                    val startRow = if (side) 6 else 1
                    val lastRow = if (side) 0 else 7
                    val r1 = r + dir
                    if (r1 in 0..7) {
                        // прямо — только на пустую клетку
                        if (b[r1 * 8 + f] == '.') {
                            addPawn(res, sq, r1 * 8 + f, r1 == lastRow)
                            val r2 = r + 2 * dir
                            if (r == startRow && b[r2 * 8 + f] == '.') res.add(Move(sq, r2 * 8 + f))
                        }
                        // по диагонали — только взятие (или en passant)
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

object Engine {
    private fun value(c: Char): Int = when (c.lowercaseChar()) {
        'p' -> 100; 'n' -> 320; 'b' -> 330; 'r' -> 500; 'q' -> 900; else -> 0
    }

    private fun eval(p: Position): Int {
        var s = 0
        for (sq in 0 until 64) {
            val c = p.b[sq]
            if (c == '.') continue
            val r = sq / 8
            val f = sq % 8
            var v = value(c)
            val l = c.lowercaseChar()
            if (l == 'p' || l == 'n' || l == 'b') {
                val dist = abs(r - 3.5) + abs(f - 3.5)
                v += (10 - dist * 3).toInt()
            }
            if (l == 'p') v += if (c.isUpperCase()) (6 - r) * 4 else (r - 1) * 4
            s += if (c.isUpperCase()) v else -v
        }
        return if (p.white) s else -s
    }

    private fun order(p: Position, moves: List<Move>): List<Move> =
        moves.sortedByDescending { m ->
            val t = p.b[m.to]
            (if (t != '.') value(t) * 10 - value(p.b[m.from]) else 0) + (if (m.promo == 'q') 800 else 0)
        }

    private fun search(p: Position, d: Int, alpha0: Int, beta: Int, ply: Int): Int {
        if (d <= 0) return eval(p)
        val ms = p.legal()
        if (ms.isEmpty()) return if (p.inCheck(p.white)) -100000 + ply else 0
        var alpha = alpha0
        for (m in order(p, ms)) {
            val s = -search(p.make(m), d - 1, -beta, -alpha, ply + 1)
            if (s >= beta) return beta
            if (s > alpha) alpha = s
        }
        return alpha
    }

    fun best(pos: Position, depth: Int): Move? {
        val ms = order(pos, pos.legal())
        if (ms.isEmpty()) return null
        var bestM: Move? = null
        var alpha = -1000000
        for (m in ms) {
            val s = -search(pos.make(m), depth - 1, -1000000, -alpha, 1)
            if (bestM == null || s > alpha) {
                alpha = s
                bestM = m
            }
        }
        return bestM
    }
}
