package com.example.chessoverlay

import kotlin.math.abs

const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
private val KN = arrayOf(
    intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(-1, 2), intArrayOf(-2, 1),
    intArrayOf(1, -2), intArrayOf(2, -1), intArrayOf(-1, -2), intArrayOf(-2, -1)
)
private val VAL = intArrayOf(0, 100, 320, 330, 500, 900, 0)

fun sqName(s: Int) = "${'a' + s % 8}${'1' + s / 8}"

/** b[sq]: 0 empty, +1..+6 white P,N,B,R,Q,K, negative = black. sq = rank*8+file, a1 = 0. */
class Pos(val b: IntArray, val side: Int, val castle: Int, val ep: Int) {

    companion object {
        fun fromFen(fen: String): Pos? {
            try {
                val p = fen.trim().split(Regex("\\s+"))
                val rows = p[0].split("/")
                if (rows.size != 8) return null
                val b = IntArray(64)
                for ((i, row) in rows.withIndex()) {
                    var f = 0
                    val r = 7 - i
                    for (c in row) {
                        if (c.isDigit()) {
                            f += c - '0'
                        } else {
                            val t = "pnbrqk".indexOf(c.lowercaseChar()) + 1
                            if (t == 0 || f > 7) return null
                            b[r * 8 + f] = if (c.isUpperCase()) t else -t
                            f++
                        }
                    }
                    if (f != 8) return null
                }
                if (b.count { it == 6 } != 1 || b.count { it == -6 } != 1) return null
                val side = if (p.getOrElse(1) { "w" } == "b") -1 else 1
                val cs = p.getOrElse(2) { "-" }
                var c = 0
                if ('K' in cs) c = c or 1
                if ('Q' in cs) c = c or 2
                if ('k' in cs) c = c or 4
                if ('q' in cs) c = c or 8
                val e = p.getOrElse(3) { "-" }
                val ep = if (e.length == 2) (e[1] - '1') * 8 + (e[0] - 'a') else -1
                return Pos(b, side, c, ep)
            } catch (e: Exception) {
                return null
            }
        }
    }

    fun kingSq(c: Int): Int {
        for (i in 0..63) if (b[i] == 6 * c) return i
        return -1
    }

    fun attacked(sq: Int, by: Int): Boolean {
        val r = sq / 8
        val f = sq % 8
        val pr = r - by
        if (pr in 0..7) {
            if (f > 0 && b[pr * 8 + f - 1] == by) return true
            if (f < 7 && b[pr * 8 + f + 1] == by) return true
        }
        for (d in KN) {
            val rr = r + d[0]
            val ff = f + d[1]
            if (rr in 0..7 && ff in 0..7 && b[rr * 8 + ff] == 2 * by) return true
        }
        for (dr in -1..1) for (df in -1..1) {
            if (dr == 0 && df == 0) continue
            var rr = r + dr
            var ff = f + df
            var first = true
            while (rr in 0..7 && ff in 0..7) {
                val p = b[rr * 8 + ff]
                if (p != 0) {
                    if (p * by > 0) {
                        val t = abs(p)
                        if (t == 5) return true
                        if (t == 6 && first) return true
                        if (t == 3 && dr != 0 && df != 0) return true
                        if (t == 4 && (dr == 0 || df == 0)) return true
                    }
                    break
                }
                first = false
                rr += dr
                ff += df
            }
        }
        return false
    }

    fun inCheck() = attacked(kingSq(side), -side)

    private fun pawnMove(out: ArrayList<Int>, from: Int, to: Int) {
        val tr = to / 8
        if (tr == 0 || tr == 7) {
            for (pr in 2..5) out.add(from or (to shl 6) or (pr shl 12))
        } else {
            out.add(from or (to shl 6))
        }
    }

    /** Pseudo-legal moves. Move = from | to<<6 | promo<<12 (promo 2..5 = N,B,R,Q). */
    fun moves(): List<Int> {
        val out = ArrayList<Int>(48)
        for (sq in 0..63) {
            val p = b[sq]
            if (p * side <= 0) continue
            val t = abs(p)
            val r = sq / 8
            val f = sq % 8
            when (t) {
                1 -> {
                    val nr = r + side
                    if (nr in 0..7) {
                        if (b[nr * 8 + f] == 0) {
                            pawnMove(out, sq, nr * 8 + f)
                            val start = if (side == 1) 1 else 6
                            if (r == start && b[(r + 2 * side) * 8 + f] == 0)
                                out.add(sq or (((r + 2 * side) * 8 + f) shl 6))
                        }
                        for (df in intArrayOf(-1, 1)) {
                            val nf = f + df
                            if (nf !in 0..7) continue
                            val to = nr * 8 + nf
                            if (b[to] * side < 0 || to == ep) pawnMove(out, sq, to)
                        }
                    }
                }
                2 -> for (d in KN) {
                    val rr = r + d[0]
                    val ff = f + d[1]
                    if (rr in 0..7 && ff in 0..7 && b[rr * 8 + ff] * side <= 0)
                        out.add(sq or ((rr * 8 + ff) shl 6))
                }
                else -> {
                    for (dr in -1..1) for (df in -1..1) {
                        if (dr == 0 && df == 0) continue
                        val diag = dr != 0 && df != 0
                        if (t == 3 && !diag) continue
                        if (t == 4 && diag) continue
                        var rr = r + dr
                        var ff = f + df
                        while (rr in 0..7 && ff in 0..7) {
                            val q = b[rr * 8 + ff]
                            if (q * side > 0) break
                            out.add(sq or ((rr * 8 + ff) shl 6))
                            if (q != 0 || t == 6) break
                            rr += dr
                            ff += df
                        }
                    }
                    if (t == 6) {
                        val base = if (side == 1) 0 else 56
                        if (sq == base + 4 && !attacked(sq, -side)) {
                            val ks = if (side == 1) 1 else 4
                            val qs = if (side == 1) 2 else 8
                            if ((castle and ks) != 0 && b[base + 5] == 0 && b[base + 6] == 0 &&
                                b[base + 7] == 4 * side &&
                                !attacked(base + 5, -side) && !attacked(base + 6, -side)
                            ) out.add(sq or ((base + 6) shl 6))
                            if ((castle and qs) != 0 && b[base + 1] == 0 && b[base + 2] == 0 &&
                                b[base + 3] == 0 && b[base] == 4 * side &&
                                !attacked(base + 3, -side) && !attacked(base + 2, -side)
                            ) out.add(sq or ((base + 2) shl 6))
                        }
                    }
                }
            }
        }
        return out
    }

    private fun mask(s: Int) = when (s) {
        0 -> 2; 4 -> 3; 7 -> 1; 56 -> 8; 60 -> 12; 63 -> 4; else -> 0
    }

    fun make(m: Int): Pos {
        val from = m and 63
        val to = (m shr 6) and 63
        val promo = m shr 12
        val nb = b.copyOf()
        val p = nb[from]
        val t = abs(p)
        var nep = -1
        nb[from] = 0
        if (t == 1 && to == ep && b[to] == 0 && from % 8 != to % 8) nb[to - 8 * side] = 0
        nb[to] = if (promo != 0) promo * side else p
        if (t == 1 && abs(to - from) == 16) nep = (from + to) / 2
        if (t == 6 && abs(to - from) == 2) {
            if (to > from) { nb[to - 1] = nb[to + 1]; nb[to + 1] = 0 }
            else { nb[to + 1] = nb[to - 2]; nb[to - 2] = 0 }
        }
        val nc = castle and (mask(from) or mask(to)).inv()
        return Pos(nb, -side, nc, nep)
    }

    fun legal(): List<Int> = moves().filter {
        val n = make(it)
        !n.attacked(n.kingSq(side), -side)
    }

    /** Static evaluation in centipawns, white's point of view. */
    fun eval(): Int {
        var s = 0
        for (i in 0..63) {
            val p = b[i]
            if (p == 0) continue
            val t = abs(p)
            val c = if (p > 0) 1 else -1
            val r = i / 8
            val f = i % 8
            val adv = if (c == 1) r else 7 - r
            val cd = abs(2 * f - 7) + abs(2 * r - 7)
            var v = VAL[t]
            when (t) {
                1 -> v += adv * 6 + (14 - cd) / 2
                2, 3 -> v += (14 - cd) * 3
                4 -> if (adv == 6) v += 15
                5 -> v += (14 - cd)
                6 -> if (adv == 0) v += 12
            }
            s += c * v
        }
        return s
    }
}

class Engine {
    private val MATE = 100000
    private var deadline = 0L
    private var nodes = 0
    private var stop = false
    private var noise = 0
    private val rnd = java.util.Random()

    /** Returns Pair(move, score from side-to-move view) or null if no legal moves. */
    fun search(root: Pos, maxDepth: Int, timeMs: Long, noise: Int): Pair<Int, Int>? {
        this.noise = noise
        deadline = System.currentTimeMillis() + timeMs
        stop = false
        nodes = 0
        var moves = order(root, root.legal())
        if (moves.isEmpty()) return null
        var best = moves[0]
        var bestScore = 0
        for (d in 1..maxDepth) {
            var alpha = -MATE - 1
            var curBest = moves[0]
            var curScore = -MATE - 1
            for (m in moves) {
                val s = -negamax(root.make(m), d - 1, -MATE - 1, -alpha, 1)
                if (stop) break
                if (s > curScore) { curScore = s; curBest = m }
                if (s > alpha) alpha = s
            }
            if (stop && d > 1) break
            best = curBest
            bestScore = curScore
            if (stop) break
            moves = listOf(best) + moves.filter { it != best }
            if (abs(bestScore) > MATE - 100) break
        }
        return Pair(best, bestScore)
    }

    private fun order(p: Pos, ms: List<Int>) = ms.sortedByDescending { m ->
        val v = abs(p.b[(m shr 6) and 63])
        val a = abs(p.b[m and 63])
        (if (v > 0) 10 * VAL[v] - VAL[a] + 1000 else 0) + (if ((m shr 12) != 0) 900 else 0)
    }

    private fun timeUp(): Boolean {
        if ((++nodes and 2047) == 0 && System.currentTimeMillis() > deadline) stop = true
        return stop
    }

    private fun negamax(p: Pos, depth0: Int, alpha0: Int, beta: Int, ply: Int): Int {
        if (timeUp()) return 0
        var depth = depth0
        var alpha = alpha0
        val check = p.inCheck()
        if (check && ply < 12) depth++
        if (depth <= 0) return quiesce(p, alpha, beta, ply)
        val ms = order(p, p.legal())
        if (ms.isEmpty()) return if (check) -MATE + ply else 0
        for (m in ms) {
            val s = -negamax(p.make(m), depth - 1, -beta, -alpha, ply + 1)
            if (stop) return 0
            if (s >= beta) return beta
            if (s > alpha) alpha = s
        }
        return alpha
    }

    private fun quiesce(p: Pos, alpha0: Int, beta: Int, ply: Int): Int {
        if (timeUp()) return 0
        var alpha = alpha0
        val stand = p.eval() * p.side + (if (noise > 0) rnd.nextInt(2 * noise + 1) - noise else 0)
        if (stand >= beta) return beta
        if (ply > 24) return stand
        if (stand > alpha) alpha = stand
        val caps = p.moves().filter {
            val to = (it shr 6) and 63
            p.b[to] != 0 || (it shr 12) != 0 || (to == p.ep && abs(p.b[it and 63]) == 1)
        }
        for (m in order(p, caps)) {
            val n = p.make(m)
            if (n.attacked(n.kingSq(p.side), -p.side)) continue
            val s = -quiesce(n, -beta, -alpha, ply + 1)
            if (stop) return 0
            if (s >= beta) return beta
            if (s > alpha) alpha = s
        }
        return alpha
    }
}
