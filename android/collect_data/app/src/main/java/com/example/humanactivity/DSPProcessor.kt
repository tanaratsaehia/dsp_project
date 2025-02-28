package com.example.humanactivity

import android.content.Context
import android.util.Log
import com.example.humanactivity.ml.FftWin5Lab5050epAcc96
import kotlin.math.sqrt
import kotlin.math.abs
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.ceil
import kotlin.math.floor

import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.nio.ByteOrder

// A simple data class to hold a sensor sample.
data class SensorSample(val timestamp: Long, val x: Float, val y: Float, val z: Float)

class DSPProcessor(
    private val context: Context,
    private val model: FftWin5Lab5050epAcc96,  // Model passed from MainActivity.
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
        if (totalSamples == 0) return  // nothing to process

        // If the app just started (no window processed yet) and we haven't accumulated 250 samples,
        // then wait until we have 250 samples.
        if (lastWindowTime == 0L && totalSamples < 250) {
            Log.d("DSPProcessor", "App just started: only $totalSamples samples available. Waiting for 250 samples.")
            return
        }

        // If we have 250 or more samples, take the most recent 250.
        // Otherwise, use all available samples (processWindow will pad them to 250).
        val windowData: List<SensorSample> = if (totalSamples >= 250) {
            buffer.subList(totalSamples - 250, totalSamples)
        } else {
            buffer.toList()
        }

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
        // If needed, you can re-sample or zero-pad here. JTransforms can handle arbitrary lengths.

        // Step 4: Compute FFT using JTransforms.
        val fftSize = bandPassed.size
        val fft = DoubleFFT_1D(fftSize.toLong())
        // realForward() transforms the array in-place.
        val fftData = bandPassed.copyOf() // so we don't overwrite 'filtered'
        fft.realForward(fftData)

        // The result is in "packed" format:
        //   fftData[0] = DC component
        //   fftData[1] = Nyquist component (if fftSize is even)
        //   for k in 1..(fftSize/2 - 1):
        //       real = fftData[2*k]
        //       imag = fftData[2*k + 1]
        // We'll build the magnitude of the non-redundant half.

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

        // Step 5: Log the final FFT magnitude.
        Log.d("DSPProcessor", "FFT Magnitude Size: ${fftMag.size}, Values: ${fftMag.joinToString(limit = 10)} ...")

        // Step 5: Prepare input for TFLite model.
        // Convert fftMag (DoubleArray) to FloatArray.
        val fftMagFloat = FloatArray(fftMag.size) { i -> fftMag[i].toFloat() }
        // Create a direct ByteBuffer and load the float array.
        val byteBuffer = ByteBuffer.allocateDirect(fftMagFloat.size * 4)
        byteBuffer.order(ByteOrder.nativeOrder())
        for (value in fftMagFloat) {
            byteBuffer.putFloat(value)
        }
        byteBuffer.rewind()

        // Create input tensor of shape [1, halfSize] (e.g., [1, 126]).
        val inputFeature0 = TensorBuffer.createFixedSize(intArrayOf(1, halfSize), DataType.FLOAT32)
        inputFeature0.loadBuffer(byteBuffer)
        // Run model inference using the model passed from MainActivity.
        val outputs = model.process(inputFeature0)                  // <<<<<<<<<------------------------ Normalize into 0-1
        val outputFeature0 = outputs.outputFeature0AsTensorBuffer

        // Step 6: Determine predicted class.
        val outputArray = outputFeature0.floatArray
        val predictedIndex = outputArray.indices.maxByOrNull { outputArray[it] } ?: -1

        // Adjust these class names to match your model’s training labels.
        val classNames = listOf("climbing_stairs", "descending_stairs", "nothing", "running", "sitting_standing_transition", "walking")
        val predictedClass = if (predictedIndex in classNames.indices) classNames[predictedIndex] else "Unknown"

        Log.d("DSPProcessor", "Predicted activity: $predictedClass (index: $predictedIndex) with probabilities: ${outputArray.joinToString(", ")}")
    }

    fun bandPassFilterFrequencyDomain(
        data: DoubleArray,
        fs: Double,
        lowCutHz: Double,
        highCutHz: Double
    ): DoubleArray {
        val n = data.size
        val fft = DoubleFFT_1D(n.toLong())

        // Copy so we don't overwrite the original.
        val fftData = data.copyOf()

        // 1) Forward FFT in-place.
        fft.realForward(fftData)

        // Frequency resolution is fs / n
        val freqResolution = fs / n

        // Indices in the FFT that correspond to the cutoff frequencies
        // e.g. bin = round(freq / freqResolution)
        val lowIndex = ceil(lowCutHz / freqResolution).toInt()
        val highIndex = floor(highCutHz / freqResolution).toInt()

        // For realForward(), the layout is:
        //  fftData[0] = DC (real)
        //  fftData[1] = Nyquist (real) if n is even
        //  for k in 1..(n/2 - 1):
        //    real = fftData[2*k], imag = fftData[2*k + 1]
        // We'll zero out everything outside [lowIndex..highIndex].

        // Zero DC if below lowCut
        if (lowCutHz > 0.0) {
            fftData[0] = 0.0
        }
        // If n is even, bin n/2 is the Nyquist frequency at fftData[1].
        // Zero it if above highCut or below lowCut.
        if (n % 2 == 0) {
            val nyquistFreq = fs / 2.0
            if (nyquistFreq < lowCutHz || nyquistFreq > highCutHz) {
                fftData[1] = 0.0
            }
        }

        // Zero out bins below lowIndex or above highIndex
        val halfN = n / 2
        for (k in 1 until halfN) {
            if (k < lowIndex || k > highIndex) {
                // real part
                fftData[2 * k] = 0.0
                // imag part
                fftData[2 * k + 1] = 0.0
            }
        }
        // 2) Inverse FFT. realInverse( array, scale ) with scale=true divides by n automatically.
        fft.realInverse(fftData, true)

        // fftData now holds the filtered time-domain signal.
        return fftData
    }
}
