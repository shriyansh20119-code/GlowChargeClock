package com.glowchargeclock

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.view.View
import android.view.WindowManager
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.*

class MainActivity : Activity(), SensorEventListener, LocationListener {
    private lateinit var clockView: LiquidClockView
    private lateinit var sensors: SensorManager
    private var gyro: Sensor? = null
    private var rotation: Sensor? = null
    private var tiltX = 0f
    private var tiltY = 0f
    private var battery = 0
    private var charging = false
    private var weather = "Weather loading…"
    private var weatherTemp = "--°"
    private var lastWeatherAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            battery = (getSystemService(BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
            clockView.updateData(battery, charging, weatherTemp, weather)
            handler.postDelayed(this, 1000L)
        }
    }
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> charging = true
                Intent.ACTION_POWER_DISCONNECTED -> charging = false
                Intent.ACTION_BATTERY_CHANGED -> {
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                    battery = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, battery)
                }
            }
            clockView.updateData(battery, charging, weatherTemp, weather)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        val initial = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        initial?.let {
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            battery = it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        }
        clockView = LiquidClockView(this) { tiltX to tiltY }
        setContentView(clockView)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        registerReceiver(powerReceiver, filter)
        handler.post(tick)
        requestWeatherLocation()
    }

    override fun onResume() {
        super.onResume()
        gyro?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        if (gyro == null) rotation?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }
    override fun onPause() {
        sensors.unregisterListener(this)
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { unregisterReceiver(powerReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            tiltX = (tiltX + event.values[1] * 0.018f).coerceIn(-1.15f, 1.15f)
            tiltY = (tiltY - event.values[0] * 0.018f).coerceIn(-1.15f, 1.15f)
        } else if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val matrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(matrix, orientation)
            tiltX = (orientation[1] / 0.8f).coerceIn(-1f, 1f)
            tiltY = (orientation[2] / 0.8f).coerceIn(-1f, 1f)
        }
        clockView.invalidate()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onLocationChanged(location: Location) { fetchWeather(location.latitude, location.longitude) }
    @Deprecated("Deprecated by Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun requestWeatherLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 41)
            weather = "Allow location for weather"
            return
        }
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (last != null) fetchWeather(last.latitude, last.longitude)
            val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
            lm.requestLocationUpdates(provider, 30 * 60 * 1000L, 1000f, this, Looper.getMainLooper())
            if (last == null) weather = "Finding local weather…"
        } catch (_: SecurityException) { weather = "Location permission needed" }
        clockView.updateData(battery, charging, weatherTemp, weather)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 41 && results.any { it == PackageManager.PERMISSION_GRANTED }) requestWeatherLocation()
        else { weather = "Weather location unavailable"; clockView.updateData(battery, charging, weatherTemp, weather) }
    }

    private fun fetchWeather(lat: Double, lon: Double) {
        if (System.currentTimeMillis() - lastWeatherAt < 10 * 60 * 1000L) return
        lastWeatherAt = System.currentTimeMillis()
        thread {
            try {
                val endpoint = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto"
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                val json = org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val current = json.getJSONObject("current")
                val temp = current.getDouble("temperature_2m").roundToInt().toString() + "°"
                val condition = weatherLabel(current.getInt("weather_code"))
                connection.disconnect()
                runOnUiThread {
                    weatherTemp = temp
                    weather = condition
                    clockView.updateData(battery, charging, weatherTemp, weather)
                }
            } catch (_: Exception) {
                runOnUiThread { weather = "Weather unavailable"; clockView.updateData(battery, charging, weatherTemp, weather) }
            }
        }
    }

    private fun weatherLabel(code: Int): String = when (code) {
        0 -> "Clear sky"; 1, 2 -> "Partly cloudy"; 3 -> "Cloudy"
        45, 48 -> "Fog"; 51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67 -> "Rain"; 71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"; 85, 86 -> "Snow showers"
        95, 96, 99 -> "Thunderstorm"; else -> "Weather"
    }

    private class LiquidClockView(context: Context, val tilt: () -> Pair<Float, Float>) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
        private val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textAlign = Paint.Align.CENTER }
        private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF; style = Paint.Style.FILL }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1.5f }
        private var battery = 0
        private var charging = false
        private var temp = "--°"
        private var condition = "Weather loading…"
        private val blobs = List(8) { i -> floatArrayOf((i * 0.173f + 0.08f) % 1f, (i * 0.271f + 0.12f) % 1f, 0.7f + (i % 4) * 0.22f) }

        fun updateData(level: Int, isCharging: Boolean, temperature: String, weatherText: String) {
            battery = level; charging = isCharging; temp = temperature; condition = weatherText; invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            val (tx, ty) = tilt()
            val now = System.currentTimeMillis() / 1000f
            p.shader = LinearGradient(0f, 0f, w, h,
                intArrayOf(0xFF07152F.toInt(), 0xFF243B70.toInt(), 0xFF552D72.toInt(), 0xFF142D50.toInt()),
                null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, p); p.shader = null
            blobs.forEachIndexed { i, b ->
                val x = b[0] * w + tx * (24f + i * 3f) + sin(now * (0.35f + i * .04f) + i) * 18f
                val y = b[1] * h + ty * (22f + i * 2f) + cos(now * .4f + i) * 13f
                val radius = h * .16f * b[2]
                p.shader = RadialGradient(x, y, radius,
                    intArrayOf(if (i % 2 == 0) 0x8876E7FF.toInt() else 0x88FF70C8.toInt(), 0x227B65FF, 0x00102040),
                    null, Shader.TileMode.CLAMP)
                canvas.drawCircle(x, y, radius, p); p.shader = null
            }
            val card = RectF(w * .07f, h * .10f, w * .93f, h * .90f)
            canvas.drawRoundRect(card, 42f, 42f, glass)
            canvas.drawRoundRect(card, 42f, 42f, border)

            val cx = w * .5f
            val time = SimpleDateFormat("hh:mm", Locale.getDefault()).format(Date())
            white.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            white.textSize = h * .275f
            white.setShadowLayer(20f, 0f, 5f, 0x8875DFFF.toInt())
            canvas.drawText(time, cx + tx * 10f, h * .48f + ty * 8f, white)
            white.clearShadowLayer()

            muted.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            muted.textSize = h * .040f
            val date = SimpleDateFormat("EEEE  •  dd MMM yyyy", Locale.getDefault()).format(Date())
            canvas.drawText(date, cx, h * .57f, muted)

            val left = w * .30f
            val right = w * .70f
            val rowY = h * .73f
            drawBattery(canvas, left, rowY - h * .025f, h * .058f, now)
            white.typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            white.textSize = h * .075f
            canvas.drawText("$battery%", left + 52f, rowY + 13f, white)
            muted.textSize = h * .026f
            canvas.drawText(if (charging) "CHARGING" else "BATTERY", left + 52f, rowY + h * .055f, muted)

            white.textSize = h * .075f
            canvas.drawText(temp, right - 12f, rowY + 13f, white)
            muted.textSize = h * .026f
            canvas.drawText(condition.uppercase(Locale.getDefault()), right - 12f, rowY + h * .055f, muted)

            if (charging) drawChargeParticles(canvas, w, h, now, tx, ty)
            postInvalidateDelayed(40L)
        }

        private fun drawBattery(canvas: Canvas, x: Float, y: Float, size: Float, time: Float) {
            val r = RectF(x - size * .58f, y - size * .34f, x + size * .58f, y + size * .34f)
            p.color = 0x66FFFFFF; p.style = Paint.Style.STROKE; p.strokeWidth = 3f
            canvas.drawRoundRect(r, 8f, 8f, p)
            canvas.drawRoundRect(RectF(r.right, y - size * .13f, r.right + 6f, y + size * .13f), 3f, 3f, p)
            p.style = Paint.Style.FILL
            val fill = RectF(r.left + 4f, r.top + 4f, r.left + 4f + (r.width() - 8f) * battery / 100f, r.bottom - 4f)
            p.shader = LinearGradient(fill.left, fill.top, fill.right.coerceAtLeast(fill.left + 1f), fill.bottom,
                intArrayOf(0xFF53E6D0.toInt(), 0xFF8BE9FF.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(fill, 5f, 5f, p); p.shader = null
            if (charging) {
                white.textSize = size * .48f
                canvas.drawText("ϟ", x, y + size * .16f, white)
            }
        }

        private fun drawChargeParticles(canvas: Canvas, w: Float, h: Float, t: Float, tx: Float, ty: Float) {
            for (i in 0..17) {
                val phase = (t * (.20f + i * .009f) + i * .137f) % 1f
                val x = (i * 0.618f % 1f) * w + tx * 14f
                val y = h - phase * h + ty * 12f
                p.color = if (i % 3 == 0) 0xCC9AF5FF.toInt() else 0x66FFFFFF
                canvas.drawCircle(x, y, 1.5f + (i % 3), p)
            }
            val pulse = 0.5f + 0.5f * sin(t * 3f)
            border.strokeWidth = 1.5f + pulse * 1.5f
            border.color = 0x447DF8FF
            canvas.drawRoundRect(RectF(w * .07f, h * .10f, w * .93f, h * .90f), 42f, 42f, border)
        }
    }
}

class PowerConnectedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_POWER_CONNECTED) {
            try {
                val launch = Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(launch)
            } catch (_: Exception) { }
        }
    }
}
