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
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    // Latest sensor values.
    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f
    private var gyroX = 0f
    private var gyroY = 0f
    private var gyroZ = 0f

    // References to UI elements.
    private lateinit var chartView: AccelerometerChartView
    private lateinit var currentAcceleTextView: TextView
    private lateinit var currentGyroTextView: TextView
    private lateinit var recordTimeTextView: TextView
    private lateinit var recordButton: Button
    private lateinit var modeSpinner: Spinner

    // Handler and Runnable for sensor sampling.
    private val handler = Handler(Looper.getMainLooper())
    private val sampleInterval: Long = 20 // milliseconds (50Hz)

    // Recording variables.
    private var isRecording = false
    private var recordStartTime: Long = 0L
    private var csvWriter: FileWriter? = null

    private val sampleRunnable = object : Runnable {
        override fun run() {
            // Update the chart and current sensor values.
            chartView.addData(accelX, accelY, accelZ)
            val currentAccelValuesText = "Current Accel: X=%.2f, Y=%.2f, Z=%.2f".format(accelX, accelY, accelZ)
            currentAcceleTextView.text = currentAccelValuesText

            val currentGyroValuesText = "Current Gyro: X=%.2f, Y=%.2f, Z=%.2f".format(gyroX, gyroY, gyroZ)
            currentGyroTextView.text = currentGyroValuesText

            // If recording is active, update record time and write a CSV line.
            if (isRecording) {
                val elapsedMillis = System.currentTimeMillis() - recordStartTime
                val seconds = elapsedMillis / 1000
                recordTimeTextView.text = "Record Time: %02d:%02d".format(seconds / 60, seconds % 60)

                // Prepare CSV line: "elapsed_time,accelX,accelY,accelZ"
                val csvLine = "$elapsedMillis,%.2f,%.2f,%.2f\n".format(accelX, accelY, accelZ)
                try {
                    csvWriter?.append(csvLine)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            handler.postDelayed(this, sampleInterval)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Initialize views.
        chartView = findViewById(R.id.accelerometer_chart)
        currentAcceleTextView = findViewById(R.id.currentAcceleTextView)
        currentGyroTextView = findViewById(R.id.currentGyroTextView)
        recordTimeTextView = findViewById(R.id.recordTimeTextView)
        recordButton = findViewById(R.id.recordButton)
        modeSpinner = findViewById(R.id.modeSpinner)

        // Setup Spinner with record modes.
        val modes = listOf("walk", "run", "using_elevator", "using_stair")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modeSpinner.adapter = adapter

        // Set window insets if needed.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // If gyroscope is not available, hide its TextView.
        if (gyroscope == null) {
            currentGyroTextView.visibility = View.GONE
            Toast.makeText(this, "Gyroscope sensor is not available on this device", Toast.LENGTH_LONG).show()
        }

        // Set up record button click listener.
        recordButton.setOnClickListener {
            if (!isRecording) {
                // Start recording.
                isRecording = true
                recordStartTime = System.currentTimeMillis()
                recordButton.text = "Stop Recording"

                // Create a CSV file with mode and current date/time.
                val selectedMode = modeSpinner.selectedItem.toString()
                val timestamp = SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.getDefault()).format(Date())
                val fileName = "${selectedMode}_$timestamp.csv"

                // For simplicity, writing file in app's external files directory.
                val file = File(getExternalFilesDir(null), fileName)
                try {
                    csvWriter = FileWriter(file)
                    // Write CSV header.
                    csvWriter?.append("ElapsedTimeMillis,AccelX,AccelY,AccelZ\n")
                    Toast.makeText(this, "Recording to file: $fileName", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "Error creating CSV file", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Stop recording.
                isRecording = false
                recordButton.text = "Start Recording"
                try {
                    csvWriter?.flush()
                    csvWriter?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        handler.post(sampleRunnable)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(sampleRunnable)
        // Ensure file is closed if activity is paused while recording.
        if (isRecording) {
            try {
                csvWriter?.flush()
                csvWriter?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isRecording = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accelX = it.values[0]
                    accelY = it.values[1]
                    accelZ = it.values[2]
                }
                Sensor.TYPE_GYROSCOPE -> {
                    gyroX = it.values[0]
                    gyroY = it.values[1]
                    gyroZ = it.values[2]
                }
                else -> { }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No operation needed.
    }
}
