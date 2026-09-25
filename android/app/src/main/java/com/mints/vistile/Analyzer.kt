package com.mints.vistile

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

const val SIG = 32          // SIG x SIG luma signature
const val CSIG = 8          // CSIG x CSIG x 2 chroma signature
const val DET = 64          // DET x DET luma grid for the detail (sharpness/content) score

/** Per-sample analysis of a time window of the video. Times are in seconds. */
class Analysis(
    val lum: Array<FloatArray>,     // illumination-normalised luma signatures
    val col: Array<FloatArray>,     // chroma signatures
    val detail: FloatArray,         // Laplacian variance, higher = sharper / more content
    val t: DoubleArray,             // sample times
    val t0: Double, val t1: Double, // analysed window
    val total: Double,              // full video duration
) {
    val n get() = t.size
    val dur get() = t1 - t0
    lateinit var jump: FloatArray   // distance of each sample to the previous one
    var jumpP98 = 0f                // 98th percentile of jump over the window
}

/**
 * Decodes the video with MediaCodec (hardware when available) and reduces
 * sampled frames to tiny signatures straight from the YUV planes; no
 * Bitmaps are created during analysis.
 */
object Analyzer {

    fun durationSec(ctx: Context, uri: Uri): Double {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val track = videoTrack(ex)
        val fmt = ex.getTrackFormat(track)
        ex.release()
        return if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0
    }

    private fun videoTrack(ex: MediaExtractor): Int {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) return i
        }
        throw IllegalArgumentException("No video track")
    }

    /**
     * @param rate samples per second to analyse
     * @param progress called with 0..1
     */
    fun analyse(
        ctx: Context, uri: Uri, t0: Double, t1: Double?, rate: Double,
        progress: (Float) -> Unit,
    ): Analysis {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val track = videoTrack(ex)
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val total = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0
        val end = t1 ?: total
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val codec = MediaCodec.createDecoderByType(mime)
        try {
            // Ask the decoder to run as fast as it can (ignored where unsupported).
            val fast = MediaFormat(fmt).apply {
                setInteger(MediaFormat.KEY_PRIORITY, 1)
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            }
            codec.configure(fast, null, null, 0)
        } catch (e: Exception) {
            codec.reset()
            codec.configure(fmt, null, null, 0)
        }
        codec.start()
        if (t0 > 0) ex.seekTo((t0 * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val lum = ArrayList<FloatArray>(); val col = ArrayList<FloatArray>()
        val det = ArrayList<Float>(); val times = ArrayList<Double>()
        val step = 1.0 / rate
        var next = t0
        var lastPts = t0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val endUs = (end * 1e6).toLong()
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(buf, 0)
                        // keep feeding a little past the window end: B-frame reordering
                        if (sz < 0 || ex.sampleTime > endUs + 500_000) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    val ts = info.presentationTimeUs / 1e6
                    if (ts >= t0 - 1e-6 && ts < end && ts >= next - 1e-6 && info.size > 0) {
                        val img = codec.getOutputImage(oi)
                        if (img != null) {
                            val s = signature(img)
                            lum.add(s.first); col.add(s.second); det.add(s.third); times.add(ts)
                            img.close()
                            next = ts + step * 0.999
                            if (end > t0) progress(((ts - t0) / (end - t0)).toFloat().coerceIn(0f, 1f))
                        }
                    }
                    if (ts > lastPts) lastPts = ts
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            codec.stop(); codec.release(); ex.release()
        }
        if (times.isEmpty()) throw IllegalStateException("Could not decode any frames")
        // B-frames can come out slightly out of order; sort by time.
        val order = times.indices.sortedBy { times[it] }
        return Analysis(
            Array(order.size) { lum[order[it]] }, Array(order.size) { col[order[it]] },
            FloatArray(order.size) { det[order[it]] }, DoubleArray(order.size) { times[order[it]] },
            times.min(), if (t1 != null) min(end, max(lastPts, times.max())) else max(total, times.max()),
            if (total > 0) total else times.max(),
        )
    }

    /** Luma signature (normalised), chroma signature, detail score from a YUV_420_888 image. */
    private fun signature(img: Image): Triple<FloatArray, FloatArray, Float> {
        val crop = img.cropRect
        val w = crop.width(); val h = crop.height()
        val yp = img.planes[0]
        val yb = yp.buffer; val yrs = yp.rowStride; val yps = yp.pixelStride
        fun Y(x: Int, y: Int) = yb.get((crop.top + y) * yrs + (crop.left + x) * yps).toInt() and 0xff

        // 32x32 cell means from a 4x4 sample lattice per cell
        val lum = FloatArray(SIG * SIG)
        var mean = 0.0
        for (cy in 0 until SIG) for (cx in 0 until SIG) {
            var acc = 0
            for (sy in 0 until 4) for (sx in 0 until 4) {
                acc += Y(((cx * 4 + sx) * 2 + 1) * w / (SIG * 8), ((cy * 4 + sy) * 2 + 1) * h / (SIG * 8))
            }
            val v = acc / 16f
            lum[cy * SIG + cx] = v; mean += v
        }
        mean /= lum.size
        var varSum = 0.0
        for (v in lum) varSum += (v - mean) * (v - mean)
        val std = sqrt(varSum / lum.size)
        // illumination-invariant: remove brightness offset, normalise contrast
        for (i in lum.indices) lum[i] = ((lum[i] - mean) / (std + 8.0) * 40.0).toFloat()

        // chroma 8x8 x (U,V)
        val col = FloatArray(CSIG * CSIG * 2)
        val cw = w / 2; val ch = h / 2
        for (p in 1..2) {
            val pl = img.planes[p]
            val b = pl.buffer; val rs = pl.rowStride; val ps = pl.pixelStride
            for (cy in 0 until CSIG) for (cx in 0 until CSIG) {
                var acc = 0
                for (sy in 0 until 4) for (sx in 0 until 4) {
                    val x = ((cx * 4 + sx) * 2 + 1) * cw / (CSIG * 8) + crop.left / 2
                    val y = ((cy * 4 + sy) * 2 + 1) * ch / (CSIG * 8) + crop.top / 2
                    acc += b.get(y * rs + x * ps).toInt() and 0xff
                }
                col[(cy * CSIG + cx) * 2 + (p - 1)] = acc / 16f
            }
        }

        // detail: Laplacian variance on a 64x64 grid (2x2 box-sampled)
        val g = FloatArray(DET * DET)
        for (y in 0 until DET) for (x in 0 until DET) {
            val px = (x * 2 + 1) * w / (DET * 2); val py = (y * 2 + 1) * h / (DET * 2)
            val px2 = min(w - 1, px + max(1, w / (DET * 4))); val py2 = min(h - 1, py + max(1, h / (DET * 4)))
            g[y * DET + x] = (Y(px, py) + Y(px2, py) + Y(px, py2) + Y(px2, py2)) / 4f
        }
        var s = 0.0; var s2 = 0.0; var cnt = 0
        for (y in 1 until DET - 1) for (x in 1 until DET - 1) {
            val i = y * DET + x
            val l = g[i - 1] + g[i + 1] + g[i - DET] + g[i + DET] - 4 * g[i]
            s += l; s2 += l * l; cnt++
        }
        val detail = (s2 / cnt - (s / cnt) * (s / cnt)).toFloat()
        return Triple(lum, col, detail)
    }
}
