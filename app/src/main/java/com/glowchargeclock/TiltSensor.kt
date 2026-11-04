package com.glowchargeclock

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Phone ke tilt ko -1..+1 ke "slide" value mein badalta hai (landscape-safe).
 *
 * Sensor priority (runtime par detect hota hai):
 *  1. GAME_ROTATION_VECTOR  (gyro + accelerometer fusion, drift nahi)
 *  2. ACCELEROMETER         (har phone mein hota hai, drift nahi)
 *  3. ROTATION_VECTOR
 *  4. GYROSCOPE             (last resort, leaky integration)
 *  Koi sensor nahi mila -> mode NONE, slide hamesha 0 (static background).
 *
 * Baseline: phone dock par kisi bhi angle par rakha ho, slow baseline usko "center"
 * maan leti hai, isliye drift/unbounded value nahi banti.
 *
 * slideX / slideY = content kis taraf "slide" hona chahiye (canvas direction:
 * +X right, +Y down). Agar real phone par direction ulti lage to sirf SIGN badalna hai.
 */
class TiltSensor(context: Context) : SensorEventListener {

    enum class Mode { NONE, GAME_ROTATION, ACCELEROMETER, ROTATION, GYRO }

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val gameRot: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotVec: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val mode: Mode = when {
        gameRot != null -> Mode.GAME_ROTATION
        accel != null -> Mode.ACCELEROMETER
        rotVec != null -> Mode.ROTATION
        gyro != null -> Mode.GYRO
        else -> Mode.NONE
    }

    private val sensor: Sensor? = when (mode) {
        Mode.GAME_ROTATION -> gameRot
        Mode.ACCELEROMETER -> accel
        Mode.ROTATION -> rotVec
        Mode.GYRO -> gyro
        Mode.NONE -> null
    }

    @Volatile var slideX = 0f
        private set
    @Volatile var slideY = 0f
        private set

    private var started = false
    private var lastTs = 0L
    private var haveBase = false
    private var lastRotation = -1
    private var fastX = 0f
    private var fastY = 0f
    private var baseX = 0f
    private var baseY = 0f
    private var gyroX = 0f
    private var gyroY = 0f
    private val rm = FloatArray(9)
    private val rv = FloatArray(4)

    init {
        // Diagnostic: logcat mein "GlowTilt" tag se dekho phone ke paas kaun-kaun se sensors hain.
        val all = sm.getSensorList(Sensor.TYPE_ALL).joinToString { it.name + " (type " + it.type + ")" }
        Log.i(TAG, "Available sensors: $all")
        Log.i(TAG, "Gyroscope=${gyro != null}, GameRotation=${gameRot != null}, " +
            "RotationVector=${rotVec != null}, Accelerometer=${accel != null} -> using $mode")
    }

    fun start() {
        val s = sensor ?: return
        if (started) return
        lastTs = 0L
        haveBase = false
        started = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        Log.i(TAG, "registerListener result=$started")
    }

    fun stop() {
        if (started) sm.unregisterListener(this)
        started = false
        slideX = 0f
        slideY = 0f
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = wm.defaultDisplay.rotation

    override fun onSensorChanged(e: SensorEvent) {
        val ts = e.timestamp
        var dt = if (lastTs == 0L) 0.02f else (ts - lastTs) / 1_000_000_000f
        lastTs = ts
        dt = dt.coerceIn(0.001f, 0.1f)

        // 1) device frame mein "up" vector (x, y)
        val vx: Float
        val vy: Float
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                val g = sqrt(x * x + y * y + z * z)
                if (g < 1f) return
                vx = x / g; vy = y / g
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                if (e.values.size >= 4) {
                    System.arraycopy(e.values, 0, rv, 0, 4)
                    SensorManager.getRotationMatrixFromVector(rm, rv)
                } else {
                    SensorManager.getRotationMatrixFromVector(rm, e.values)
                }
                vx = rm[6]; vy = rm[7]
            }
            Sensor.TYPE_GYROSCOPE -> {
                val leak = exp(-dt / 1.5f)
                gyroX = gyroX * leak + e.values[1] * dt
                gyroY = gyroY * leak - e.values[0] * dt
                vx = gyroX; vy = gyroY
            }
            else -> return
        }

        // 2) display rotation ke hisaab se screen frame (X right, Y up)
        val rot = displayRotation()
        val sx: Float
        val sy: Float
        when (rot) {
            Surface.ROTATION_90 -> { sx = vy; sy = -vx }
            Surface.ROTATION_180 -> { sx = -vx; sy = -vy }
            Surface.ROTATION_270 -> { sx = -vy; sy = vx }
            else -> { sx = vx; sy = vy }
        }

        if (!haveBase || rot != lastRotation) {
            fastX = sx; fastY = sy; baseX = sx; baseY = sy
            haveBase = true
            lastRotation = rot
        }

        // 3) fast low-pass (smooth) minus slow baseline (auto-center, no drift)
        val aFast = 1f - exp(-dt / 0.07f)
        val aBase = 1f - exp(-dt / 6.0f)
        fastX += aFast * (sx - fastX)
        fastY += aFast * (sy - fastY)
        baseX += aBase * (sx - baseX)
        baseY += aBase * (sy - baseY)

        val tx = ((fastX - baseX) * GAIN).coerceIn(-1f, 1f)
        val ty = ((fastY - baseY) * GAIN).coerceIn(-1f, 1f)

        // right edge raised (tx>0) -> content left slide; top raised (ty>0) -> content down slide
        slideX = -tx
        slideY = ty
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val TAG = "GlowTilt"
        private const val GAIN = 4.0f
    }
}
