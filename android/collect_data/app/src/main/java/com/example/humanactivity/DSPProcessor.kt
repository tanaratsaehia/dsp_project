package com.example.humanactivity

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.TextView
import kotlin.math.sqrt

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException



// A simple data class to hold a sensor sample.
data class SensorSample(val timestamp: Long, val x: Float, val y: Float, val z: Float)

class DSPProcessor(
    private val context: Context,
    private val samplingRate: Double = 50.0, // nominal sampling rate in Hz
    private val windowSizeMillis: Long = 5000L,  // window length: 5 sec
    private val windowShiftMillis: Long = 2500L  // new window every 2.5 sec
) {
    // Buffer to hold incoming samples.
    private val buffer = mutableListOf<SensorSample>()
    // Keep track of last processed window start time.
    private var lastWindowTime: Long = 0L

    /**
     * Call this for each sensor sample.
     */
    fun addSample(sample: SensorSample) {
        buffer.add(sample)
        // Optionally remove very old samples (keep a bit more than one window)
        val cutoff = sample.timestamp - (windowSizeMillis * 2)
        buffer.removeAll { it.timestamp < cutoff }
    }

    /**
     * Call this periodically (e.g. every 2.5 sec) with the current time.
     * If enough data (5 sec window) is available, process it.
     */
    fun processIfReady(currentTime: Long) {
        val totalSamples = buffer.size
        if (totalSamples < 250) {
            Log.d("DSPProcessor", "Not enough samples: only $totalSamples available. Waiting for 250 samples.")
            return
        }

        // Always use the most recent 100 samples.
        val windowData: List<SensorSample> = buffer.subList(totalSamples - 250, totalSamples)
        processWindow(windowData)
        lastWindowTime = currentTime
    }


    /**
     * Process one window of sensor data.
     * Steps:
     * 1) Compute magnitude.
     * 2) Apply band-pass filter (implemented here as high-pass then low-pass).
     * 3) Flatten (our vector is already flat).
     * 4) Compute FFT using JTransforms.
     * 5) Log the intermediate results.
     */
    private fun processWindow(windowData: List<SensorSample>) {
        // Step 1: Calculate magnitude for each sample.
        val magnitudes = windowData.map { sample ->
            sqrt((sample.x * sample.x + sample.y * sample.y + sample.z * sample.z).toDouble())
        }.toDoubleArray()

        Log.d("DSPProcessor", "Raw Magnitude (${magnitudes.size}): ${magnitudes.joinToString(", ", limit = 10)} ...")

        // Convert magnitude data into JSON
        val jsonData = """
        {
            "data": ${magnitudes.joinToString(",", "[", "]")}
        }
    """.trimIndent()

        // Send HTTP request to FastAPI
        sendPredictionRequest(jsonData)
    }

    /**
     * Sends a POST request to FastAPI to get predictions.
     */
    private fun sendPredictionRequest(jsonData: String) {
        val client = OkHttpClient()
        val mediaType = "application/json".toMediaType()
        val body = jsonData.toRequestBody(mediaType)

        val request = Request.Builder()
            .url("http://192.168.110.112:8000/predict")  // Use "10.0.2.2" instead of "localhost" for Android emulator
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("API_ERROR", "Request failed: ${e.message}")
                (context as? Activity)?.runOnUiThread {
                    val resultTextView = (context as Activity).findViewById<TextView>(R.id.predictResultTextView)
                    resultTextView.text = "Error: ${e.message}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseData = response.body?.string()
                Log.d("API_RESPONSE", "Response: $responseData")

                try {
                    val jsonResponse = JSONObject(responseData)
//                    val predictedClass = jsonResponse.getString("predicted_class")
                    val predictedClassIdx = jsonResponse.getInt("predicted_index")
                    val probabilities = jsonResponse.getJSONArray("probabilities")
                    val classNames = listOf("climbing_stairs", "descending_stairs", "nothing", "running", "sitting_standing", "walking")

                    val probabilitiesString = (0 until probabilities.length()).joinToString("\n") { index ->
                        val formattedProb = String.format("%.4f", probabilities.getDouble(index))
                        "\t${classNames[index]}: $formattedProb"
                    }

                    val displayText = """
Predicted activity: ${classNames[predictedClassIdx]}

Probabilities:
$probabilitiesString
                """

                    (context as? Activity)?.runOnUiThread {
                        val resultTextView = (context as Activity).findViewById<TextView>(R.id.predictResultTextView)
                        resultTextView.text = displayText
                    }

                } catch (e: JSONException) {
                    Log.e("JSON_ERROR", "Failed to parse JSON: ${e.message}")
                }
            }
        })
    }
}
