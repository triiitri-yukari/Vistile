package com.mints.vistile

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    /** One page (image) of a sheet set, showing tiles [range]. */
    private class Page(val bitmap: Bitmap, val file: File, val range: IntRange)

    /** One rendered timeline (overview or a zoom into a range), possibly spanning several pages. */
    private class Sheet(
        val A: Analysis, val tiles: List<Tile>, val ids: List<String>, val pages: List<Page>,
        val label: String, val ar: Double, val seconds: Double, val maxUsed: Int, val fillUsed: Boolean,
    )

    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var videoUri: Uri? = null
    private var videoName = "video"
    private var videoDuration = 0.0
    // The limit is set in images (sheets), since each image costs the AI the same however full it is.
    // It's a ceiling only; the content decides the actual count.
    private var maxSheets = 3
    private val maxTiles get() = maxSheets * SheetRenderer.PER_SHEET
    // "Fill sheets": use the free slots on the images already in use for more moments
    private var fillSheets = false
    private var customZooms = 0
    private val stack = ArrayList<Sheet>()   // overview at [0], zooms above it
    private var busy = false
    private var pendingZoom: String? = null
    private var topInset = 0
    private var zh = false          // UI language: Chinese when true, English otherwise
    private lateinit var crumbScroll: View

    private fun tr(en: String, cn: String) = if (zh) cn else en

    private fun loadLanguage() {
        val saved = getSharedPreferences("settings", MODE_PRIVATE).getString("lang", null)
        zh = saved?.let { it == "zh" } ?: (resources.configuration.locales[0].language == "zh")
        fillSheets = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("fill", false)
    }

    private fun toggleLanguage() {
        if (busy) return
        zh = !zh
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("lang", if (zh) "zh" else "en").apply()
        buildUi()                       // rebuild in place; analysed sheets stay in memory
        if (stack.isNotEmpty()) showTop()
    }

    private lateinit var k: Ui
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var maxValue: TextView
    private lateinit var maxSeek: android.widget.SeekBar
    private lateinit var maxHint: TextView
    private lateinit var rerun: TextView
    private lateinit var emptyCard: View
    private lateinit var videoCard: View
    private lateinit var videoTitle: TextView
    private lateinit var videoMeta: TextView
    private lateinit var progressCard: View
    private lateinit var stageText: TextView
    private lateinit var percentText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var crumbRow: LinearLayout
    private lateinit var navBlock: View
    private lateinit var zoomInput: EditText
    private lateinit var pagesView: LinearLayout
    private lateinit var fillSwitch: android.widget.Switch
    private lateinit var bottomBar: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        k = Ui(this)
        loadLanguage()
        buildUi()
        getExternalFilesDir(null)   // make sure the app owns its files dir (adb test pushes go there)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // ------------------------------------------------------------------ UI
    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }

        content = k.column().apply { setPadding(k.dp(16), k.dp(16), k.dp(16), k.dp(24)) }
        // the scroll view holds focus so the zoom box never grabs it and yanks the page around
        scroll = ScrollView(this).apply {
            isFillViewport = true; isFocusableInTouchMode = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
            addView(content)
        }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        // header: mark + name, max-tiles pill on the right
        content.addView(k.row(
            ImageView(this).apply {
                setImageResource(R.drawable.vistile_logo); scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(k.dp(52), k.dp(44)).apply { marginEnd = k.dp(12) }
            },
            k.column(
                k.text("Vistile", 24f, Ui.TEXT, bold = true),
                k.text(tr("Video → a timeline an AI can read", "把视频变成 AI 看得懂的时间轴"), 13f, Ui.MUTED),
            ).also { it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f) },
            k.pill(if (zh) "EN" else "中文") { toggleLanguage() },
        ).also { it.setPadding(0, 0, 0, k.dp(18)) })

        // max tiles, right on the main screen
        maxValue = k.text("$maxSheets", 15f, Ui.ACCENT_TEXT, bold = true)
        maxHint = k.text("", 12f, Ui.FAINT)
        fillSwitch = android.widget.Switch(this).apply {
            isChecked = fillSheets
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(Ui.ACCENT, Ui.MUTED))
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(android.graphics.Color.argb(120, 132, 84, 255), Ui.STROKE))
            setOnCheckedChangeListener { _, on ->
                fillSheets = on
                getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("fill", on).apply()
                updateRerun()
            }
        }
        updateMaxHint()
        rerun = k.chip(tr("Apply", "应用"), current = true) { rerunOverview() }.apply { visibility = View.GONE }
        maxSeek = android.widget.SeekBar(this).apply {
            max = 5; progress = maxSheets - 1
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.STROKE)
            setPadding(0, k.dp(10), 0, k.dp(6))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, v: Int, u: Boolean) {
                    maxSheets = v + 1; maxValue.text = "$maxSheets"; updateMaxHint(); updateRerun()
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
            })
        }
        content.addView(k.card(
            k.row(
                k.text(tr("Max images", "最多图片"), 15f, Ui.TEXT, bold = true).also { it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f) },
                rerun.also { it.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = k.dp(10) } },
                maxValue,
            ),
            maxSeek,
            maxHint,
            View(this).apply { setBackgroundColor(Ui.STROKE) }.also {
                it.layoutParams = LinearLayout.LayoutParams(-1, k.dp(1)).apply { topMargin = k.dp(14); bottomMargin = k.dp(12) }
            },
            k.row(
                k.column(
                    k.text(tr("Fill sheets", "填满图片"), 15f, Ui.TEXT, bold = true),
                    k.text(tr("Use every free slot on the images for more moments (smaller tiles)",
                        "用满每张图的空位，多放一些时刻（图块会变小）"), 12f, Ui.FAINT)
                        .also { it.setPadding(0, k.dp(2), k.dp(12), 0) },
                ).also { it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f) },
                fillSwitch,
            ),
            pad = 16,
        ))
        content.addView(View(this), LinearLayout.LayoutParams(-1, k.dp(12)))

        // empty state
        emptyCard = k.card(
            PlayBadge(this).also { it.layoutParams = LinearLayout.LayoutParams(k.dp(64), k.dp(64)).apply { bottomMargin = k.dp(16) } },
            k.text(tr("Pick a video", "选一个视频"), 20f, Ui.TEXT, bold = true),
            k.text(tr("Vistile picks the frames that matter and lays them out as timestamped sheets you can hand to any AI chat.",
                "Vistile 会挑出关键画面，排成带时间戳的图，直接发给任何 AI 聊天即可。"),
                14f, Ui.MUTED).also { it.setPadding(0, k.dp(6), 0, k.dp(20)); it.setLineSpacing(0f, 1.2f) },
            k.button(tr("Choose video", "选择视频"), filled = true) { pickVideo() },
            k.text(tr("You can also share a video to Vistile from your gallery.", "也可以从相册把视频分享到 Vistile。"), 12f, Ui.FAINT)
                .also { it.setPadding(0, k.dp(14), 0, 0) },
        )
        content.addView(emptyCard)

        // loaded video
        videoTitle = k.text("", 16f, Ui.TEXT, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        videoMeta = k.text("", 13f, Ui.MUTED)
        videoCard = k.card(k.row(
            k.column(videoTitle, videoMeta).also { it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f) },
            k.pill(tr("Change", "更换")) { pickVideo() },
        ), pad = 14).apply { visibility = View.GONE }
        content.addView(videoCard)

        // progress
        stageText = k.text("", 14f, Ui.TEXT)
        percentText = k.text("", 13f, Ui.ACCENT_TEXT, bold = true)
        progress = k.progressBar()
        progressCard = k.card(
            k.row(stageText.also { it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }, percentText),
            progress.also { (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = k.dp(10) },
            pad = 14,
        ).apply { visibility = View.GONE }
        content.addView(progressCard, k.gapAbove(12))

        errorText = k.text("", 13f, Ui.ERROR).apply { visibility = View.GONE; setPadding(k.dp(4), k.dp(10), k.dp(4), 0) }
        content.addView(errorText)

        // navigation: breadcrumbs + zoom box
        crumbRow = k.row()
        zoomInput = k.input(tr("Zoom to a tile (7, 7.3) or time (1:00-1:20)", "放大图块（7、7.3）或时间（1:00-1:20）")).apply {
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, action, ev ->
                if (action == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { zoomFromInput(); true } else false
            }
        }
        navBlock = k.column(
            android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(crumbRow) }
                .also { crumbScroll = it },
            k.row(
                zoomInput.also { it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = k.dp(8) } },
                k.button(tr("Zoom", "放大"), filled = false) { zoomFromInput() },
            ).also { it.setPadding(0, k.dp(12), 0, 0) },
            k.text(tr("Tip: tap any tile to zoom into the time it covers.", "提示：点任意图块，即可放大它覆盖的时间段。"), 12f, Ui.FAINT).also { it.setPadding(k.dp(2), k.dp(8), 0, 0) },
        ).apply { visibility = View.GONE }
        content.addView(navBlock, k.gapAbove(20))

        pagesView = k.column()
        content.addView(pagesView, k.gapAbove(8))

        // footer: project link, pushed to the bottom when the screen isn't full
        content.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(k.text(tr("Vistile ${BuildConfig.VERSION_NAME} · GitHub  ↗", "Vistile ${BuildConfig.VERSION_NAME} · GitHub  ↗"), 13f, Ui.ACCENT_TEXT).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(0, k.dp(28), 0, k.dp(8))
            isClickable = true
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB))) } catch (e: Exception) { toast(GITHUB) }
            }
        }, LinearLayout.LayoutParams(-1, -2))

        // sticky action bar
        bottomBar = k.row(
            k.button(tr("Copy prompt", "复制提示词"), filled = false) { copyPrompt() }.also { it.layoutParams = LinearLayout.LayoutParams(0, k.dp(48), 1f).apply { marginEnd = k.dp(10) } },
            k.button(tr("Save sheets", "保存图片"), filled = true) { saveToGallery() }.also { it.layoutParams = LinearLayout.LayoutParams(0, k.dp(48), 1f) },
        ).apply {
            setBackgroundColor(Ui.BAR)
            setPadding(k.dp(16), k.dp(12), k.dp(16), k.dp(12))
            elevation = k.dp(8).toFloat()
            visibility = View.GONE
        }
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))

        // edge-to-edge: keep content clear of the status and navigation bars
        root.setOnApplyWindowInsetsListener { _, ins ->
            val bars = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            topInset = bars.top
            content.setPadding(k.dp(16), bars.top + k.dp(16), k.dp(16), bars.bottom + k.dp(96))
            bottomBar.setPadding(k.dp(16), k.dp(12), k.dp(16), bars.bottom + k.dp(12))
            ins
        }
        setContentView(root)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BAR
    }

    private fun setStage(s: String) = ui.post { stageText.text = s }

    private fun showBusy(on: Boolean, msg: String = "") {
        progressCard.visibility = if (on) View.VISIBLE else View.GONE
        if (on) { stageText.text = msg; percentText.text = ""; progress.isIndeterminate = false; progress.progress = 0; errorText.visibility = View.GONE }
    }

    private fun showTop() {
        val s = stack.lastOrNull() ?: return
        updateRerun()
        emptyCard.visibility = View.GONE
        videoCard.visibility = View.VISIBLE
        navBlock.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        val ov = stack.first()
        videoTitle.text = videoName
        videoMeta.text = tr("${fmt(videoDuration, 0)} · ${ov.tiles.size} tiles · ${ov.pages.size} ${if (ov.pages.size == 1) "sheet" else "sheets"}",
            "${fmt(videoDuration, 0)} · ${ov.tiles.size} 格 · ${ov.pages.size} 张图")

        // breadcrumbs are only useful once you've zoomed in
        crumbScroll.visibility = if (stack.size > 1) View.VISIBLE else View.GONE

        // breadcrumbs: tap an earlier level to go back to it
        crumbRow.removeAllViews()
        stack.forEachIndexed { i, sh ->
            if (i > 0) crumbRow.addView(k.text("›", 16f, Ui.FAINT).also { it.setPadding(k.dp(6), 0, k.dp(6), 0) })
            val name = if (i == 0) tr("Overview", "总览") else tr("Zoom ${sh.label}", "放大 ${sh.label}")
            crumbRow.addView(k.chip(name, current = i == stack.size - 1) {
                if (!busy && i < stack.size - 1) { while (stack.size > i + 1) stack.removeAt(stack.size - 1); showTop() }
            })
        }

        pagesView.removeAllViews()
        val span = s.A.t1 - s.A.t0
        s.pages.forEachIndexed { pi, pg ->
            val range = "${fmt(s.A.t0, rangeDec(span))}–${fmt(s.A.t1, rangeDec(span))}"
            val tiles = "${s.ids[pg.range.first]}–${s.ids[pg.range.last]}"
            val caption = if (s.pages.size == 1) tr("${s.tiles.size} tiles · $range", "${s.tiles.size} 格 · $range")
            else tr("Sheet ${pi + 1} of ${s.pages.size} · tiles $tiles", "第 ${pi + 1}/${s.pages.size} 张 · 图块 $tiles")
            pagesView.addView(k.text(caption, 13f, Ui.MUTED, bold = true).also { it.setPadding(k.dp(2), k.dp(16), 0, k.dp(8)) })
            pagesView.addView(ImageView(this).apply {
                adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2)
                background = k.rounded(Ui.SURFACE, 14f); clipToOutline = true
                setImageBitmap(pg.bitmap)
                setOnTouchListener { v, e ->
                    if (e.action == MotionEvent.ACTION_UP) onSheetTap(v as ImageView, s, pg, e.x, e.y)
                    v.performClick(); true
                }
            })
        }
        pagesView.addView(k.text(tr("Made in %.1f s", "用时 %.1f 秒").format(s.seconds), 12f, Ui.FAINT).also { it.setPadding(k.dp(2), k.dp(12), 0, 0) })
        // overview: start at the top; zoom: bring the breadcrumbs just below the status bar
        zoomInput.clearFocus(); scroll.requestFocus()
        scroll.post { scroll.scrollTo(0, if (stack.size == 1) 0 else max(0, navBlock.top - topInset - k.dp(12))) }
    }

    private fun updateMaxHint() {
        maxHint.text = tr("Up to $maxTiles tiles · uses only what the video needs",
            "最多 $maxTiles 格 · 按视频需要取用")
    }

    /** The overview was made with a different limit: offer to redo it. */
    private fun updateRerun() {
        val ov = stack.firstOrNull()
        val changed = ov != null && (ov.maxUsed != maxTiles || ov.fillUsed != fillSheets)
        rerun.visibility = if (changed && !busy) View.VISIBLE else View.GONE
    }

    private fun rerunOverview() {
        val uri = videoUri ?: return
        if (!busy) open(uri)
    }

    // --------------------------------------------------------------- input
    private fun pickVideo() {
        if (busy) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"
        }, 1)
    }

    @Deprecated("simple prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) data?.data?.let { open(it) }
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        // debug hook for adb-driven testing: --es path <file in app external files dir> [--ei sheets N] [--es zoom "5"]
        if (BuildConfig.DEBUG) i.getStringExtra("path")?.let { p ->
            if (i.hasExtra("sheets")) maxSeek.progress = i.getIntExtra("sheets", 3).coerceIn(1, 6) - 1
            if (i.hasExtra("fill")) fillSwitch.isChecked = i.getBooleanExtra("fill", false)
            open(Uri.fromFile(File(p)), i.getStringExtra("zoom"))
            return
        }
        val uri = when (i.action) {
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
        uri?.let { open(it) }
    }

    private fun open(uri: Uri, thenZoom: String? = null) {
        if (busy) return
        videoUri = uri
        videoName = queryName(uri)
        stack.clear(); customZooms = 0
        pagesView.removeAllViews(); navBlock.visibility = View.GONE; bottomBar.visibility = View.GONE
        emptyCard.visibility = View.GONE
        videoCard.visibility = View.VISIBLE
        videoTitle.text = videoName; videoMeta.text = tr("Reading video…", "正在读取视频…")
        run(tr("Reading frames", "读取画面")) {
            val total = Analyzer.durationSec(this, uri)
            videoDuration = total
            ui.post { videoMeta.text = fmt(total, 0) }
            // ~10 samples/s, capped at ~3000 samples for long videos
            val rate = if (total > 0) min(10.0, max(0.5, 3000.0 / total)) else 10.0
            val sheet = makeSheet(uri, 0.0, null, rate, "", "Overview", zoom = false)
            ui.post { stack.add(sheet); showTop() }
            pendingZoom = thenZoom
        }
    }

    private fun queryName(uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "video"
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "video"
        } catch (e: Exception) { "video" }
    }

    // ----------------------------------------------------------- pipeline
    private fun run(msg: String, job: () -> Unit) {
        busy = true
        showBusy(true, msg); rerun.visibility = View.GONE
        worker.execute {
            try { job() } catch (e: Exception) {
                Log.e(TAG, "failed", e)
                ui.post {
                    errorText.text = tr("Couldn't process this video: ", "无法处理这个视频：") + e.message; errorText.visibility = View.VISIBLE
                    if (stack.isEmpty()) { videoCard.visibility = View.GONE; emptyCard.visibility = View.VISIBLE }
                }
            } finally {
                ui.post {
                    busy = false; showBusy(false); updateRerun()
                    pendingZoom?.let { z -> pendingZoom = null; zoomInput.setText(z); zoomFromInput() }
                }
            }
        }
    }

    private fun makeSheet(uri: Uri, t0: Double, t1: Double?, rate: Double, prefix: String, label: String, zoom: Boolean): Sheet {
        val tA = System.currentTimeMillis()
        val A = Analyzer.analyse(this, uri, t0, t1, rate) { f ->
            ui.post { progress.progress = (f * 800).toInt(); percentText.text = "${(f * 80).toInt()}%" }
        }
        val tS = System.currentTimeMillis()
        setStage(tr("Choosing key moments", "挑选关键时刻"))
        val minDur = if (zoom) Segmenter.autoMinDur(A, lo = 1.0 / rate) else Segmenter.autoMinDur(A)
        // a zoom must fit on one sheet; the overview may span several
        val cap = if (zoom) min(maxTiles, SheetRenderer.PER_SHEET) else maxTiles
        val tiles = Segmenter.segment(A, cap, minDur, fill = fillSheets)
        val tR = System.currentTimeMillis()
        val (pages, ar) = drawSheet(uri, A, tiles, prefix, label, zoom)
        val tE = System.currentTimeMillis()
        val timing = "analyse ${tS - tA} ms (${A.n} samples) · select ${tR - tS} ms · render ${tE - tR} ms"
        Log.i(TAG, "sheet $label: ${tiles.size} tiles on ${pages.size} sheet(s); $timing -> ${pages.joinToString { it.file.absolutePath }}")
        return Sheet(A, tiles, tiles.indices.map { "$prefix${it + 1}" }, pages, label, ar, (tE - tA) / 1000.0, maxTiles, fillSheets)
    }

    /** Draws and saves the pages for [tiles]; returns the pages and the frames' aspect ratio. */
    private fun drawSheet(uri: Uri, A: Analysis, tiles: List<Tile>, prefix: String, label: String, zoom: Boolean): Pair<List<Page>, Double> {
        val ids = tiles.indices.map { "$prefix${it + 1}" }
        val span = A.t1 - A.t0
        val base = if (!zoom)
            "$videoName · ${fmt(A.total, 0)} · overview · ${tiles.size} tiles in time order · id, frame time | range covered"
        else "$videoName · ZOOM $label · ${fmt(A.t0, rangeDec(span))}–${fmt(A.t1, rangeDec(span))} · ${tiles.size} tiles"
        val dir = File(getExternalFilesDir(null), "sheets").apply { mkdirs() }
        val safe = videoName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_")
        val stem = "${safe}_${if (!zoom) "overview" else "zoom_" + label.replace(Regex("[^A-Za-z0-9.]"), "_")}"
        val ranges = SheetRenderer.paginate(tiles.size)
        var ar = 1.0
        val pages = ranges.mapIndexed { pi, r ->
            setStage(if (ranges.size == 1) tr("Drawing the sheet", "绘制图片")
                else tr("Drawing sheet ${pi + 1} of ${ranges.size}", "绘制第 ${pi + 1}/${ranges.size} 张"))
            ui.post { val f = 0.8f + 0.2f * pi / ranges.size; progress.progress = (f * 1000).toInt(); percentText.text = "${(f * 100).toInt()}%" }
            // fetch frames page by page to keep memory bounded
            val pageTiles = tiles.subList(r.first, r.last + 1)
            val frames = SheetRenderer.frames(this, uri, pageTiles, 640)
            frames.firstOrNull { it != null }?.let { ar = it.height.toDouble() / it.width }
            val title = if (ranges.size == 1) base else "SHEET ${pi + 1}/${ranges.size} (tiles ${ids[r.first]}–${ids[r.last]}) · $base"
            val bmp = SheetRenderer.render(A, pageTiles, frames, ids.subList(r.first, r.last + 1), title, tiles, ids, r.first)
            frames.forEach { it?.recycle() }
            val file = File(dir, if (ranges.size == 1) "$stem.png" else "${stem}_${pi + 1}of${ranges.size}.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Page(bmp, file, r)
        }
        return pages to ar
    }

    private fun zoomInto(t0: Double, t1: Double, prefix: String, label: String) {
        val uri = videoUri ?: return
        if (busy) return
        val span = t1 - t0
        // denser sampling for short windows, up to every frame at 60 fps
        val rate = if (span > 60) min(10.0, max(0.5, 3000.0 / span)) else min(60.0, max(10.0, 160.0 / span))
        run(tr("Zooming into $label", "放大 $label")) {
            val sheet = makeSheet(uri, t0, t1, rate, prefix, label, zoom = true)
            ui.post { stack.add(sheet); showTop() }
        }
    }

    private fun zoomTile(sheet: Sheet, i: Int) {
        val t = sheet.tiles[i]
        if (t.end - t.start < 0.05) { toast(tr("Tile ${sheet.ids[i]} is already a single frame", "图块 ${sheet.ids[i]} 已经是单帧")); return }
        zoomInto(t.start, t.end, sheet.ids[i] + ".", sheet.ids[i])
    }

    private fun onSheetTap(view: ImageView, s: Sheet, pg: Page, x: Float, y: Float) {
        if (busy) return
        val scale = pg.bitmap.width.toFloat() / view.width
        val offY = (view.height - pg.bitmap.height / scale) / 2
        val bx = x * scale; val by = (y - max(0f, offY)) * scale
        val rects = SheetRenderer.tileRects(s.A, pg.range.count(), pg.bitmap.width, s.ar)
        val i = rects.indexOfFirst { it.contains(bx, by) }
        if (i >= 0) zoomTile(s, pg.range.first + i)
    }

    /** Accepts a tile ID from any sheet in the stack ("5", "5.3") or a time range ("1:00-1:02", "60-62"). */
    private fun zoomFromInput() {
        val q = zoomInput.text.toString().trim().removePrefix("ZOOM").removePrefix("zoom").trim()
        if (q.isEmpty()) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(zoomInput.windowToken, 0)
        for (s in stack.reversed()) {
            val i = s.ids.indexOf(q)
            if (i >= 0) { zoomInput.setText(""); zoomTile(s, i); return }
        }
        val parts = q.split(Regex("\\s*[-–—]\\s*|\\s+to\\s+"))
        if (parts.size == 2) {
            val a = parseTime(parts[0]); val b = parseTime(parts[1])
            if (a != null && b != null && b > a) {
                val letter = ('A' + (customZooms++ % 26)).toString()
                zoomInput.setText("")
                zoomInto(a, b, letter, "$letter (${parts[0]}–${parts[1]})")
                return
            }
        }
        toast(tr("Enter a tile like 7 or 7.3, or a time like 1:00-1:20", "请输入图块（如 7、7.3）或时间（如 1:00-1:20）"))
    }

    private fun parseTime(s: String): Double? {
        val p = s.trim().split(":")
        return try {
            p.fold(0.0) { acc, v -> acc * 60 + v.toDouble() }
        } catch (e: NumberFormatException) { null }
    }

    @Deprecated("simple prototype")
    override fun onBackPressed() {
        if (stack.size > 1 && !busy) { stack.removeAt(stack.size - 1); showTop() } else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // --------------------------------------------------------------- output
    /** A short description of how to read the sheets; the sheets carry the timestamps themselves. */
    private fun prompt(s: Sheet): String {
        val span = s.A.t1 - s.A.t0
        val n = s.tiles.size; val pages = s.pages.size
        val range = "${fmt(s.A.t0, rangeDec(span))}–${fmt(s.A.t1, rangeDec(span))}"
        val overview = s === stack.firstOrNull()
        return if (!zh) {
            val images = if (pages == 1) "1 image" else "$pages images"
            val head = if (overview) "Visual timeline of a video (${fmt(s.A.total, 0)}): $n tiles in time order across $images."
            else "Zoomed visual timeline of $range of a video: $n tiles in time order (${s.ids.first()}–${s.ids.last()})."
            "$head Each tile shows its ID, frame time, and the time range it represents; an amber label marks a brief moment. " +
                "The strip at the top shows how much the picture changes over time."
        } else {
            val head = if (overview) "一个视频（${fmt(s.A.total, 0)}）的可视时间轴：共 $n 个图块，按时间顺序排在 $pages 张图里。"
            else "视频 $range 段的放大时间轴：共 $n 个图块，按时间顺序排列（${s.ids.first()}–${s.ids.last()}）。"
            "${head}每个图块标有编号、画面时间，以及它所代表的时间范围；琥珀色标签表示短暂出现的画面。顶部的条带显示画面随时间变化的程度。"
        }
    }

    private fun copyPrompt() {
        val s = stack.lastOrNull() ?: return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("prompt", prompt(s)))
        toast(tr("Prompt copied", "已复制提示词"))
    }

    private fun saveToGallery() {
        val s = stack.lastOrNull() ?: return
        for (pg in s.pages) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, pg.file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Vistile")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: continue
            contentResolver.openOutputStream(uri)?.use { out -> pg.file.inputStream().use { it.copyTo(out) } }
        }
        toast(if (s.pages.size == 1) tr("Sheet saved to Pictures/Vistile", "已保存到 Pictures/Vistile")
            else tr("${s.pages.size} sheets saved to Pictures/Vistile", "已保存 ${s.pages.size} 张到 Pictures/Vistile"))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val TAG = "Vistile"
        const val GITHUB = "https://github.com/triiitri-yukari/Vistile"
    }
}
