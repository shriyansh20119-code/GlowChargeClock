package com.glowchargeclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val SEG = 96

/**
 * Liquid Glass charging clock.
 * 4 glass tiles (Time, Date, Battery, Weather). Tiles halka sa "liquid" wobble karte hain,
 * tilt par alag-alag depth se slide karte hain, aur battery tile ke andar asli liquid hai
 * jo phone tilt karne par slosh karta hai.
 */
class LiquidClockView(context: Context, private val tilt: TiltSensor) : View(context) {

    private class Tile(val depth: Float, val phase: Float) {
        var cx = 0f; var cy = 0f; var hw = 0f; var hh = 0f; var radius = 0f
        val ptsX = FloatArray(SEG); val ptsY = FloatArray(SEG)
        val nrmX = FloatArray(SEG); val nrmY = FloatArray(SEG)
        val path = Path()
        var fill: Shader? = null
        var border: Shader? = null
        var shine: Shader? = null
    }

    private val d = resources.displayMetrics.density
    private val startNs = System.nanoTime()
    private var lastFrame = 0L
    private var running = false

    // ---- data ----
    private var battery = 0
    private var plugged = false
    private var full = false
    private var tempText = "--°"
    private var conditionText = "Weather loading…"
    private var weatherCode = -1

    // ---- tilt spring (inertia = liquid feel) ----
    private var px = 0f; private var py = 0f
    private var vx = 0f; private var vy = 0f

    // ---- clock cache ----
    private val cal = Calendar.getInstance()
    private var lastSec = -1L
    private var lastDayKey = -1
    private var ampm = ""
    private val digits = arrayOf("0", "0", "0", "0")
    private val weekdayFmt = SimpleDateFormat("EEEE", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
    private var weekday = ""
    private var dateText = ""
    private var secFrac = 0f

    // ---- tiles ----
    private val timeTile = Tile(1.0f, 0.0f)
    private val dateTile = Tile(0.7f, 1.7f)
    private val batTile = Tile(1.3f, 3.1f)
    private val wxTile = Tile(0.95f, 4.6f)

    // ---- paints ----
    private val pBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBlob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30000000; style = Paint.Style.FILL }
    private val pBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d }
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTime = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val pBig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        setShadowLayer(6f * d, 0f, 2f * d, 0x66000000)
    }
    private val pMid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        color = 0xD9FFFFFF.toInt()
    }
    private val pSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = 0xCCFFFFFF.toInt()
        letterSpacing = 0.14f
    }
    private val pLiquid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pIcon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- reusable objects ----
    private val tmpPath = Path()
    private val liquidPath = Path()
    private val measure = PathMeasure()
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)
    private val rr = RectF()
    private val bounds = Rect()
    private val bolt = Path().apply {
        moveTo(0.12f, -0.50f); lineTo(-0.28f, 0.06f); lineTo(-0.02f, 0.06f)
        lineTo(-0.12f, 0.50f); lineTo(0.28f, -0.10f); lineTo(0.02f, -0.10f); close()
    }

    // ---- cached geometry / shaders ----
    private var bgShader: Shader? = null
    private var timeGlow: Shader? = null
    private var timeTextShader: Shader? = null
    private var liquidShader: Shader? = null
    private var liquidState = -1
    private var digitW = 0f
    private var colonW = 0f
    private var digitH = 0f
    private var digitBaseline = 0f

    private class Blob(val fx: Float, val fy: Float, val r: Float, val speed: Float, val depth: Float, val color: Int) {
        var shader: Shader? = null
    }
    private val blobs = listOf(
        Blob(0.12f, 0.25f, 0.46f, 0.21f, 34f, 0xFF35C8FF.toInt()),
        Blob(0.80f, 0.20f, 0.42f, 0.17f, 46f, 0xFFFF4FB8.toInt()),
        Blob(0.55f, 0.85f, 0.50f, 0.13f, 28f, 0xFF7B5CFF.toInt()),
        Blob(0.25f, 0.88f, 0.34f, 0.25f, 52f, 0xFF19E3B1.toInt()),
        Blob(0.92f, 0.78f, 0.38f, 0.19f, 40f, 0xFFFFA24C.toInt()),
        Blob(0.45f, 0.10f, 0.30f, 0.23f, 58f, 0xFF4F7CFF.toInt())
    )

    private val pCount = 30
    private val pxFrac = FloatArray(pCount) { (it * 0.6180339f + 0.13f) % 1f }
    private val pSpeed = FloatArray(pCount) { 0.045f + (it % 7) * 0.012f }
    private val pPhase = FloatArray(pCount) { (it * 0.37f) % 1f }
    private val pSize = FloatArray(pCount) { 1.6f + (it % 4) * 0.9f }

    // =========================================================== public API

    fun setBattery(level: Int, isPlugged: Boolean, isFull: Boolean) {
        battery = level.coerceIn(0, 100)
        plugged = isPlugged
        full = isFull
    }

    fun setWeather(temp: String, condition: String, code: Int) {
        tempText = temp
        conditionText = condition
        weatherCode = code
    }

    fun start() {
        if (running) return
        running = true
        lastFrame = 0L
        invalidate()
    }

    fun stop() {
        running = false
    }

    // =========================================================== layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wf = w.toFloat(); val hf = h.toFloat()

        bgShader = LinearGradient(
            0f, 0f, wf, hf,
            intArrayOf(0xFF061230.toInt(), 0xFF1D2F7A.toInt(), 0xFF4A2A86.toInt(), 0xFF0F2A55.toInt()),
            floatArrayOf(0f, 0.38f, 0.72f, 1f), Shader.TileMode.CLAMP
        )
        for (b in blobs) {
            val r = hf * b.r
            b.shader = RadialGradient(
                0f, 0f, r,
                intArrayOf((b.color and 0x00FFFFFF) or 0x99000000.toInt(), (b.color and 0x00FFFFFF) or 0x33000000, 0x00000000),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
        }

        val mx = max(wf * 0.045f, 16f * d)
        val my = max(hf * 0.085f, 14f * d)
        val gap = hf * 0.04f
        val availW = wf - 2 * mx
        val availH = hf - 2 * my
        val leftW = availW * 0.64f
        val rightW = availW - leftW - gap
        val timeH = availH * 0.64f
        val dateH = availH - timeH - gap
        val batH = (availH - gap) * 0.56f
        val wxH = availH - batH - gap
        layoutTile(timeTile, mx, my, leftW, timeH)
        layoutTile(dateTile, mx, my + timeH + gap, leftW, dateH)
        layoutTile(batTile, mx + leftW + gap, my, rightW, batH)
        layoutTile(wxTile, mx + leftW + gap, my + batH + gap, rightW, wxH)

        // time digits sizing
        val ref = 100f
        pTime.textSize = ref
        var dwRef = 0f
        for (c in '0'..'9') dwRef = max(dwRef, pTime.measureText(c.toString()))
        pTime.getTextBounds("0", 0, 1, bounds)
        val hRef = bounds.height().toFloat().coerceAtLeast(1f)
        val sizeByW = ref * (timeTile.hw * 2f * 0.84f) / (4.5f * dwRef)
        val sizeByH = ref * (timeTile.hh * 2f * 0.60f) / hRef
        pTime.textSize = min(sizeByW, sizeByH)
        var dw = 0f
        for (c in '0'..'9') dw = max(dw, pTime.measureText(c.toString()))
        digitW = dw
        colonW = dw * 0.5f
        pTime.getTextBounds("0", 0, 1, bounds)
        digitH = bounds.height().toFloat()
        digitBaseline = -(bounds.top + bounds.bottom) / 2f
        timeTextShader = LinearGradient(
            0f, -digitH / 2f, 0f, digitH / 2f,
            0xFFFFFFFF.toInt(), 0xFFB9E0FF.toInt(), Shader.TileMode.CLAMP
        )
        timeGlow = RadialGradient(
            0f, 0f, timeTile.hw * 0.75f,
            intArrayOf(0x4D6FD8FF, 0x00000000), null, Shader.TileMode.CLAMP
        )
        liquidState = -1
    }

    private fun layoutTile(t: Tile, x: Float, y: Float, w: Float, h: Float) {
        t.cx = x + w / 2f; t.cy = y + h / 2f
        t.hw = w / 2f; t.hh = h / 2f
        t.radius = min(w, h) * 0.17f
        rr.set(-t.hw, -t.hh, t.hw, t.hh)
        tmpPath.reset()
        tmpPath.addRoundRect(rr, t.radius, t.radius, Path.Direction.CW)
        measure.setPath(tmpPath, true)
        val len = measure.length
        for (i in 0 until SEG) {
            measure.getPosTan(len * i / SEG, pos, tan)
            t.ptsX[i] = pos[0]; t.ptsY[i] = pos[1]
            t.nrmX[i] = tan[1]; t.nrmY[i] = -tan[0]
        }
        t.fill = LinearGradient(
            0f, -t.hh, 0f, t.hh,
            intArrayOf(0x40FFFFFF, 0x14FFFFFF, 0x26FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        t.border = LinearGradient(
            -t.hw, -t.hh, t.hw, t.hh,
            intArrayOf(0xCCFFFFFF.toInt(), 0x33FFFFFF, 0x14FFFFFF, 0x99FFFFFF.toInt()),
            floatArrayOf(0f, 0.4f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        t.shine = RadialGradient(
            0f, 0f, max(t.hw, t.hh) * 0.9f,
            intArrayOf(0x4DFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP
        )
    }

    // =========================================================== drawing

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        var dt = if (lastFrame == 0L) 0.033f else (now - lastFrame) / 1_000_000_000f
        lastFrame = now
        dt = dt.coerceIn(0.001f, 0.05f)
        val t = (now - startNs) / 1_000_000_000f

        stepSpring(dt)
        refreshClock()

        val w = width.toFloat(); val h = height.toFloat()
        pBg.shader = bgShader
        canvas.drawRect(0f, 0f, w, h, pBg)
        drawBlobs(canvas, w, h, t)
        val effects = plugged && !full
        if (effects) drawParticles(canvas, w, h, t)

        drawTimeTile(canvas, t)
        drawDateTile(canvas, t)
        drawBatteryTile(canvas, t, effects)
        drawWeatherTile(canvas, t)

        if (running) postInvalidateDelayed(33L)
    }

    private fun stepSpring(dt: Float) {
        val k = 70f; val c = 10f
        vx += (k * (tilt.slideX - px) - c * vx) * dt
        px += vx * dt
        vy += (k * (tilt.slideY - py) - c * vy) * dt
        py += vy * dt
    }

    private fun refreshClock() {
        val ms = System.currentTimeMillis()
        secFrac = (ms % 1000L) / 1000f
        val sec = ms / 1000L
        if (sec == lastSec) return
        lastSec = sec
        cal.timeInMillis = ms
        var hour = cal.get(Calendar.HOUR_OF_DAY)
        val min = cal.get(Calendar.MINUTE)
        val is24 = DateFormat.is24HourFormat(context)
        ampm = if (is24) "" else if (hour < 12) "AM" else "PM"
        if (!is24) { hour %= 12; if (hour == 0) hour = 12 }
        digits[0] = (hour / 10).toString(); digits[1] = (hour % 10).toString()
        digits[2] = (min / 10).toString(); digits[3] = (min % 10).toString()
        val dayKey = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        if (dayKey != lastDayKey) {
            lastDayKey = dayKey
            val date = cal.time
            weekday = weekdayFmt.format(date).uppercase(Locale.getDefault())
            dateText = dateFmt.format(date)
        }
    }

    private fun drawBlobs(c: Canvas, w: Float, h: Float, t: Float) {
        for ((i, b) in blobs.withIndex()) {
            val x = b.fx * w + sin(t * b.speed * 2f + i) * w * 0.05f - px * b.depth * d
            val y = b.fy * h + cos(t * b.speed * 1.6f + i * 1.3f) * h * 0.06f + py * b.depth * d
            c.save()
            c.translate(x, y)
            pBlob.shader = b.shader
            c.drawCircle(0f, 0f, h * b.r, pBlob)
            c.restore()
        }
        pBlob.shader = null
    }

    private fun drawParticles(c: Canvas, w: Float, h: Float, t: Float) {
        for (i in 0 until pCount) {
            val f = (t * pSpeed[i] + pPhase[i]) % 1f
            val x = pxFrac[i] * w + sin(t * 0.8f + pPhase[i] * 6.28f) * 14f * d - px * 26f * d
            val y = h * (1f - f) + py * 20f * d
            val a = sin(PI.toFloat() * f)
            val r = pSize[i] * d
            val base = when (i % 3) { 0 -> 0x9AF5FF; 1 -> 0xFFFFFF; else -> 0xFFB6E8 }
            pDot.color = ((a * 0x30).toInt() shl 24) or base
            c.drawCircle(x, y, r * 3.2f, pDot)
            pDot.color = ((a * 0xCC).toInt() shl 24) or base
            c.drawCircle(x, y, r, pDot)
        }
    }

    /** Tile ka wobbly "liquid" outline path banata hai (local coords, center = 0,0). */
    private fun buildPath(tile: Tile, t: Float, amp: Float) {
        val p = tile.path
        p.reset()
        val k1 = 2f * PI.toFloat() * 3f / SEG
        val k2 = 2f * PI.toFloat() * 5f / SEG
        for (i in 0 until SEG) {
            val off = amp * (sin(i * k1 + t * 1.3f + tile.phase) + 0.6f * sin(i * k2 - t * 1.9f + tile.phase * 2f))
            val x = tile.ptsX[i] + tile.nrmX[i] * off
            val y = tile.ptsY[i] + tile.nrmY[i] * off
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
    }

    private fun slosh(): Float = min(1.5f, (abs(vx) + abs(vy)) * 0.4f)

    /** Tile ko uski depth ke hisaab se shift karke glass body draw karta hai, phir content callback. */
    private inline fun glassTile(c: Canvas, tile: Tile, t: Float, inner: (Float, Float) -> Unit) {
        val ox = px * tile.depth * 14f * d
        val oy = py * tile.depth * 11f * d
        c.save()
        c.translate(tile.cx + ox, tile.cy + oy)
        buildPath(tile, t, d * (1.1f + slosh() * 3f))

        // soft drop shadow (fake, blur-free)
        c.save(); c.translate(0f, 7f * d)
        c.drawPath(tile.path, pShadow)
        c.restore()

        pFill.shader = tile.fill
        c.drawPath(tile.path, pFill)

        c.save()
        c.clipPath(tile.path)
        // specular highlight tilt ke saath slide karta hai
        c.save()
        c.translate(-px * tile.hw * 0.55f - tile.hw * 0.25f, -tile.hh * 0.55f + py * tile.hh * 0.3f)
        pGlow.shader = tile.shine
        c.drawCircle(0f, 0f, max(tile.hw, tile.hh) * 0.9f, pGlow)
        c.restore()
        inner(ox, oy)
        c.restore()

        pBorder.shader = tile.border
        c.drawPath(tile.path, pBorder)
        c.restore()
    }

    // ------------------------------------------------------------- TIME

    private fun drawTimeTile(c: Canvas, t: Float) {
        glassTile(c, timeTile, t) { _, _ ->
            val tile = timeTile
            c.save()
            c.translate(-px * 6f * d, -py * 5f * d)   // andar ka content thoda zyada move => depth
            pGlow.shader = timeGlow
            c.drawCircle(0f, 0f, tile.hw * 0.75f, pGlow)

            val total = 4f * digitW + colonW
            val startX = -total / 2f
            pTime.shader = timeTextShader
            pTime.setShadowLayer(14f * d, 0f, 3f * d, 0x886FD8FF.toInt())
            c.drawText(digits[0], startX + digitW * 0.5f, digitBaseline, pTime)
            c.drawText(digits[1], startX + digitW * 1.5f, digitBaseline, pTime)
            val colonX = startX + 2f * digitW + colonW * 0.5f
            c.drawText(digits[2], startX + 2f * digitW + colonW + digitW * 0.5f, digitBaseline, pTime)
            c.drawText(digits[3], startX + 2f * digitW + colonW + digitW * 1.5f, digitBaseline, pTime)
            pTime.clearShadowLayer()
            pTime.shader = null

            // blinking colon (two glowing dots)
            val pulse = 0.55f + 0.45f * (0.5f + 0.5f * cos(2f * PI.toFloat() * secFrac))
            val dotR = digitH * 0.065f
            pDot.color = ((pulse * 0x40).toInt() shl 24) or 0xBFE6FF
            c.drawCircle(colonX, -digitH * 0.2f, dotR * 2.6f, pDot)
            c.drawCircle(colonX, digitH * 0.2f, dotR * 2.6f, pDot)
            pDot.color = ((pulse * 0xFF).toInt() shl 24) or 0xFFFFFF
            c.drawCircle(colonX, -digitH * 0.2f, dotR, pDot)
            c.drawCircle(colonX, digitH * 0.2f, dotR, pDot)

            if (ampm.isNotEmpty()) {
                pSmall.textSize = tile.hh * 0.14f
                pSmall.textAlign = Paint.Align.RIGHT
                c.drawText(ampm, tile.hw - 20f * d, -tile.hh + 20f * d + pSmall.textSize, pSmall)
                pSmall.textAlign = Paint.Align.CENTER
            }
            c.restore()
        }
    }

    // ------------------------------------------------------------- DATE

    private fun drawDateTile(c: Canvas, t: Float) {
        glassTile(c, dateTile, t) { _, _ ->
            val tile = dateTile
            c.save()
            c.translate(-px * 5f * d, -py * 4f * d)
            val maxW = tile.hw * 2f * 0.86f
            pBig.color = Color.WHITE
            fit(pBig, weekday, maxW, tile.hh * 2f * 0.40f)
            pBig.letterSpacing = 0.06f
            c.drawText(weekday, 0f, -tile.hh * 0.06f, pBig)
            pBig.letterSpacing = 0f
            fit(pMid, dateText, maxW, tile.hh * 2f * 0.22f)
            c.drawText(dateText, 0f, tile.hh * 0.56f, pMid)
            c.restore()
        }
    }

    // ------------------------------------------------------------- BATTERY

    private fun liquidStateNow(): Int = when {
        plugged -> 0
        battery <= 15 -> 2
        battery <= 30 -> 3
        else -> 1
    }

    private fun ensureLiquidShader() {
        val s = liquidStateNow()
        if (s == liquidState && liquidShader != null) return
        liquidState = s
        val (top, bottom) = when (s) {
            0 -> 0xCC5BF5D0.toInt() to 0xB01FA6C9.toInt()   // charging: mint -> teal
            2 -> 0xCCFF8F7A.toInt() to 0xB0D93C55.toInt()   // low: red
            3 -> 0xCCFFD27A.toInt() to 0xB0F08A3C.toInt()   // medium-low: amber
            else -> 0xCC8EC5FF.toInt() to 0xB05B6BFF.toInt() // normal: blue
        }
        liquidShader = LinearGradient(0f, -batTile.hh, 0f, batTile.hh, top, bottom, Shader.TileMode.CLAMP)
    }

    private fun drawBatteryTile(c: Canvas, t: Float, effects: Boolean) {
        ensureLiquidShader()
        glassTile(c, batTile, t) { _, _ ->
            val tile = batTile
            val hw = tile.hw; val hh = tile.hh
            val level = max(battery, 4) / 100f
            val y0 = hh - 2f * hh * level
            val sl = slosh()
            val a1 = d * ((if (effects) 3.2f else 2.2f) + sl * 3f)
            val slope = hh * 0.22f
            val step = 10f

            for (layer in 0..1) {
                val phaseShift = if (layer == 0) 0f else 1.7f
                val ampL = if (layer == 0) a1 else a1 * 0.75f
                liquidPath.reset()
                liquidPath.moveTo(-hw - 2f, hh + 2f)
                var x = -hw - 2f
                while (x <= hw + 2f + step) {
                    val tilted = y0 - px * slope * (x / hw)
                    val wave = ampL * sin(x * 0.030f + t * 2.2f + phaseShift) +
                        ampL * 0.5f * sin(x * 0.014f - t * 1.4f + phaseShift)
                    liquidPath.lineTo(x, tilted + wave + (if (layer == 0) 0f else -2.5f * d))
                    x += step
                }
                liquidPath.lineTo(hw + step + 2f, hh + 2f)
                liquidPath.close()
                pLiquid.shader = liquidShader
                pLiquid.alpha = if (layer == 0) 150 else 255
                c.drawPath(liquidPath, pLiquid)
            }
            pLiquid.shader = null

            if (effects) {
                for (i in 0 until 8) {
                    val f = (t * (0.18f + i * 0.02f) + i * 0.173f) % 1f
                    val bx = (-0.8f + 1.6f * ((i * 0.37f) % 1f)) * hw + sin(t + i) * 6f * d
                    val by = hh - f * (hh - y0)
                    val r = d * (1.6f + (i % 3) * 1.2f)
                    pDot.color = ((sin(PI.toFloat() * f) * 0x70).toInt() shl 24) or 0xFFFFFF
                    c.drawCircle(bx, by, r, pDot)
                }
                val pulse = 0.5f + 0.5f * sin(t * 2.6f)
                pGlow.shader = null
                pBorder.shader = null
                pBorder.color = ((pulse * 0x50).toInt() shl 24) or 0x7DF8FF
                pBorder.strokeWidth = (3f + pulse * 3f) * d
                c.drawPath(tile.path, pBorder)
                pBorder.strokeWidth = 1.6f * d
            }

            c.save()
            c.translate(-px * 7f * d, -py * 6f * d)
            val label = when {
                full -> "FULL"
                plugged -> "CHARGING"
                battery <= 15 -> "LOW BATTERY"
                else -> "ON BATTERY"
            }
            val text = "$battery%"
            pBig.color = Color.WHITE
            fit(pBig, text, hw * 2f * 0.80f, hh * 0.78f)
            c.drawText(text, 0f, -hh * 0.10f + pBig.textSize * 0.36f, pBig)

            pSmall.textSize = hh * 0.17f
            val lw = pSmall.measureText(label)
            val boltS = if (plugged) pSmall.textSize * 1.5f else 0f
            val ly = hh * 0.62f
            val shift = if (plugged) boltS * 0.55f else 0f
            c.drawText(label, shift, ly, pSmall)
            if (plugged) drawBolt(c, -lw / 2f + shift - boltS * 0.35f, ly - pSmall.textSize * 0.35f, boltS, 0xFFFFE27A.toInt())
            c.restore()
        }
    }

    private fun drawBolt(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        c.save()
        c.translate(cx, cy)
        c.scale(s, s)
        pIcon.shader = null
        pIcon.style = Paint.Style.FILL
        pIcon.color = color
        c.drawPath(bolt, pIcon)
        c.restore()
    }

    // ------------------------------------------------------------- WEATHER

    private fun drawWeatherTile(c: Canvas, t: Float) {
        glassTile(c, wxTile, t) { _, _ ->
            val tile = wxTile
            val hw = tile.hw; val hh = tile.hh
            c.save()
            c.translate(-px * 7f * d, -py * 6f * d)
            drawWeatherIcon(c, -hw * 0.48f, -hh * 0.02f, min(hw * 0.75f, hh * 1.25f), weatherCode, t)

            val cxText = hw * 0.36f
            val regionW = hw * 0.98f
            pBig.color = Color.WHITE
            fit(pBig, tempText, regionW, hh * 0.80f)
            c.drawText(tempText, cxText, -hh * 0.05f + pBig.textSize * 0.1f, pBig)
            val label = conditionText.uppercase(Locale.getDefault())
            pSmall.textSize = hh * 0.15f
            val lw = pSmall.measureText(label)
            if (lw > regionW) pSmall.textSize = pSmall.textSize * regionW / lw
            c.drawText(label, cxText, hh * 0.58f, pSmall)
            c.restore()
        }
    }

    private fun drawCloud(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        pIcon.shader = null
        pIcon.style = Paint.Style.FILL
        pIcon.color = color
        c.drawCircle(cx - 0.27f * s, cy + 0.06f * s, 0.20f * s, pIcon)
        c.drawCircle(cx - 0.02f * s, cy - 0.08f * s, 0.29f * s, pIcon)
        c.drawCircle(cx + 0.28f * s, cy + 0.06f * s, 0.21f * s, pIcon)
        rr.set(cx - 0.47f * s, cy + 0.02f * s, cx + 0.49f * s, cy + 0.27f * s)
        c.drawRoundRect(rr, 0.13f * s, 0.13f * s, pIcon)
    }

    private fun drawSun(c: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        pIcon.shader = null
        pIcon.style = Paint.Style.FILL
        pIcon.color = 0x33FFD56A
        c.drawCircle(cx, cy, r * 2.2f, pIcon)
        pIcon.color = 0xFFFFD56A.toInt()
        c.drawCircle(cx, cy, r, pIcon)
        pIcon.style = Paint.Style.STROKE
        pIcon.strokeWidth = r * 0.18f
        pIcon.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 8) {
            val a = t * 0.4f + i * (PI.toFloat() / 4f)
            c.drawLine(cx + cos(a) * r * 1.45f, cy + sin(a) * r * 1.45f,
                cx + cos(a) * r * 1.85f, cy + sin(a) * r * 1.85f, pIcon)
        }
        pIcon.style = Paint.Style.FILL
    }

    private fun drawDrops(c: Canvas, cx: Float, cy: Float, s: Float, t: Float, snow: Boolean, long: Boolean) {
        pIcon.shader = null
        for (j in 0 until 3) {
            val ph = (t * 0.9f + j * 0.33f) % 1f
            val x = cx - 0.22f * s + j * 0.22f * s - ph * 0.04f * s
            val y = cy + 0.34f * s + ph * 0.26f * s
            val alpha = (sin(PI.toFloat() * ph) * 255).toInt().coerceIn(0, 255)
            if (snow) {
                pIcon.style = Paint.Style.FILL
                pIcon.color = (alpha shl 24) or 0xFFFFFF
                c.drawCircle(x, y, 0.035f * s, pIcon)
            } else {
                pIcon.style = Paint.Style.STROKE
                pIcon.strokeWidth = 0.045f * s
                pIcon.strokeCap = Paint.Cap.ROUND
                pIcon.color = (alpha shl 24) or 0x9FD8FF
                val len = if (long) 0.12f else 0.07f
                c.drawLine(x, y, x - 0.04f * s, y + len * s, pIcon)
            }
        }
        pIcon.style = Paint.Style.FILL
    }

    private fun drawWeatherIcon(c: Canvas, cx: Float, cy: Float, s: Float, code: Int, t: Float) {
        val bob = sin(t * 1.1f) * s * 0.025f
        val white = 0xF2FFFFFF.toInt()
        when (code) {
            0 -> drawSun(c, cx, cy, s * 0.26f, t)
            1, 2 -> {
                drawSun(c, cx + 0.2f * s, cy - 0.18f * s, s * 0.18f, t)
                drawCloud(c, cx - 0.04f * s, cy + 0.1f * s + bob, s * 0.9f, white)
            }
            3 -> drawCloud(c, cx, cy + bob, s, white)
            45, 48 -> {
                drawCloud(c, cx, cy - 0.08f * s + bob, s * 0.9f, 0xCCFFFFFF.toInt())
                pIcon.style = Paint.Style.STROKE
                pIcon.strokeWidth = 0.05f * s
                pIcon.strokeCap = Paint.Cap.ROUND
                pIcon.color = 0xBBFFFFFF.toInt()
                for (j in 0 until 3) {
                    val yy = cy + 0.28f * s + j * 0.11f * s
                    c.drawLine(cx - 0.3f * s + j * 0.05f * s, yy, cx + 0.3f * s - j * 0.05f * s, yy, pIcon)
                }
                pIcon.style = Paint.Style.FILL
            }
            51, 53, 55, 56, 57 -> {
                drawCloud(c, cx, cy - 0.1f * s + bob, s * 0.9f, white)
                drawDrops(c, cx, cy - 0.1f * s, s * 0.9f, t, snow = false, long = false)
            }
            61, 63, 65, 66, 67, 80, 81, 82 -> {
                drawCloud(c, cx, cy - 0.1f * s + bob, s * 0.9f, white)
                drawDrops(c, cx, cy - 0.1f * s, s * 0.9f, t, snow = false, long = true)
            }
            71, 73, 75, 77, 85, 86 -> {
                drawCloud(c, cx, cy - 0.1f * s + bob, s * 0.9f, white)
                drawDrops(c, cx, cy - 0.1f * s, s * 0.9f, t, snow = true, long = false)
            }
            95, 96, 99 -> {
                drawCloud(c, cx, cy - 0.12f * s + bob, s * 0.9f, 0xEEDDE6FF.toInt())
                drawBolt(c, cx + 0.02f * s, cy + 0.38f * s, s * 0.42f, 0xFFFFE27A.toInt())
            }
            else -> drawCloud(c, cx, cy + bob, s, 0x88FFFFFF.toInt())
        }
    }

    // ------------------------------------------------------------- helpers

    private fun fit(p: Paint, text: String, maxW: Float, size: Float) {
        p.textSize = size
        val w = p.measureText(text)
        if (w > maxW && w > 0f) p.textSize = size * maxW / w
    }
}
