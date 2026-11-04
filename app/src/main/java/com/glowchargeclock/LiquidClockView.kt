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
 * Minimal full-screen colourful clock inspired by the supplied reference:
 * pure black background, oversized rounded digits and soft grey/lilac colon dots.
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
        Color.rgb(213, 105, 181), // pink
        Color.rgb(164, 137, 248), // violet
        Color.rgb(158, 62, 169),  // purple
        Color.rgb(213, 105, 181)
    )
    private var battery = 0
    private var plugged = false
    private var full = false
    private var running = false
    private var lastFrame = 0L
    private var hourText = "9"
    private var minuteText = "41"
    private var ampm = ""
    private var lastSecond = -1L
    private var textSizePx = 100f
    private var colonX = 0f
    private var baseline = 0f
    private var hourX = 0f
    private var minuteX = 0f
    private var hourWidth = 0f
    private var minuteWidth = 0f
    private var dotRadius = 8f
    private var widthScale = 1f
    private var timeShader: Shader? = null

    fun setBattery(level: Int, isPlugged: Boolean, isFull: Boolean) {
        battery = level.coerceIn(0, 100)
        plugged = isPlugged
        full = isFull
    }

    // Kept for compatibility with MainActivity; the minimal clock intentionally hides weather.
    fun setWeather(temp: String, condition: String, code: Int) {}

    fun start() {
        if (running) return
        running = true
        lastFrame = 0L
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
        val sampleHour = if (is24) "00" else "0"
        val sampleMinute = "00"
        var size = h * 0.82f
        textPaint.textSize = size
        val hourW = textPaint.measureText(sampleHour)
        val minuteW = textPaint.measureText(sampleMinute)
        val colonW = size * 0.20f
        val gap = size * 0.025f
        val total = hourW + minuteW + colonW + gap * 2f
        val maxW = w * 0.94f
        if (total > maxW) size *= maxW / total
        textSizePx = size
        textPaint.textSize = size
        hourWidth = textPaint.measureText(sampleHour)
        minuteWidth = textPaint.measureText(sampleMinute)
        val colonWidth = size * 0.20f
        val spacing = size * 0.025f
        val fullW = hourWidth + colonWidth + minuteWidth + spacing * 2f
        val left = (w - fullW) / 2f
        hourX = left + hourWidth / 2f
        colonX = left + hourWidth + spacing + colonWidth / 2f
        minuteX = left + hourWidth + spacing + colonWidth + spacing + minuteWidth / 2f
        baseline = h / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        dotRadius = size * 0.075f
        widthScale = if (fullW > 0f) min(1f, maxW / fullW) else 1f
        timeShader = LinearGradient(0f, baseline - size * 0.55f, 0f, baseline + size * 0.45f,
            Color.WHITE, 0xFFE9DFFF.toInt(), Shader.TileMode.CLAMP)
    }

    private fun updateTime() {
        val now = System.currentTimeMillis()
        val sec = now / 1000L
        if (sec == lastSecond) return
        lastSecond = sec
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        var hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        if (DateFormat.is24HourFormat(context)) {
            hourText = String.format(java.util.Locale.getDefault(), "%02d", hour)
            ampm = ""
        } else {
            ampm = if (hour < 12) "AM" else "PM"
            hour %= 12
            if (hour == 0) hour = 12
            hourText = hour.toString()
        }
        minuteText = String.format(java.util.Locale.getDefault(), "%02d", minute)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val t = now / 1_000_000_000f
        canvas.drawColor(Color.BLACK)
        updateTime()

        val tiltX = tilt.slideX.coerceIn(-1f, 1f)
        val tiltY = tilt.slideY.coerceIn(-1f, 1f)
        val shiftX = tiltX * 5f * d
        val shiftY = tiltY * 4f * d
        canvas.save()
        canvas.translate(shiftX, shiftY)
        textPaint.textSize = textSizePx
        textPaint.typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)

        // Individual digit colours, matching the pink / lilac / purple reference.
        val hourDigits = hourText.toCharArray()
        val minuteDigits = minuteText.toCharArray()
        val hourTextWidth = textPaint.measureText(hourText)
        val minuteTextWidth = textPaint.measureText(minuteText)
        val leftHour = hourX - hourWidth / 2f
        drawDigitRun(canvas, hourText, leftHour, baseline, colors[0], colors[1])
        drawDigitRun(canvas, minuteText, minuteX - minuteWidth / 2f, baseline, colors[1], colors[2])

        // Two soft grey-lilac dots; their opacity gently breathes.
        val pulse = 0.72f + 0.20f * (0.5f + 0.5f * sin(t * 2.0f * PI.toFloat()))
        val dotColor = (pulse * 255).toInt().coerceIn(0,255)
        dotPaint.shader = LinearGradient(colonX - dotRadius, 0f, colonX + dotRadius, 0f,
            0xFF8B8B8B.toInt(), 0xFFC9B9FF.toInt(), Shader.TileMode.CLAMP)
        val dotGap = textSizePx * 0.31f
        canvas.drawCircle(colonX, baseline - dotGap, dotRadius, dotPaint)
        canvas.drawCircle(colonX, baseline + dotGap, dotRadius, dotPaint)
        dotPaint.shader = null

        if (ampm.isNotEmpty()) {
            textPaint.textSize = textSizePx * 0.095f
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.color = 0xFFB8A7D8.toInt()
            canvas.drawText(ampm, width - 24f * d, height - 22f * d, textPaint)
            textPaint.textAlign = Paint.Align.CENTER
        }
        canvas.restore()

        // Tiny charging shimmer near the bottom edge, without distracting from the clock.
        if (plugged && !full) {
            val shimmer = 0.25f + 0.35f * (0.5f + 0.5f * sin(t * 2.4f))
            glowPaint.color = (shimmer * 255).toInt().shl(24) or 0x00BFA6FF
            val barW = width * 0.16f
            val barH = max(2f * d, 3f * d)
            canvas.drawRoundRect(width / 2f - barW / 2f, height - 8f * d,
                width / 2f + barW / 2f, height - 8f * d + barH, barH, barH, glowPaint)
        }
        if (running) postInvalidateDelayed(33L)
    }

    private fun drawDigitRun(canvas: Canvas, value: String, x: Float, y: Float, first: Int, second: Int) {
        textPaint.shader = LinearGradient(x, y - textSizePx * 0.52f, x, y + textSizePx * 0.42f,
            intArrayOf(first, second), null, Shader.TileMode.CLAMP)
        textPaint.setShadowLayer(textSizePx * 0.018f, 0f, textSizePx * 0.008f, 0x553E1C58)
        canvas.drawText(value, x, y, textPaint)
        textPaint.clearShadowLayer()
        textPaint.shader = null
    }
}
