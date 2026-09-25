package com.mints.vistile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

fun fmt(sec: Double, dec: Int = 1): String {
    val s = max(0.0, sec)
    val m = floor(s / 60).toInt()
    val r = s - m * 60
    return if (dec == 0) "%d:%02d".format(m, r.toInt()) else "%d:%0${3 + dec}.${dec}f".format(m, r)
}

/** Timestamp precision for a window: finer for short zoom windows. */
fun timeDec(span: Double) = if (span < 15) 2 else 1
fun rangeDec(span: Double) = if (span < 60) 1 else 0

/**
 * Draws a contact sheet: title, activity strip (visual change over time with tile
 * markers and range boundaries), and a grid of tiles each labelled with
 * ID, frame time, the time range it stands for, and a position bar.
 */
object SheetRenderer {
    private const val SHEET = 1536   // fits Claude's ~1568px long edge; fine for GPT/Gemini too
    private const val PAD = 6
    private const val BAND = 30

    fun frames(ctx: Context, uri: Uri, tiles: List<Tile>, maxSide: Int): List<Bitmap?> {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(ctx, uri)
        val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 720
        val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
        val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val (vw, vh) = if (rot % 180 == 0) w to h else h to w
        val sc = min(1.0, maxSide.toDouble() / max(vw, vh))
        val out = tiles.map {
            val us = (it.time * 1e6).toLong()
            mmr.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, (vw * sc).toInt(), (vh * sc).toInt())
                ?: mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
        mmr.release()
        return out
    }

    /** Split n tiles into the fewest sheets of <= perSheet, as evenly as possible. */
    fun paginate(n: Int, perSheet: Int = PER_SHEET): List<IntRange> {
        val k = max(1, ceil(n / perSheet.toDouble()).toInt())
        var a = 0
        return (0 until k).map { i -> val sz = n / k + if (i < n % k) 1 else 0; (a until a + sz).also { a += sz } }
    }

    const val PER_SHEET = 16

    /**
     * Renders one sheet showing [tiles] (a page of [allTiles] starting at index [first]).
     * The activity strip always spans the whole window, with this page's part outlined.
     */
    fun render(
        A: Analysis, tiles: List<Tile>, frames: List<Bitmap?>, ids: List<String>, title: String,
        allTiles: List<Tile> = tiles, allIds: List<String> = ids, first: Int = 0,
    ): Bitmap {
        val n = tiles.size
        val f0 = frames.firstOrNull { it != null }
        val ar = if (f0 != null) f0.height.toDouble() / f0.width else 1.0
        val head = 34 + 72
        fun tileW(c: Int): Double {
            val r = ceil(n / c.toDouble())
            val byW = (SHEET - PAD * (c + 1)) / c.toDouble()
            val byH = (SHEET - head - PAD - r * (BAND + PAD)) / r / ar
            return min(byW, byH)
        }
        val cols = (1..n).maxBy { tileW(it) }
        val rows = ceil(n / cols.toDouble()).toInt()
        val tw = tileW(cols).toInt()
        val th = (tw * ar).toInt()
        val sw = max(900, cols * tw + PAD * (cols + 1))
        val sh = head + rows * (th + BAND + PAD) + PAD
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(18, 18, 22))
        val mono = Typeface.MONOSPACE
        val monoB = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
            p.typeface = if (bold) monoB else mono; p.textSize = size; p.color = color; p.style = Paint.Style.FILL
            c.drawText(s, x, y, p)
        }
        fun rect(l: Float, t: Float, r: Float, b: Float, color: Int) {
            p.color = color; p.style = Paint.Style.FILL; c.drawRect(l, t, r, b, p)
        }
        fun textW(s: String, size: Float, bold: Boolean = false): Float {
            p.typeface = if (bold) monoB else mono; p.textSize = size; return p.measureText(s)
        }

        var ts = 19f   // shrink the title until it fits the sheet width
        while (ts > 11f && textW(title, ts, true) > sw - 2 * PAD - 8) ts -= 1f
        text(title, PAD + 4f, 25f, ts, Color.rgb(235, 235, 235), true)
        val w0 = A.t0; val w1 = A.t1; val span = max(1e-6, w1 - w0)
        val dec = timeDec(span); val rdec = rangeDec(span)
        fun X(x0: Float, x1: Float, t: Double) = (x0 + (x1 - x0) * ((t - w0) / span)).toFloat()

        // activity strip
        val x0 = PAD + 4f; val x1 = sw - PAD - 4f; val y0 = 36f; val y1 = 90f
        rect(x0, y0, x1, y1, Color.rgb(32, 32, 38))
        val sorted = A.jump.sorted()
        val mx = sorted[min(sorted.size - 1, (sorted.size * 0.99).toInt())] + 1e-6f
        p.strokeWidth = 1f; p.color = Color.rgb(90, 150, 220)
        for (k in 0 until A.n) {
            val v = min(1f, A.jump[k] / mx)
            val x = X(x0, x1, A.t[k])
            p.color = Color.rgb(90, 150, 220); c.drawLine(x, y1, x, y1 - v * (y1 - y0 - 16), p)
        }
        if (allTiles.size > n) {   // outline the part of the video this sheet covers
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.rgb(255, 200, 60)
            c.drawRect(X(x0, x1, tiles.first().start), y0, X(x0, x1, tiles.last().end), y1, p)
            p.style = Paint.Style.FILL; p.strokeWidth = 1f
        }
        allTiles.forEachIndexed { i, t ->
            val on = i in first until first + n
            val mk = if (on) Color.rgb(255, 200, 60) else Color.rgb(110, 100, 70)
            val xs = X(x0, x1, t.start)
            p.color = Color.rgb(90, 90, 100); c.drawLine(xs, y0, xs, y1, p)
            val x = X(x0, x1, t.time)
            p.color = mk
            c.drawPath(Path().apply { moveTo(x - 4, y0); lineTo(x + 4, y0); lineTo(x, y0 + 7); close() }, p)
            if (on || allTiles.size <= 24) text(allIds[i], x + 3, y0 + 12, 12f, mk, true)
        }
        val st = niceStep(span)
        var s = ceil(w0 / st) * st
        while (s <= w1 + 1e-6) {
            text(fmt(s, if (st >= 1) 0 else 1), X(x0, x1, s), y1 + 13, 12f, Color.rgb(150, 150, 160))
            s += st
        }

        // tiles
        val gridX = (sw - cols * tw - PAD * (cols - 1)) / 2
        val dst = Rect(); val bandBrief = Color.rgb(120, 80, 10); val bandNorm = Color.rgb(40, 40, 48)
        val fp = Paint(Paint.FILTER_BITMAP_FLAG)
        tiles.forEachIndexed { i, t ->
            val r = i / cols; val col = i % cols
            val x = (gridX + col * (tw + PAD)).toFloat()
            val y = (head + PAD + r * (th + BAND + PAD)).toFloat()
            dst.set(x.toInt(), y.toInt(), x.toInt() + tw, y.toInt() + th)
            frames[i]?.let { c.drawBitmap(it, null, dst, fp) } ?: rect(dst.left.toFloat(), dst.top.toFloat(), dst.right.toFloat(), dst.bottom.toFloat(), Color.DKGRAY)
            val brief = (t.end - t.start) < max(2.0, span / 60) && span > 20
            rect(x, y + th, x + tw, y + th + BAND, if (brief) bandBrief else bandNorm)
            text(ids[i], x + 5, y + th + 21, 20f, Color.rgb(255, 210, 90), true)
            text(fmt(t.time, dec), x + 5 + textW(ids[i], 20f, true) + 8, y + th + 21, 18f, Color.WHITE)
            val rng = "${fmt(t.start, rdec)}–${fmt(t.end, rdec)}"
            text(rng, x + tw - textW(rng, 15f) - 5, y + th + 20, 15f, Color.rgb(200, 200, 210))
            val by = y + th + BAND - 4
            rect(x, by, x + tw, by + 3, Color.rgb(70, 70, 80))
            val bs = X(x, x + tw, t.start)
            rect(bs, by, max(bs + 2, X(x, x + tw, t.end)), by + 3, Color.rgb(255, 200, 60))
        }
        return bmp
    }

    private fun niceStep(span: Double): Double {
        for (st in doubleArrayOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0, 900.0, 1800.0))
            if (span / st <= 10) return st
        return 3600.0
    }

    /** Pixel -> tile index hit testing for taps on the rendered sheet. */
    fun tileRects(A: Analysis, n: Int, sheetW: Int, ar: Double): List<RectF> {
        val head = 34 + 72
        fun tileW(c: Int): Double {
            val r = ceil(n / c.toDouble())
            return min((SHEET - PAD * (c + 1)) / c.toDouble(), (SHEET - head - PAD - r * (BAND + PAD)) / r / ar)
        }
        val cols = (1..n).maxBy { tileW(it) }
        val tw = tileW(cols).toInt(); val th = (tw * ar).toInt()
        val gridX = (sheetW - cols * tw - PAD * (cols - 1)) / 2
        return (0 until n).map { i ->
            val x = (gridX + (i % cols) * (tw + PAD)).toFloat(); val y = (head + PAD + (i / cols) * (th + BAND + PAD)).toFloat()
            RectF(x, y, x + tw, y + th + BAND)
        }
    }
}
