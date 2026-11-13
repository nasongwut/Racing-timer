package com.tracking.speedtimer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** สถานะของตัวจับเวลาออกตัว (เช่น 0–100 กม./ชม.) */
enum class LaunchState { Idle, Armed, Timing, Done }

data class Lap(val number: Int, val lapMs: Long, val totalMs: Long)

/** ผลการวิ่งที่หยุดอัตโนมัติเมื่อถึงระยะ */
data class RunResult(val meters: Int, val timeMs: Long, val maxSpeedKmh: Float, val trapSpeedKmh: Float)

data class TrackerState(
    val hasPermission: Boolean = false,
    val gpsEnabled: Boolean = true,
    val gpsFix: Boolean = false,
    val accuracyM: Float? = null,
    val satellitesUsed: Int = 0,
    val satellitesTotal: Int = 0,
    /** มีดาวเทียมความถี่ L5/E5a ช่วยคำนวณตำแหน่ง (GPS สองความถี่) */
    val dualFrequency: Boolean = false,
    val hasAccelSensor: Boolean = true,

    // ความเร็ว (จาก GPS)
    val speedKmh: Float = 0f,
    val maxSpeedKmh: Float = 0f,
    val distanceM: Double = 0.0,

    // ความเร่ง (จากเซนเซอร์ accelerometer)
    val accelG: Float = 0f,
    val maxAccelG: Float = 0f,

    // นาฬิกาจับเวลา
    val running: Boolean = false,
    val elapsedMs: Long = 0L,
    val laps: List<Lap> = emptyList(),
    /** เริ่มนาฬิกาจับเวลาอัตโนมัติเมื่อแรง G ถึงค่าที่ตั้งไว้ */
    val autoStartG: Boolean = false,
    val autoStartThresholdG: Float = 0.20f,
    /** หยุดนาฬิกาอัตโนมัติเมื่อถึงระยะที่กำหนด */
    val autoStopEnabled: Boolean = false,
    val autoStopMeters: Int = 402,
    val lastRun: RunResult? = null,

    // ตัวจับเวลาออกตัว
    val targetKmh: Int = 100,
    val launch: LaunchState = LaunchState.Idle,
    val launchLiveMs: Long = 0L,
    val launchResultMs: Long? = null,
    val bestLaunchMs: Map<Int, Long> = emptyMap(),
    val message: String? = null,
) {
    /** ความเร็วเฉลี่ยตลอดช่วงที่จับเวลา */
    val avgSpeedKmh: Float
        get() = if (elapsedMs > 0) (distanceM / (elapsedMs / 1000.0) * 3.6).toFloat() else 0f
}

class SpeedViewModel(app: Application) : AndroidViewModel(app), LocationListener, SensorEventListener {

    companion object {
        /** ต่ำกว่านี้ถือว่าหยุดนิ่ง (ตัด noise ของ GPS) */
        const val STOP_KMH = 2f
        /** ความเร่งที่ถือว่า "ออกตัวแล้ว" (หน่วย g) */
        const val LAUNCH_ACCEL_G = 0.12f
        /** ถ้าเริ่มจับเวลาแล้วแต่ความเร็วยังไม่ขึ้นภายในเวลานี้ ให้รีเซ็ตกลับไปรอใหม่ */
        const val FALSE_START_MS = 4000L
        val TARGETS = listOf(50, 60, 100, 120)
        /** ค่าแรง G ให้เลือกสำหรับเริ่มนาฬิกาอัตโนมัติ */
        val AUTO_START_G = listOf(0.10f, 0.20f, 0.30f, 0.50f)
        /** ระยะสำเร็จรูป (เมตร): 100 ม., 1/8 ไมล์, 1/4 ไมล์, 1 กม. */
        val DISTANCE_PRESETS = listOf(100, 201, 402, 1000)
    }

    private val locationManager = app.getSystemService(LocationManager::class.java)
    private val sensorManager = app.getSystemService(SensorManager::class.java)
    private val linearAccelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rawAccelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _state = MutableStateFlow(
        TrackerState(hasAccelSensor = linearAccelSensor != null || rawAccelSensor != null)
    )
    val state: StateFlow<TrackerState> = _state.asStateFlow()

    private var listening = false
    private var lastLocation: Location? = null

    // accelerometer
    private val gravity = FloatArray(3)
    private var gravityInit = false
    private var smoothAccel = 0f
    private var lastAccelUiNanos = 0L

    // stopwatch
    private var accumulatedMs = 0L
    private var startedAtMs = 0L

    // ระยะทาง: คำนวณจากความเร็ว Doppler ของ GPS × เวลา (แม่นกว่าการวัดระยะระหว่างพิกัด)
    private var distAnchorNanos = 0L
    private var prevFixNanos = 0L
    private var prevFixSpeedMs = 0.0

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            var l5 = false
            for (i in 0 until status.satelliteCount) {
                if (!status.usedInFix(i)) continue
                used++
                if (status.hasCarrierFrequencyHz(i)) {
                    val f = status.getCarrierFrequencyHz(i)
                    if (f in 1.160e9f..1.195e9f) l5 = true   // L5 / E5a / B2a ≈ 1176 MHz
                }
            }
            _state.update {
                it.copy(satellitesUsed = used, satellitesTotal = status.satelliteCount, dualFrequency = l5)
            }
        }
    }

    // launch timer (เวลาอ้างอิงเป็น elapsedRealtimeNanos ทั้งจาก GPS และเซนเซอร์)
    private var launchStartNanos = 0L
    private var prevSpeedKmh = 0f
    private var prevSpeedNanos = 0L

    init {
        // ตัว tick สำหรับอัปเดตตัวเลขเวลาบนหน้าจอ (~30 fps)
        viewModelScope.launch {
            while (isActive) {
                tick()
                delay(33)
            }
        }
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (!hasLocationPermission()) {
            _state.update { it.copy(hasPermission = false) }
            return
        }
        _state.update {
            it.copy(
                hasPermission = true,
                gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER),
            )
        }
        if (listening) return
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, 0L, 0f, this, Looper.getMainLooper()
        )
        locationManager.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
        (linearAccelSensor ?: rawAccelSensor)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        listening = true
    }

    fun stopListening() {
        if (!listening) return
        locationManager.removeUpdates(this)
        locationManager.unregisterGnssStatusCallback(gnssCallback)
        sensorManager.unregisterListener(this)
        listening = false
        lastLocation = null
        prevFixNanos = 0L
        _state.update { it.copy(gpsFix = false, speedKmh = 0f, accelG = 0f, satellitesUsed = 0) }
    }

    override fun onCleared() {
        stopListening()
        super.onCleared()
    }

    // ---------------------------------------------------------------- นาฬิกาจับเวลา

    fun toggleStopwatch() {
        val now = SystemClock.elapsedRealtime()
        if (_state.value.running) {
            accumulatedMs += now - startedAtMs
            _state.update { it.copy(running = false, elapsedMs = accumulatedMs) }
        } else {
            startedAtMs = now
            distAnchorNanos = SystemClock.elapsedRealtimeNanos()
            _state.update { it.copy(running = true) }
        }
    }

    fun toggleAutoStart() {
        _state.update { it.copy(autoStartG = !it.autoStartG) }
    }

    fun setAutoStartThreshold(g: Float) {
        _state.update { it.copy(autoStartThresholdG = g) }
    }

    fun toggleAutoStop() {
        _state.update { it.copy(autoStopEnabled = !it.autoStopEnabled) }
    }

    fun setAutoStopMeters(m: Int) {
        if (m in 1..100_000) _state.update { it.copy(autoStopMeters = m) }
    }

    /** เริ่มนาฬิกา ณ เวลาที่เซนเซอร์ตรวจพบ (หน่วย ms ของ elapsedRealtime) */
    private fun autoStartStopwatch(atNanos: Long, g: Float) {
        accumulatedMs = 0L
        startedAtMs = atNanos / 1_000_000
        distAnchorNanos = atNanos
        _state.update {
            it.copy(
                running = true,
                message = String.format(java.util.Locale.US, "เริ่มจับเวลาอัตโนมัติ (แรง G %.2f g)", g),
            )
        }
    }

    fun lap() {
        val s = _state.value
        if (!s.running) return
        val total = currentElapsed()
        val prevTotal = s.laps.firstOrNull()?.totalMs ?: 0L
        val lap = Lap(number = s.laps.size + 1, lapMs = total - prevTotal, totalMs = total)
        _state.update { it.copy(laps = listOf(lap) + it.laps) }
    }

    fun resetStopwatch() {
        accumulatedMs = 0L
        startedAtMs = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(
                running = false, elapsedMs = 0L, laps = emptyList(),
                maxSpeedKmh = 0f, distanceM = 0.0, maxAccelG = 0f, lastRun = null,
            )
        }
    }

    private fun currentElapsed(): Long =
        if (_state.value.running) accumulatedMs + (SystemClock.elapsedRealtime() - startedAtMs) else accumulatedMs

    // ---------------------------------------------------------------- ตัวจับเวลาออกตัว

    fun setTarget(kmh: Int) {
        if (_state.value.launch == LaunchState.Timing) return
        _state.update { it.copy(targetKmh = kmh, launch = LaunchState.Idle, launchResultMs = null, launchLiveMs = 0L) }
    }

    fun armLaunch() {
        val s = _state.value
        if (!s.gpsFix) {
            _state.update { it.copy(message = "รอสัญญาณ GPS ก่อน") }
            return
        }
        if (s.speedKmh >= STOP_KMH) {
            _state.update { it.copy(message = "ต้องหยุดนิ่งก่อนจึงจะเริ่มได้") }
            return
        }
        _state.update {
            it.copy(launch = LaunchState.Armed, launchLiveMs = 0L, launchResultMs = null, message = null)
        }
    }

    fun cancelLaunch() {
        _state.update { it.copy(launch = LaunchState.Idle, launchLiveMs = 0L) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun beginLaunch(nanos: Long) {
        launchStartNanos = nanos
        prevSpeedKmh = 0f
        prevSpeedNanos = nanos
        _state.update { it.copy(launch = LaunchState.Timing, launchLiveMs = 0L) }
    }

    private fun handleLaunchSpeed(speedKmh: Float, nanos: Long) {
        val s = _state.value
        when (s.launch) {
            // ถ้าเซนเซอร์ความเร่งยังไม่จับได้ แต่ GPS เห็นว่าเริ่มเคลื่อนที่แล้ว ก็เริ่มจับเวลา
            LaunchState.Armed -> if (speedKmh >= STOP_KMH) beginLaunch(nanos)

            LaunchState.Timing -> {
                val target = s.targetKmh.toFloat()
                if (speedKmh >= target) {
                    // ประมาณเวลาที่ข้ามความเร็วเป้าหมายจริงด้วยการ interpolate ระหว่างจุด GPS สองจุด
                    val crossNanos =
                        if (speedKmh > prevSpeedKmh && nanos > prevSpeedNanos) {
                            val frac = (target - prevSpeedKmh) / (speedKmh - prevSpeedKmh)
                            prevSpeedNanos + (frac * (nanos - prevSpeedNanos)).toLong()
                        } else nanos
                    val resultMs = ((crossNanos - launchStartNanos) / 1_000_000).coerceAtLeast(0)
                    val key = s.targetKmh
                    val best = s.bestLaunchMs[key]
                    val newBest = if (best == null || resultMs < best) s.bestLaunchMs + (key to resultMs) else s.bestLaunchMs
                    _state.update {
                        it.copy(
                            launch = LaunchState.Done, launchResultMs = resultMs,
                            launchLiveMs = resultMs, bestLaunchMs = newBest,
                        )
                    }
                }
                prevSpeedKmh = speedKmh
                prevSpeedNanos = nanos
            }

            else -> Unit
        }
    }

    // ---------------------------------------------------------------- tick

    private fun tick() {
        val s = _state.value
        val elapsed = currentElapsed()
        var launch = s.launch
        var live = s.launchLiveMs
        if (s.launch == LaunchState.Timing) {
            live = (SystemClock.elapsedRealtimeNanos() - launchStartNanos) / 1_000_000
            // ออกตัวหลอก (เช่น มือถือสั่น) — กลับไปรอใหม่
            if (live > FALSE_START_MS && s.speedKmh < STOP_KMH) {
                launch = LaunchState.Armed
                live = 0L
            }
        }
        if (elapsed != s.elapsedMs || live != s.launchLiveMs || launch != s.launch) {
            _state.update { it.copy(elapsedMs = elapsed, launchLiveMs = live, launch = launch) }
        }
    }

    // ---------------------------------------------------------------- GPS

    override fun onLocationChanged(location: Location) {
        val prev = lastLocation
        var speedKmh = when {
            location.hasSpeed() -> location.speed * 3.6f
            prev != null -> {
                val dt = (location.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
                if (dt > 0) (prev.distanceTo(location) / dt * 3.6).toFloat() else 0f
            }
            else -> 0f
        }
        if (speedKmh < 1f) speedKmh = 0f

        val s = _state.value
        var distance = s.distanceM
        var max = s.maxSpeedKmh
        val t1 = location.elapsedRealtimeNanos
        val v1 = speedKmh / 3.6
        var stopAtNanos: Long? = null

        if (s.running) {
            if (speedKmh > max) max = speedKmh
            if (prevFixNanos > 0L) {
                // นับเฉพาะช่วงหลังเริ่มจับเวลา
                val from = maxOf(prevFixNanos, distAnchorNanos)
                if (t1 > from) {
                    val dt = (t1 - from) / 1e9
                    val segment = (prevFixSpeedMs + v1) / 2 * dt   // trapezoid
                    val target = s.autoStopMeters.toDouble()
                    if (s.autoStopEnabled && distance < target && segment > 0 && distance + segment >= target) {
                        // หาเวลาที่ข้ามเส้นระยะพอดี
                        val frac = (target - distance) / segment
                        stopAtNanos = from + (frac * (t1 - from)).toLong()
                        distance = target
                    } else {
                        distance += segment
                    }
                }
            }
        }
        prevFixNanos = t1
        prevFixSpeedMs = v1
        lastLocation = location

        var result = s.lastRun
        var stopNow = false
        if (stopAtNanos != null) {
            accumulatedMs += (stopAtNanos / 1_000_000) - startedAtMs
            stopNow = true
            result = RunResult(s.autoStopMeters, accumulatedMs, max, speedKmh)
        }

        _state.update {
            it.copy(
                gpsFix = true,
                gpsEnabled = true,
                accuracyM = if (location.hasAccuracy()) location.accuracy else null,
                speedKmh = speedKmh,
                maxSpeedKmh = max,
                distanceM = distance,
                running = if (stopNow) false else it.running,
                elapsedMs = if (stopNow) accumulatedMs else it.elapsedMs,
                lastRun = result,
                message = if (stopNow) "ถึง ${s.autoStopMeters} ม. — หยุดจับเวลาอัตโนมัติ" else it.message,
            )
        }
        handleLaunchSpeed(speedKmh, location.elapsedRealtimeNanos)
    }

    override fun onProviderEnabled(provider: String) {
        _state.update { it.copy(gpsEnabled = true) }
    }

    override fun onProviderDisabled(provider: String) {
        _state.update { it.copy(gpsEnabled = false, gpsFix = false, speedKmh = 0f) }
    }

    // ต้อง override ไว้ ไม่งั้น Android 10 ลงไปอาจ crash (AbstractMethodError)
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    // ---------------------------------------------------------------- Accelerometer

    override fun onSensorChanged(event: SensorEvent) {
        val x: Float
        val y: Float
        val z: Float
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            x = event.values[0]; y = event.values[1]; z = event.values[2]
        } else {
            // ไม่มี linear acceleration — แยกแรงโน้มถ่วงออกด้วย low-pass filter
            if (!gravityInit) {
                event.values.copyInto(gravity, 0, 0, 3)
                gravityInit = true
            }
            val a = 0.9f
            for (i in 0..2) gravity[i] = a * gravity[i] + (1 - a) * event.values[i]
            x = event.values[0] - gravity[0]
            y = event.values[1] - gravity[1]
            z = event.values[2] - gravity[2]
        }
        val magnitude = sqrt(x * x + y * y + z * z)
        smoothAccel = 0.8f * smoothAccel + 0.2f * magnitude
        val g = smoothAccel / SensorManager.GRAVITY_EARTH

        val s = _state.value
        if (s.launch == LaunchState.Armed && g >= LAUNCH_ACCEL_G) {
            beginLaunch(event.timestamp)
        }

        // เริ่มนาฬิกาอัตโนมัติ: เฉพาะตอนที่นาฬิกาอยู่ที่ 0 (หลังรีเซ็ต) — ไม่เดินต่อเองตอนกดหยุดไว้
        if (s.autoStartG && !s.running && accumulatedMs == 0L && g >= s.autoStartThresholdG) {
            autoStartStopwatch(event.timestamp, g)
        }

        // อัปเดตหน้าจอไม่เกิน ~10 ครั้ง/วินาที
        if (event.timestamp - lastAccelUiNanos > 100_000_000L) {
            lastAccelUiNanos = event.timestamp
            val maxG = if (s.running && g > s.maxAccelG) g else s.maxAccelG
            _state.update { it.copy(accelG = g, maxAccelG = maxG) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
