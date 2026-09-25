package com.mints.vistile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Tiny view toolkit so the screen can be built in code with one consistent look. */
class Ui(private val ctx: Context) {
    companion object {
        val BG = Color.rgb(18, 18, 22)          // same as the sheets
        val SURFACE = Color.rgb(29, 29, 36)
        val BAR = Color.rgb(24, 24, 30)
        val STROKE = Color.rgb(48, 48, 58)
        val TEXT = Color.rgb(240, 240, 244)
        val MUTED = Color.rgb(165, 165, 178)
        val FAINT = Color.rgb(112, 112, 126)
        val ACCENT = Color.rgb(132, 84, 255)    // Vistile purple (from the icon)
        val ACCENT_TEXT = Color.rgb(186, 156, 255)  // purple readable as text on dark
        val ON_ACCENT = Color.WHITE
        val ERROR = Color.rgb(255, 128, 110)
    }

    private val density = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * density + 0.5f).toInt()

    fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radiusDp * density
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun pressable(bg: GradientDrawable, ripple: Int) =
        RippleDrawable(ColorStateList.valueOf(ripple), bg, null)

    fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = s; textSize = sizeSp; setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    fun column(vararg v: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; v.forEach { addView(it) }
    }

    fun row(vararg v: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; v.forEach { addView(it) }
    }

    fun gapAbove(d: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(d) }

    fun card(vararg v: View, pad: Int = 22) = column(*v).apply {
        background = rounded(SURFACE, 18f, STROKE)
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    fun button(label: String, filled: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (filled) ON_ACCENT else ACCENT_TEXT)
        background = pressable(
            if (filled) rounded(ACCENT, 14f) else rounded(Color.TRANSPARENT, 14f, ACCENT),
            if (filled) Color.argb(60, 0, 0, 0) else Color.argb(50, 132, 84, 255),
        )
        setPadding(dp(20), dp(13), dp(20), dp(13))
        isClickable = true; setOnClickListener { onClick() }
    }

    fun pill(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13f; setTextColor(TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = pressable(rounded(SURFACE, 20f, STROKE), Color.argb(40, 255, 255, 255))
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isClickable = true; setOnClickListener { onClick() }
    }

    fun chip(label: String, current: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (current) ON_ACCENT else TEXT)
        background = pressable(if (current) rounded(ACCENT, 16f) else rounded(SURFACE, 16f, STROKE), Color.argb(40, 255, 255, 255))
        setPadding(dp(14), dp(7), dp(14), dp(7))
        isClickable = true; setOnClickListener { onClick() }
    }

    fun input(hint: String) = EditText(ctx).apply {
        this.hint = hint; textSize = 14f
        setTextColor(TEXT); setHintTextColor(FAINT)
        inputType = InputType.TYPE_CLASS_TEXT; isSingleLine = true
        background = rounded(SURFACE, 14f, STROKE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    fun progressBar() = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(STROKE)
        layoutParams = LinearLayout.LayoutParams(-1, dp(6))
    }
}

/** Round purple badge with a play triangle, for the empty state. */
class PlayBadge(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val r = minOf(width, height) / 2f
        p.color = Color.argb(55, 132, 84, 255); c.drawCircle(r, r, r, p)
        p.color = Ui.ACCENT; c.drawCircle(r, r, r * 0.72f, p)
        p.color = Ui.ON_ACCENT
        c.drawPath(Path().apply {
            moveTo(r * 0.82f, r * 0.62f); lineTo(r * 1.42f, r); lineTo(r * 0.82f, r * 1.38f); close()
        }, p)
    }
}
