package io.github.shohei0205.yamamuki.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.github.shohei0205.yamamuki.core.Heading
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 端末を向けている方位角(磁北基準、0〜360°)を流す。
 * 回転ベクトルセンサーを優先し、無い端末では加速度+地磁気センサーから求める。
 * どちらも無い端末では何も流さない。
 */
fun magneticHeadingUpdates(context: Context): Flow<Double> = callbackFlow {
    val sensorManager = context.getSystemService(SensorManager::class.java)
    val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val matrix = FloatArray(9)
    val gravity = FloatArray(3)
    val geomagnetic = FloatArray(3)
    var hasGravity = false
    var hasGeomagnetic = false

    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val ok = when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    true
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    event.values.copyInto(gravity, endIndex = 3)
                    hasGravity = true
                    hasGeomagnetic && SensorManager.getRotationMatrix(matrix, null, gravity, geomagnetic)
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    event.values.copyInto(geomagnetic, endIndex = 3)
                    hasGeomagnetic = true
                    hasGravity && SensorManager.getRotationMatrix(matrix, null, gravity, geomagnetic)
                }
                else -> false
            }
            if (ok) Heading.azimuthFromRotationMatrix(matrix)?.let { trySend(it) }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    if (rotationVector != null) {
        sensorManager.registerListener(listener, rotationVector, SensorManager.SENSOR_DELAY_GAME)
    } else if (accelerometer != null && magnetometer != null) {
        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_GAME)
    }
    awaitClose { sensorManager.unregisterListener(listener) }
}

/**
 * 方位の精度が低い(ずれているかもしれない)かを流す。方位に使うセンサー(回転ベクトル、無い端末では地磁気)が
 * 知らせる精度が「低い」か「当てにならない」のとき true。センサーが無い端末では何も流さない。
 */
fun headingAccuracyLowUpdates(context: Context): Flow<Boolean> = callbackFlow {
    val sensorManager = context.getSystemService(SensorManager::class.java)
    val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = Unit

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            trySend(accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW)
        }
    }

    if (sensor != null) sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    awaitClose { sensorManager.unregisterListener(listener) }
}
