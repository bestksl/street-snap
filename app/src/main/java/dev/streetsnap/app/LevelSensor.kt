package dev.streetsnap.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.atan2
import kotlin.math.sqrt

@Composable
fun rememberLevelAngle(enabled: Boolean, preview: View): Float? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var angle by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(enabled, context, lifecycle) {
        val manager = context.getSystemService<SensorManager>()
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                if (sqrt(x * x + y * y) < 2.5f) { angle = null; return }
                val radians = when (preview.display?.rotation ?: Surface.ROTATION_0) {
                    Surface.ROTATION_90 -> atan2(y, -x)
                    Surface.ROTATION_180 -> atan2(-x, -y)
                    Surface.ROTATION_270 -> atan2(-y, x)
                    else -> atan2(x, y)
                }
                var degrees = Math.toDegrees(radians.toDouble()).toFloat()
                while (degrees > 90f) degrees -= 180f
                while (degrees < -90f) degrees += 180f
                angle = degrees
            }
        }
        fun update() {
            manager?.unregisterListener(listener)
            if (enabled && sensor != null && lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            } else angle = null
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            manager?.unregisterListener(listener)
        }
    }
    return if (enabled) angle else null
}
