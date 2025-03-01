package com.example.humanactivity

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.TextView
import com.example.humanactivity.ml.AddScaleWin5Lab50Acc95
import com.example.humanactivity.ml.Win2Lab50Acc93
import kotlin.math.sqrt
import kotlin.math.abs
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.nio.ByteOrder

// A simple data class to hold a sensor sample.
data class SensorSample(val timestamp: Long, val x: Float, val y: Float, val z: Float)

class DSPProcessor(
    private val context: Context,
    private val model: Win2Lab50Acc93,  // Model passed from MainActivity.
    private val samplingRate: Double = 50.0, // nominal sampling rate in Hz
    private val windowSizeMillis: Long = 5000L,  // window length: 5 sec
    private val windowShiftMillis: Long = 1000L  // new window every 2.5 sec
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
        if (totalSamples < 100) {
            Log.d("DSPProcessor", "Not enough samples: only $totalSamples available. Waiting for 100 samples.")
            return
        }

        // Always use the most recent 100 samples.
        val windowData: List<SensorSample> = buffer.subList(totalSamples - 100, totalSamples)
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

        // Step 2: Apply band-pass filter.
        // First high-pass with 0.4 Hz cutoff, then low-pass with 15 Hz cutoff.
        val bandPassed = bandPassFilterFrequencyDomain(
            data = magnitudes,
            fs = samplingRate,
            lowCutHz = 0.4,
            highCutHz = 15.0
        )
        Log.d("DSPProcessor", "Band-Passed Magnitude (${bandPassed.size}): ${bandPassed.joinToString(limit = 10)} ...")

        // Step 3: Our window is already a flattened vector (of variable length).

        // Step 4: Compute FFT using JTransforms.
        val fftSize = bandPassed.size
        val fft = DoubleFFT_1D(fftSize.toLong())
        // realForward() transforms the array in-place.
        val fftData = bandPassed.copyOf() // so we don't overwrite 'bandPassed'
        fft.realForward(fftData)

        // The result is in "packed" format.
        val halfSize = fftSize / 2 + 1
        val fftMag = DoubleArray(halfSize)

        // DC component:
        fftMag[0] = abs(fftData[0])

        // If the data length is even, the last bin is purely real at fftData[1]
        if (fftSize % 2 == 0) {
            fftMag[halfSize - 1] = abs(fftData[1])
        }

        val upperBound = if (fftSize % 2 == 0) halfSize - 1 else halfSize
        for (k in 1 until upperBound) {
            val re = fftData[2 * k]
            val im = fftData[2 * k + 1]
            fftMag[k] = sqrt(re * re + im * im)
        }

        Log.d("DSPProcessor", "FFT Magnitude Size: ${fftMag.size}, Values: ${fftMag.joinToString(limit = 10)} ...")

        // Step 5: Prepare input for TFLite model.
        val fftMagFloat = FloatArray(fftMag.size) { i -> fftMag[i].toFloat() }
        val byteBuffer = ByteBuffer.allocateDirect(fftMagFloat.size * 4)
        byteBuffer.order(ByteOrder.nativeOrder())
        for (value in fftMagFloat) {
            byteBuffer.putFloat(value)
        }
        byteBuffer.rewind()

        // Create input tensor of shape [1, halfSize]
        val inputFeature0 = TensorBuffer.createFixedSize(intArrayOf(1, halfSize), DataType.FLOAT32)
        inputFeature0.loadBuffer(byteBuffer)

        // Run model inference.
        val outputs = model.process(inputFeature0)
        val outputFeature0 = outputs.outputFeature0AsTensorBuffer

        // Step 6: Determine predicted class.
        val outputArray = outputFeature0.floatArray
        val predictedIndex = outputArray.indices.maxByOrNull { outputArray[it] } ?: -1
        val classNames = listOf("climbing_stairs", "descending_stairs", "nothing", "running", "sitting_standing", "walking")
        val predictedClass = if (predictedIndex in classNames.indices) classNames[predictedIndex] else "Unknown"

        Log.d("DSPProcessor", "Predicted activity: $predictedClass (index: $predictedIndex) with probabilities: ${outputArray.joinToString(", ")}")

        // --- Update UI ---
        // Ensure the UI update is done on the main thread.
        (context as? Activity)?.runOnUiThread {
            // Convert 'context' to 'Activity' explicitly
            val activity = context as Activity

            val resultTextView = activity.findViewById<TextView>(R.id.predictResultTextView)
            // Build a nicely formatted string for probabilities:
            val probabilitiesString = outputArray
                .mapIndexed { index, prob ->
                    // Format each probability to 2 decimal places
                    val formattedProb = String.format("%.4f", prob)
                    "\tClass ${classNames[index]}: $formattedProb"
                }
                .joinToString("\n")

            // Combine everything in a multiline string:
            val displayText = """
Predicted activity: $predictedClass

Probabilities:
$probabilitiesString
""".trimIndent()

            resultTextView.text = displayText
        }

    }

    fun bandPassFilterFrequencyDomain(
        data: DoubleArray,
        fs: Double,
        lowCutHz: Double,
        highCutHz: Double
    ): DoubleArray {
        val n = data.size
        val fft = DoubleFFT_1D(n.toLong())
        val fftData = data.copyOf()
        fft.realForward(fftData)
        val freqResolution = fs / n
        val filter = DoubleArray(n) { i ->
            val freq = i * freqResolution
            val lowWeight = 1.0 / (1.0 + (lowCutHz / freq).pow(4))
            val highWeight = 1.0 / (1.0 + (freq / highCutHz).pow(4))
            lowWeight * highWeight
        }

        val halfN = n / 2
        for (k in 0 until halfN) {
            fftData[2 * k] *= filter[k]
            fftData[2 * k + 1] *= filter[k]
        }
        fft.realInverse(fftData, true)
        return fftData
    }
}
