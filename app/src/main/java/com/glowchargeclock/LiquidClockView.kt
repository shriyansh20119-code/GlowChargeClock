package com.glowchargeclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Minimal full-screen colourful clock: black background, oversized digits,
 * and two soft grey/lilac colon dots.
 */
class LiquidClockView(context: Context, private val tilt: TiltSensor) : View(context) {

    private val d = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
        isSubpixelText = true
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val colors = intArrayOf(
        Color.rgb(213, 105, 181),
        Color.rgb(164, 137, 248),
        Color.rgb(158, 62, 169),
        Color.rgb(213, 105, 181)
    )

    private var battery = 0
    private var plugged = false
    private var full = false
    private var running = false
    private var hourText = "9"
    private var minuteText = "41"
    private var ampm = ""
    private var lastSecond = -1L
    private var textSizePx = 100f
    private var colonX = 0f
    private var baseline = 0f
    private var hourX = 0f
    private var minuteX = 0f
    private var dotRadius = 8f

    fun setBattery(level: Int, isPlugged: Boolean, isFull: Boolean) {
        battery = level.coerceIn(0, 100)
        plugged = isPlugged
        full = isFull
    }

    fun setWeather(temp: String, condition: String, code: Int) {}

    fun start() {
        if (running) return
        running = true
        invalidate()
    }

    fun stop() { running = false }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutClock(w.toFloat(), h.toFloat())
    }

    private fun layoutClock(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        val is24 = DateFormat.is24HourFormat(context)

        // Reserve enough room for the widest possible hour ("12" or "23").
        var size = h * 0.82f
        textPaint.textSize = size
        val hourCellWidth = textPaint.measureText(if (is24) "23" else "12")
        val minuteCellWidth = textPaint.measureText("00")
        val colonCellWidth = size * 0.24f
        val gap = size * 0.035f
        val total = hourCellWidth + minuteCellWidth + colonCellWidth + gap * 2f
        val maxWidth = w * 0.92f
        if (total > maxWidth) size *= maxWidth / total

        textSizePx = size
        textPaint.textSize = size
        val hourW = textPaint.measureText(if (is24) "23" else "12")
        val minuteW = textPaint.measureText("00")
        val colonW = size * 0.24f
        val spacing = size * 0.035f
        val fullW = hourW + colonW + minuteW + spacing * 2f
        val left = (w - fullW) / 2f

        hourX = left + hourW / 2f
        colonX = left + hourW + spacing + colonW / 2f
        minuteX = left + hourW + spacing + colonW + spacing + minuteW / 2f

        baseline = h / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        dotRadius = size * 0.065f
    }

    private fun updateTime() {
        val sec = System.currentTimeMillis() / 1000L
        if (sec == lastSecond) return
        lastSecond = sec
        val cal = java.util.Calendar.getInstance()
        val hour24 = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)

        if (DateFormat.is24HourFormat(context)) {
            hourText = String.format(java.util.Locale.getDefault(), "%02d", hour24)
            ampm = ""
        } else {
            ampm = if (hour24 < 12) "AM" else "PM"
            val hour12 = when (val h = hour24 % 12) { 0 -> 12; else -> h }
            hourText = hour12.toString()
        }
        minuteText = String.format(java.util.Locale.getDefault(), "%02d", minute)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = System.nanoTime() / 1_000_000_000f
        canvas.drawColor(Color.BLACK)
        updateTime()

        val shiftX = tilt.slideX.coerceIn(-1f, 1f) * 5f * d
        val shiftY = tilt.slideY.coerceIn(-1f, 1f) * 4f * d
        canvas.save()
        canvas.translate(shiftX, shiftY)
        textPaint.textSize = textSizePx
        textPaint.typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
        textPaint.textAlign = Paint.Align.CENTER

        // Both runs are drawn from their CELL CENTRES. Do not subtract half
        // the minute width here: Paint.Align.CENTER already centres the text.
        drawDigitRun(canvas, hourText, hourX, baseline, colors[0], colors[1])
        drawDigitRun(canvas, minuteText, minuteX, baseline, colors[1], colors[2])

        val pulse = 0.78f + 0.16f * (0.5f + 0.5f * sin(t * 2f * PI.toFloat()))
        val dotColor = (pulse * 255f).toInt().coerceIn(0, 255)
        dotPaint.shader = LinearGradient(
            colonX - dotRadius, 0f, colonX + dotRadius, 0f,
            0xFF8B8B8B.toInt(), 0xFFC9B9FF.toInt(), Shader.TileMode.CLAMP
        )
        dotPaint.alpha = dotColor
        val dotOffset = textSizePx * 0.22f
        canvas.drawCircle(colonX, baseline - dotOffset, dotRadius, dotPaint)
        canvas.drawCircle(colonX, baseline + dotOffset, dotRadius, dotPaint)
        dotPaint.alpha = 255
        dotPaint.shader = null

        if (ampm.isNotEmpty()) {
            textPaint.textSize = textSizePx * 0.095f
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.color = 0xFFB8A7D8.toInt()
            canvas.drawText(ampm, width - 28f * d, height - 24f * d, textPaint)
        }
        canvas.restore()

        if (plugged && !full) {
            val shimmer = 0.25f + 0.35f * (0.5f + 0.5f * sin(t * 2.4f))
            glowPaint.color = (shimmer * 255).toInt().shl(24) or 0x00BFA6FF
            val barW = width * 0.16f
            val barH = max(2f * d, 3f * d)
            canvas.drawRoundRect(
                width / 2f - barW / 2f, height - 8f * d,
                width / 2f + barW / 2f, height - 8f * d + barH,
                barH, barH, glowPaint
            )
        }
        if (running) postInvalidateDelayed(33L)
    }

    private fun drawDigitRun(
        canvas: Canvas, value: String, x: Float, y: Float, first: Int, second: Int
    ) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.shader = LinearGradient(
            x, y - textSizePx * 0.52f, x, y + textSizePx * 0.42f,
            intArrayOf(first, second), null, Shader.TileMode.CLAMP
        )
        textPaint.setShadowLayer(textSizePx * 0.018f, 0f, textSizePx * 0.008f, 0x553E1C58)
        canvas.drawText(value, x, y, textPaint)
        textPaint.clearShadowLayer()
        textPaint.shader = null
    }
}
