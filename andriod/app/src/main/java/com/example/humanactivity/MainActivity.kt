package com.example.humanactivity

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // Latest sensor values.
    private var latestX = 0f
    private var latestY = 0f
    private var latestZ = 0f

    // Reference to our custom chart view.
    private lateinit var chartView: AccelerometerChartView
    // Reference to the TextView for current values.
    private lateinit var currentValuesTextView: TextView

    // Handler and Runnable to sample at 10Hz.
    private val handler = Handler(Looper.getMainLooper())
    private val sampleInterval: Long = 100 // milliseconds (10Hz)

    private val sampleRunnable = object : Runnable {
        override fun run() {
            // Update the chart with the latest values.
            chartView.addData(latestX, latestY, latestZ)
            // Format the text and update the TextView.
            val currentValuesText = "Current: X=%.2f, Y=%.2f, Z=%.2f".format(latestX, latestY, latestZ)
            currentValuesTextView.text = currentValuesText

            handler.postDelayed(this, sampleInterval)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Force light theme if needed
        // AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Initialize the chart view and TextView from the layout.
        chartView = findViewById(R.id.accelerometer_chart)
        currentValuesTextView = findViewById(R.id.currentValuesTextView)
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also { sensor ->
            // Using SENSOR_DELAY_GAME to avoid needing high sampling permissions.
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        handler.post(sampleRunnable)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(sampleRunnable)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                // Update the latest sensor values.
                latestX = it.values[0]
                latestY = it.values[1]
                latestZ = it.values[2]
                Log.d("Accelerometer", "Latest: x: $latestX, y: $latestY, z: $latestZ")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No operation needed for this demo.
    }
}
