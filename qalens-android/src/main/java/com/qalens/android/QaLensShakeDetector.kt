package com.qalens.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class QaLensShakeDetector(private val onShake: () -> Unit) : SensorEventListener {

    private var lastShakeMs = 0L

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        // Normalise to g-force (1g = 9.81 m/s²)
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (gForce > THRESHOLD_G) {
            val now = System.currentTimeMillis()
            if (now - lastShakeMs > COOLDOWN_MS) {
                lastShakeMs = now
                onShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    companion object {
        // ~2.7 g is a deliberate hard shake — avoids accidental triggers while walking
        private const val THRESHOLD_G = 2.7f
        private const val COOLDOWN_MS = 1_200L

        fun register(context: Context, detector: QaLensShakeDetector) {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            sm.registerListener(detector, sensor, SensorManager.SENSOR_DELAY_UI)
        }

        fun unregister(context: Context, detector: QaLensShakeDetector) {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sm.unregisterListener(detector)
        }
    }
}
