package com.example.humanactivity

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.Color
import android.graphics.Paint
import com.example.humanactivity.ml.AddScaleWin5Lab50Acc95
import com.example.humanactivity.ml.FftWin5Lab5050epAcc96
import com.example.humanactivity.ml.Win2Lab50Acc93
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    // Create a TFLite model instance.
    private lateinit var tfliteModel: Win2Lab50Acc93
    // Create DSPProcessor with the model.
    private lateinit var dspProcessor: DSPProcessor
    // Create a timer to trigger DSP processing every 2.5 seconds.
    private var dspTimer: Timer? = null

    // Latest sensor values.
    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f
    private var gyroX = 0f
    private var gyroY = 0f
    private var gyroZ = 0f

    // References to UI elements.
    private lateinit var chartView: AccelerometerChartView

    // *** New Buttons for switching modes ***
    private lateinit var btnPredict: Button
    private lateinit var btnRecord: Button

    // *** Predict result TextView ***
    private lateinit var predictResultTextView: TextView

    // *** The layout that contains record widgets ***
    private lateinit var recordLayout: LinearLayout

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

    private val windowShiftMillis = 2500L

    private val sampleRunnable = object : Runnable {
        override fun run() {
            // Update the chart and current sensor values.
            chartView.addData(accelX, accelY, accelZ)
            currentAcceleTextView.text = "Current Accel: X=%.2f, Y=%.2f, Z=%.2f".format(accelX, accelY, accelZ)
            currentGyroTextView.text = "Current Gyro: X=%.2f, Y=%.2f, Z=%.2f".format(gyroX, gyroY, gyroZ)

            // If recording, write CSV line.
            if (isRecording) {
                val elapsedMillis = System.currentTimeMillis() - recordStartTime
                recordTimeTextView.text = "Record Time: %02d:%02d".format(elapsedMillis / 60000, (elapsedMillis / 1000) % 60)
                val csvLine = "$elapsedMillis,%.2f,%.2f,%.2f\n".format(accelX, accelY, accelZ)
                try {
                    csvWriter?.append(csvLine)
                } catch (e: Exception) { e.printStackTrace() }
            }
            // Post next sample update.
            handler.postDelayed(this, sampleInterval)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        // Use PARTIAL_WAKE_LOCK to keep CPU running.
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::SensorWakelock")

        // Initialize views.
        chartView = findViewById(R.id.accelerometer_chart)

        // *** New UI references ***
        btnPredict = findViewById(R.id.btnPredict)
        btnRecord = findViewById(R.id.btnRecord)
        predictResultTextView = findViewById(R.id.predictResultTextView)
        recordLayout = findViewById(R.id.recordLayout)

        highlightButton(btnPredict, btnRecord)

        currentAcceleTextView = findViewById(R.id.currentAcceleTextView)
        currentGyroTextView = findViewById(R.id.currentGyroTextView)
        recordTimeTextView = findViewById(R.id.recordTimeTextView)
        recordButton = findViewById(R.id.recordButton)
        modeSpinner = findViewById(R.id.modeSpinner)

        // Setup Spinner with record modes.
        val modes = listOf("nothing", "walking", "running", "climbing_stairs", "descending_stairs", "sitting_standing_transition")
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

        // *** Handle PREDICT button ***
        btnPredict.setOnClickListener {
            highlightButton(btnPredict, btnRecord)
            // Show prediction text, hide record layout.
            predictResultTextView.visibility = View.VISIBLE
            recordLayout.visibility = View.GONE
            predictResultTextView.text = "predicted result will display here"
        }

        // *** Handle RECORD button ***
        btnRecord.setOnClickListener {
            highlightButton(btnRecord, btnPredict)
            // Hide prediction text, show record layout.
            predictResultTextView.visibility = View.GONE
            recordLayout.visibility = View.VISIBLE
            predictResultTextView.text = ""
        }

        // Set up record button click listener.
        recordButton.setOnClickListener {
            if (!isRecording) {
                isRecording = true
                recordStartTime = System.currentTimeMillis()
                recordButton.text = "Stop Recording"
                val selectedMode = modeSpinner.selectedItem.toString()
                val timestamp = SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.getDefault()).format(Date())
                val fileName = "${selectedMode}_$timestamp.csv"
                val file = File(getExternalFilesDir(null), fileName)
                try {
                    csvWriter = FileWriter(file)
                    csvWriter?.append("time,x,y,z\n")
                    Toast.makeText(this, "Recording to file: $fileName", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "Error creating CSV file", Toast.LENGTH_SHORT).show()
                }
            } else {
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
        // Pass the model to DSPProcessor.
//        dspProcessor = DSPProcessor()
        dspProcessor = DSPProcessor(this, samplingRate = 50.0)

        // Start a timer task that triggers DSP processing every windowShiftMillis.
        dspTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread {
                    dspProcessor.processIfReady(System.currentTimeMillis())
                }
            }
        }, windowShiftMillis, windowShiftMillis)
    }

    override fun onResume() {
        super.onResume()
        wakeLock.acquire()
        accelerometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        handler.post(sampleRunnable)

        // Create and schedule the timer task
        dspTimer = Timer()
        dspTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread {
                    dspProcessor.processIfReady(System.currentTimeMillis())
                }
            }
        }, windowShiftMillis, windowShiftMillis)
    }


    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(sampleRunnable)
        dspTimer?.cancel()
        dspTimer = null
        if (isRecording) {
            try {
                csvWriter?.flush()
                csvWriter?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isRecording = false
        }
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        // Close the TFLite model when the app is closing.
        tfliteModel.close()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accelX = it.values[0]
                    accelY = it.values[1]
                    accelZ = it.values[2]
                    // Feed the sample into DSPProcessor.
                    dspProcessor.addSample(SensorSample(System.currentTimeMillis(), accelX, accelY, accelZ))
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

    private fun highlightButton(selectedButton: Button, unselectedButton: Button) {
        selectedButton.paintFlags = selectedButton.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        selectedButton.setTextColor(Color.RED)
        unselectedButton.paintFlags = unselectedButton.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        unselectedButton.setTextColor(Color.BLACK)
    }
}
