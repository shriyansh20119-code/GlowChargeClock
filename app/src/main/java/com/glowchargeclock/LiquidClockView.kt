package com.glowchargeclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Full-screen clock with animated spectrum digits and a live battery/charging icon. */
class LiquidClockView(context: Context, private val tilt: TiltSensor) : View(context) {

    private val d = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
        isSubpixelText = true
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val batteryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val batteryFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeJoin = Paint.Join.ROUND
    }

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
        invalidate()
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
        dotRadius = size * 0.043f
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
            val h = hour24 % 12
            hourText = (if (h == 0) 12 else h).toString()
        }
        minuteText = String.format(java.util.Locale.getDefault(), "%02d", minute)
    }

    // A complete smooth hue cycle every five seconds; no abrupt palette jumps.
    private fun spectrumColor(t: Float, hueOffset: Float = 0f): Int {
        val hue = ((t * 72f + hueOffset) % 360f + 360f) % 360f
        return Color.HSVToColor(floatArrayOf(hue, 0.48f, 1f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = System.nanoTime() / 1_000_000_000f
        canvas.drawColor(Color.BLACK)
        updateTime()

        drawBatteryIcon(canvas, t)

        canvas.save()
        canvas.translate(
            tilt.slideX.coerceIn(-1f, 1f) * 5f * d,
            tilt.slideY.coerceIn(-1f, 1f) * 4f * d
        )
        textPaint.textSize = textSizePx
        textPaint.typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
        textPaint.textAlign = Paint.Align.CENTER

        drawDigitRun(canvas, hourText, hourX, baseline, t, 0f)
        drawDigitRun(canvas, minuteText, minuteX, baseline, t, 115f)

        // Colon is centred on the clock's optical midpoint, with two compact dots.
        val pulse = 0.82f + 0.12f * (0.5f + 0.5f * sin(t * 2f * PI.toFloat()))
        dotPaint.color = Color.HSVToColor(
            (pulse * 255f).toInt().coerceIn(0, 255),
            floatArrayOf((t * 72f + 205f) % 360f, 0.16f, 0.88f)
        )
        val dotOffset = textSizePx * 0.115f
        canvas.drawCircle(colonX, baseline - dotOffset, dotRadius, dotPaint)
        canvas.drawCircle(colonX, baseline + dotOffset, dotRadius, dotPaint)

        if (ampm.isNotEmpty()) {
            textPaint.shader = null
            textPaint.textSize = textSizePx * 0.095f
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.color = 0xFFB8A7D8.toInt()
            canvas.drawText(ampm, width - 28f * d, height - 24f * d, textPaint)
        }
        canvas.restore()

        if (running) postInvalidateDelayed(33L)
    }

    private fun drawDigitRun(
        canvas: Canvas, value: String, x: Float, y: Float, t: Float, offset: Float
    ) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.shader = LinearGradient(
            x, y - textSizePx * 0.52f, x, y + textSizePx * 0.42f,
            intArrayOf(spectrumColor(t, offset), spectrumColor(t, offset + 65f)),
            null, Shader.TileMode.CLAMP
        )
        textPaint.setShadowLayer(textSizePx * 0.018f, 0f, textSizePx * 0.008f, 0x553E1C58)
        canvas.drawText(value, x, y, textPaint)
        textPaint.clearShadowLayer()
        textPaint.shader = null
    }

    private fun drawBatteryIcon(canvas: Canvas, t: Float) {
        val left = 24f * d
        val top = 22f * d
        val bodyW = 38f * d
        val bodyH = 21f * d
        val corner = 4f * d
        val body = RectF(left, top, left + bodyW, top + bodyH)
        val inner = RectF(left + 3f * d, top + 3f * d, left + bodyW - 3f * d, top + bodyH - 3f * d)

        val levelColor = when {
            battery <= 15 -> Color.rgb(255, 75, 95)
            battery <= 40 -> Color.rgb(255, 174, 66)
            battery <= 70 -> Color.rgb(255, 218, 91)
            else -> Color.rgb(105, 225, 164)
        }
        val fillColor = if (plugged) spectrumColor(t, 150f) else levelColor

        // Battery body outline and positive terminal.
        batteryPaint.color = 0xFFD8D2E5.toInt()
        batteryPaint.alpha = 220
        canvas.drawRoundRect(body, corner, corner, batteryPaint)
        val nub = RectF(left + bodyW + 1f * d, top + bodyH * 0.32f,
            left + bodyW + 4f * d, top + bodyH * 0.68f)
        batteryFillPaint.color = 0xFFD8D2E5.toInt()
        canvas.drawRoundRect(nub, 1.5f * d, 1.5f * d, batteryFillPaint)

        val fraction = if (plugged) 1f else battery / 100f
        val fillRight = inner.left + inner.width() * fraction
        if (fraction > 0f) {
            val fillRect = RectF(inner.left, inner.top, max(inner.left + 1f * d, fillRight), inner.bottom)
            batteryFillPaint.color = fillColor
            batteryFillPaint.alpha = if (plugged && !full) (190 + 65 * (0.5f + 0.5f * sin(t * 5f))).toInt() else 255
            canvas.drawRoundRect(fillRect, 2f * d, 2f * d, batteryFillPaint)
        }
        batteryFillPaint.alpha = 255

        if (plugged) {
            // Bright animated lightning bolt; a travelling glint pulses while charging.
            val pulse = 0.72f + 0.28f * (0.5f + 0.5f * sin(t * 6f))
            boltPaint.color = Color.WHITE
            boltPaint.alpha = (pulse * 255f).toInt()
            val cx = left + bodyW * 0.52f
            val cy = top + bodyH * 0.5f
            val bolt = Path().apply {
                moveTo(cx + 2f * d, cy - 8f * d)
                lineTo(cx - 5f * d, cy + 1f * d)
                lineTo(cx - 1f * d, cy + 1f * d)
                lineTo(cx - 3f * d, cy + 8f * d)
                lineTo(cx + 6f * d, cy - 3f * d)
                lineTo(cx + 1f * d, cy - 3f * d)
                close()
            }
            canvas.drawPath(bolt, boltPaint)
            boltPaint.alpha = 255
        }
    }
}
