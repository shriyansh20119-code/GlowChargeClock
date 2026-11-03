package com.glowchargeclock

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.graphics.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sin

class MainActivity : Activity(), SensorEventListener {
    private lateinit var clockView: ClockView
    private var sensorManager: SensorManager? = null
    private var gyro: Sensor? = null
    private var tiltX = 0f
    private var tiltY = 0f
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { clockView.invalidate(); handler.postDelayed(this, 1000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        clockView = ClockView(this) { tiltX to tiltY }
        setContentView(clockView)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        handler.post(tick)
    }

    override fun onResume() { super.onResume(); gyro?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    override fun onPause() { sensorManager?.unregisterListener(this); super.onPause() }
    override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            tiltX = (tiltX + event.values[1] * 0.025f).coerceIn(-1f, 1f)
            tiltY = (tiltY + event.values[0] * 0.025f).coerceIn(-1f, 1f)
            clockView.invalidate()
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private class ClockView(context: Context, val tilt: () -> Pair<Float, Float>) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textAlign = Paint.Align.CENTER; textSize = 22f; letterSpacingCompat() }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF; style = Paint.Style.STROKE; strokeWidth = 2f }
        private val stars = List(42) { Pair(Math.random().toFloat(), Math.random().toFloat()) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            val (tx, ty) = tilt()
            val shiftX = tx * 26f; val shiftY = ty * 20f
            paint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xFF101A43.toInt(), 0xFF472A78.toInt(), 0xFFEF6A91.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint); paint.shader = null
            paint.shader = RadialGradient(w * .78f + shiftX, h * .18f + shiftY, h * .75f, intArrayOf(0xAAFFB86C.toInt(), 0x334FC8FF, 0x00101030), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint); paint.shader = null
            stars.forEachIndexed { i, p ->
                paint.color = if (i % 3 == 0) 0xAAFFFFFF.toInt() else 0x55FFFFFF
                canvas.drawCircle(p.first * w + shiftX * .35f, p.second * h + shiftY * .35f, if (i % 5 == 0) 2.4f else 1.2f, paint)
            }
            val cx = w / 2f; val cy = h / 2f
            glow.color = 0x44FFFFFF
            canvas.drawRoundRect(cx - 330f + shiftX, cy - 175f + shiftY, cx + 330f + shiftX, cy + 175f + shiftY, 34f, 34f, glow)
            val time = SimpleDateFormat("hh:mm", Locale.getDefault()).format(Date())
            title.textSize = h * .27f
            canvas.drawText(time, cx + shiftX * .2f, cy + 38f + shiftY * .2f, title)
            small.textSize = 22f
            val date = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(Date())
            canvas.drawText(date, cx, cy + 91f, small)
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
            small.textSize = 20f
            canvas.drawText("⚡  $level% BATTERY", cx, cy + 139f, small)
            small.textSize = 14f
            canvas.drawText("GLOW CHARGE CLOCK", cx, h - 30f, small)
        }
        private fun Paint.letterSpacingCompat() { }
    }
}

class PowerConnectedReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        if (intent.action == android.content.Intent.ACTION_POWER_CONNECTED) {
            try {
                val launch = android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(launch)
            } catch (_: Exception) { /* Android may block background launches; open the app manually if needed. */ }
        }
    }
}
