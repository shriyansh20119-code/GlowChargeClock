package com.glowchargeclock

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MainActivity : Activity(), LocationListener {

    private lateinit var clockView: LiquidClockView
    private lateinit var tilt: TiltSensor
    private val handler = Handler(Looper.getMainLooper())

    // power
    private var receiverRegistered = false
    private var sawPlugged = false

    // weather
    private var weatherTemp = "--°"
    private var weatherLabel = "Weather loading…"
    private var weatherCode = -1
    private var hasWeather = false
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var nextFetchAt = 0L
    private val fetching = AtomicBoolean(false)
    private var askedPermission = false
    private var listening = false

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_DISCONNECTED -> {
                    Log.i(TAG, "POWER_DISCONNECTED -> closing")
                    finishAndRemoveTask()
                }
                Intent.ACTION_BATTERY_CHANGED -> handleBattery(intent)
            }
        }
    }

    private val refreshWeather = object : Runnable {
        override fun run() {
            if (!lastLat.isNaN()) fetchWeather(lastLat, lastLon) else startLocation()
            handler.postDelayed(this, 10 * 60 * 1000L)
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        showOverLockScreen()
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        hideSystemBars()

        tilt = TiltSensor(this)
        clockView = LiquidClockView(this, tilt)
        setContentView(clockView)
        pushWeather()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        // BATTERY_CHANGED sticky hai: register karte hi current state mil jaati hai
        val sticky = registerReceiver(powerReceiver, filter)
        receiverRegistered = true
        if (sticky != null) handleBattery(sticky)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        tilt.start()
        clockView.start()
        startLocation()
        handler.removeCallbacks(refreshWeather)
        handler.postDelayed(refreshWeather, 10 * 60 * 1000L)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshWeather)
        stopLocation()
        tilt.stop()
        clockView.stop()
        super.onPause()
    }

    override fun onStop() {
        if (receiverRegistered) {
            try { unregisterReceiver(powerReceiver) } catch (_: IllegalArgumentException) {}
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    // ------------------------------------------------------------ battery

    private fun handleBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val full = status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        clockView.setBattery(pct, plugged, full)

        if (plugged) {
            sawPlugged = true
        } else if (sawPlugged) {
            // charger nikal diya -> clock band
            Log.i(TAG, "Unplugged -> closing")
            finishAndRemoveTask()
        }
    }

    // ------------------------------------------------------------ weather / location

    private fun pushWeather() {
        clockView.setWeather(weatherTemp, weatherLabel, weatherCode)
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun setStatus(text: String) {
        // sirf tab dikhao jab abhi tak koi valid weather nahi mila (purana data na mitaye)
        if (!hasWeather) {
            weatherLabel = text
            pushWeather()
        }
    }

    private fun startLocation() {
        if (!hasLocationPermission()) {
            if (!askedPermission) {
                askedPermission = true
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQ_LOCATION
                )
            }
            setStatus("Allow location for weather")
            return
        }
        if (listening) return
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (last != null) fetchWeather(last.latitude, last.longitude)
            val provider = when {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider == null) {
                if (last == null) setStatus("Turn on location for weather")
            } else {
                lm.requestLocationUpdates(provider, 15 * 60 * 1000L, 2000f, this, Looper.getMainLooper())
                listening = true
                if (last == null) setStatus("Finding local weather…")
            }
        } catch (e: SecurityException) {
            setStatus("Location permission needed")
        } catch (e: Exception) {
            Log.w(TAG, "location error", e)
            setStatus("Weather unavailable")
        }
    }

    private fun stopLocation() {
        if (!listening) return
        try {
            (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        } catch (_: Exception) {}
        listening = false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQ_LOCATION) {
            if (results.any { it == PackageManager.PERMISSION_GRANTED }) startLocation()
            else setStatus("Weather unavailable")
        }
    }

    override fun onLocationChanged(location: Location) {
        fetchWeather(location.latitude, location.longitude)
    }

    @Deprecated("Deprecated by Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun fetchWeather(lat: Double, lon: Double) {
        lastLat = lat; lastLon = lon
        if (SystemClock.elapsedRealtime() < nextFetchAt) return
        if (!fetching.compareAndSet(false, true)) return
        thread(name = "weather") {
            var conn: HttpURLConnection? = null
            var ok = false
            try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code&timezone=auto"
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val current = org.json.JSONObject(body).getJSONObject("current")
                val temp = current.getDouble("temperature_2m").roundToInt().toString() + "°"
                val code = current.getInt("weather_code")
                ok = true
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    weatherTemp = temp
                    weatherCode = code
                    weatherLabel = labelFor(code)
                    hasWeather = true
                    pushWeather()
                }
            } catch (e: Exception) {
                Log.w(TAG, "weather fetch failed", e)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    setStatus("Weather unavailable")
                }
            } finally {
                conn?.disconnect()
                // success: 25 min baad; fail: 2 min baad dobara try
                nextFetchAt = SystemClock.elapsedRealtime() + if (ok) 25 * 60 * 1000L else 2 * 60 * 1000L
                fetching.set(false)
            }
        }
    }

    private fun labelFor(code: Int): String = when (code) {
        0 -> "Clear sky"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67 -> "Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Weather"
    }

    companion object {
        private const val TAG = "GlowCharge"
        private const val REQ_LOCATION = 41
    }
}

/**
 * Charger lagte hi clock kholne ki koshish. Android 10+ / MIUI background se activity
 * kholna rok sakte hain — isliye ye "best effort" hai. Result logcat mein "GlowCharge" tag se dikhega.
 */
class PowerConnectedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_POWER_CONNECTED) {
            Log.i("GlowCharge", "POWER_CONNECTED received by manifest receiver")
            try {
                val launch = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(launch)
            } catch (e: Exception) {
                Log.w("GlowCharge", "Launch blocked: ${e.message}")
            }
        }
    }
}
