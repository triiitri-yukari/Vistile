package com.mints.vistile

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** One tile on a sheet: the representative frame time and the range it stands for. */
data class Tile(val time: Double, val start: Double, val end: Double)

/**
 * Budgeted coverage segmentation (see prototype/timeline.py for the reference).
 *
 * Split the analysed window into <= K contiguous segments so every sample is
 * close to its segment's representative. Greedy top-down: split the segment
 * with the highest priority = capped worst-case deviation x sqrt(duration).
 * Static stretches collapse into one long-range tile, bursts and brief events
 * get their own tiles, and a split only happens where something is not yet
 * represented, so near-identical tiles don't appear.
 */
object Segmenter {
    private const val TOPK = 24
    private const val D0 = 6f          // "represented well enough" -> stop early
    private const val MERGE_EPS = 3f   // adjacent reps closer than this are merged
    private const val CAP = 25f        // deviation beyond this is simply "different"

    /** Global + local (top-k most-changed cells) + colour difference. */
    fun dist(A: Analysis, i: Int, j: Int): Float {
        val a = A.lum[i]; val b = A.lum[j]
        val top = FloatArray(TOPK); var topMin = 0f; var topMinIdx = 0
        var sum = 0f
        for (k in a.indices) {
            val d = abs(a[k] - b[k])
            sum += d
            if (d > topMin) {       // replace the smallest of the current top-k
                top[topMinIdx] = d
                topMin = top[0]; topMinIdx = 0
                for (q in 1 until TOPK) if (top[q] < topMin) { topMin = top[q]; topMinIdx = q }
            }
        }
        var ts = 0f; for (v in top) ts += v
        val ca = A.col[i]; val cb = A.col[j]
        var cs = 0f; for (k in ca.indices) cs += abs(ca[k] - cb[k])
        return 0.5f * sum / a.size + 0.5f * ts / TOPK + 0.5f * cs / ca.size
    }

    private class Seg(A: Analysis, val a: Int, val b: Int) {
        val rep: Int
        val dev: FloatArray
        val maxdev: Float
        var prio: Double? = null
        var run: Pair<Int, Int>? = null

        init {
            val dim = A.lum[0].size
            val mean = FloatArray(dim)
            for (i in a until b) { val l = A.lum[i]; for (k in 0 until dim) mean[k] += l[k] }
            for (k in 0 until dim) mean[k] /= (b - a)
            val dm = FloatArray(b - a) { idx ->
                val l = A.lum[a + idx]; var s = 0f
                for (k in 0 until dim) { val d = l[k] - mean[k]; s += d * d }
                s
            }
            // representative: the most detailed frame among the 30% closest to the mean
            val thr = dm.sorted()[((b - a - 1) * 0.3).toInt()] + 1e-6f
            var best = -1; var bestDet = -1f
            for (idx in dm.indices) if (dm[idx] <= thr && A.detail[a + idx] > bestDet) { bestDet = A.detail[a + idx]; best = idx }
            rep = a + best
            dev = FloatArray(b - a) { dist(A, rep, a + it) }
            maxdev = dev.max()
        }
    }

    private fun bestSplit(A: Analysis, s: Seg): Pair<Seg, Seg>? {
        val a = s.a; val b = s.b; val n = b - a
        if (n < 2) return null
        val dim = A.lum[0].size
        val cands = HashSet<Int>()
        // candidate: SSE-optimal split, incremental O(n*dim) with running sums
        val T = FloatArray(dim); var totSq = 0.0
        for (i in a until b) { val l = A.lum[i]; for (k in 0 until dim) { T[k] += l[k]; totSq += l[k] * l[k] } }
        var tt = 0.0; for (k in 0 until dim) tt += T[k].toDouble() * T[k]
        val S = FloatArray(dim); var ss = 0.0; var ts = 0.0; var sq = 0.0
        var bestK = a + 1; var bestV = Double.MAX_VALUE
        for (k in 1 until n) {
            val x = A.lum[a + k - 1]
            var sx = 0.0; var tx = 0.0; var xx = 0.0
            for (q in 0 until dim) { sx += S[q] * x[q]; tx += T[q] * x[q]; xx += x[q] * x[q] }
            ss += 2 * sx + xx; ts += tx; sq += xx
            for (q in 0 until dim) S[q] += x[q]
            val sseL = sq - ss / k
            val rr = tt - 2 * ts + ss
            val sseR = (totSq - sq) - rr / (n - k)
            if (sseL + sseR < bestV) { bestV = sseL + sseR; bestK = a + k }
        }
        cands.add(bestK)
        // candidates: isolate the worst-represented sample
        var w = 0; for (i in s.dev.indices) if (s.dev[i] > s.dev[w]) w = i
        cands.add(a + w); cands.add(a + w + 1)
        // candidates: the three biggest frame-to-frame jumps
        (a + 1 until b).sortedByDescending { A.jump[it] }.take(3).forEach { cands.add(it) }

        // same objective as the greedy loop: minimise the larger half's priority,
        // raw worst deviation as tie-break
        var best: Pair<Seg, Seg>? = null
        var bestP = Double.MAX_VALUE; var bestD = Float.MAX_VALUE
        for (c in cands) if (c in (a + 1) until b) {
            val l = Seg(A, a, c); val r = Seg(A, c, b)
            val p = maxOf(priority(A, l), priority(A, r)); val d = maxOf(l.maxdev, r.maxdev)
            if (p < bestP - 1e-9 || (p < bestP + 1e-9 && d < bestD)) { bestP = p; bestD = d; best = l to r }
        }
        return best
    }

    /**
     * A brief event = two big frame-to-frame jumps (in and out) 0.25-2 s apart inside the
     * segment. Jumps must be exceptional locally and for the whole video, so cut-heavy
     * footage (music videos, vlogs) doesn't turn every shot into an "event".
     */
    private fun outlierRun(A: Analysis, s: Seg): Pair<Int, Int>? {
        if (s.b - s.a < 3) return null
        val js = A.jump.copyOfRange(s.a + 1, s.b)
        val med = js.sorted()[js.size / 2]
        val thr = maxOf(CAP, 4 * med, A.jumpP98)
        var best: Pair<Int, Int>? = null; var strength = 0f
        var prev = -1
        for (i in s.a + 1 until s.b) {
            if (A.jump[i] <= thr) continue
            if (prev >= 0) {
                val d = A.t[i] - A.t[prev]
                val st = min(A.jump[prev], A.jump[i])
                if (d in 0.25..2.0 && st > strength) { strength = st; best = prev to i }
            }
            prev = i
        }
        return best
    }

    private fun priority(A: Analysis, s: Seg): Double {
        s.prio?.let { return it }
        val step = if (A.n > 1) A.t[1] - A.t[0] else 0.0
        var dur = A.t[s.b - 1] - A.t[s.a] + step
        s.run = outlierRun(A, s)
        if (s.run != null) dur = maxOf(dur, A.dur / 8)   // an unshown brief event matters however short
        return (min(s.maxdev, CAP) / CAP * sqrt(dur / A.dur)).also { s.prio = it }
    }

    /** Finest temporal resolution the overview may use for busy content: T/40, within [lo, 60 s]. */
    fun autoMinDur(A: Analysis, lo: Double = 1.5) = minOf(60.0, maxOf(lo, A.dur / 40))

    /**
     * Split until every range is represented (maxdev < D0) or too short to be worth
     * another tile, or until K ranges. The tile count is decided by the content; K is
     * only a ceiling. Small changes (noise, a ticking digit) must span longer before
     * they earn another tile; clearly different content splits down to minDur.
     */
    /** A change this clear is worth a tile when filling spare slots (camera noise is ~13, a ticking digit ~20). */
    private const val FILL_DEV = 15f

    /**
     * @param fill optional second pass ("Fill sheets"): the images in use cost the same however
     *   full they are, so after the normal selection keep adding tiles until the last sheet is full
     *   (never adding a sheet, never exceeding K), ignoring minDur, but only where a clearly visible
     *   change (>= FILL_DEV) is still unrepresented. Static video therefore keeps its few large tiles.
     */
    fun segment(A: Analysis, K: Int, minDur: Double = autoMinDur(A), fill: Boolean = false, perSheet: Int = 16): List<Tile> {
        A.jump = FloatArray(A.n) { if (it == 0) 0f else dist(A, it - 1, it) }
        A.jumpP98 = A.jump.sorted().let { it[min(it.size - 1, (it.size * 0.98).toInt())] }
        val segs = mutableListOf(Seg(A, 0, A.n))
        val step = if (A.n > 1) A.t[1] - A.t[0] else 0.0

        /** Split the highest-priority range among those accepted by [ok]; false when none qualifies. */
        fun splitBest(limit: Int, ok: (Seg) -> Boolean): Boolean {
            val i = segs.indices.filter { segs[it].b - segs[it].a >= 2 && ok(segs[it]) }
                .maxByOrNull { priority(A, segs[it]) } ?: return false
            val run = segs[i].run
            if (run != null) {   // isolate the whole brief event at once (budget permitting)
                val a = segs[i].a; val b = segs[i].b
                var parts = listOf(a to run.first, run, run.second to b).filter { it.second > it.first }
                if (segs.size + parts.size - 1 > limit)
                    parts = if (run.first > a) listOf(a to run.first, run.first to b) else listOf(a to run.second, run.second to b)
                segs.removeAt(i); segs.addAll(i, parts.map { Seg(A, it.first, it.second) })
                return true
            }
            val sp = bestSplit(A, segs[i]) ?: return false
            segs.removeAt(i); segs.add(i, sp.second); segs.add(i, sp.first)
            return true
        }

        // pass 1: as many tiles as the content needs, at most K
        while (segs.size < K && splitBest(K) { sg ->
                if (sg.maxdev < D0) false else {
                    priority(A, sg)   // also computes sg.run
                    val dur = A.t[sg.b - 1] - A.t[sg.a] + step
                    dur >= 2 * minDur * CAP / min(sg.maxdev, CAP) || sg.run != null
                }
            }) { /* keep splitting */ }

        // pass 2 (optional): fill the sheets already in use with clearly visible changes
        if (fill) {
            val fillTo = min(K, perSheet * ((segs.size + perSheet - 1) / perSheet))
            while (segs.size < fillTo && splitBest(fillTo) { sg -> sg.maxdev >= FILL_DEV }) { /* keep filling */ }
        }

        val out = mutableListOf(segs[0])
        for (s in segs.drop(1)) {
            val p = out.last()
            if (dist(A, p.rep, s.rep) < MERGE_EPS) out[out.size - 1] = Seg(A, p.a, s.b) else out.add(s)
        }
        return out.map { s ->
            Tile(A.t[s.rep], if (s.a == 0) A.t0 else A.t[s.a], if (s.b < A.n) A.t[s.b] else A.t1)
        }
    }
}
