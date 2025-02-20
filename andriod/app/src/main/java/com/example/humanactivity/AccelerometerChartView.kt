package com.example.humanactivity

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class AccelerometerChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // Maximum number of samples for 1 minute at 10Hz.
    private val maxSamples = 600

    // Data buffers for the three axes.
    private val xData = mutableListOf<Float>()
    private val yData = mutableListOf<Float>()
    private val zData = mutableListOf<Float>()

    val textLabelSize = 40f

    // Paint objects for each axis line.
    private val paintX = Paint().apply {
        color = Color.RED
        strokeWidth = 4f
        isAntiAlias = true
        textSize = textLabelSize
    }
    private val paintY = Paint().apply {
        color = Color.GREEN
        strokeWidth = 4f
        isAntiAlias = true
        textSize = textLabelSize
    }
    private val paintZ = Paint().apply {
        color = Color.BLUE
        strokeWidth = 4f
        isAntiAlias = true
        textSize = textLabelSize
    }

    // Paint for drawing the Y-axis ticks and grid lines.
    private val axisPaint = Paint().apply {
        color = Color.DKGRAY
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // Paint for text (axis labels, legend, current values).
    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 32f
        isAntiAlias = true
    }

    // Fixed Y-axis range.
    private val minY = -12f
    private val maxY = 12f

    // Margins for the chart area.
    private val leftMargin = 80f
    private val rightMargin = 20f
    private val topMargin = 160f
    private val bottomMargin = 40f

    // Store the latest X, Y, Z so we can display them.
    private var currentX = 0f
    private var currentY = 0f
    private var currentZ = 0f

    /**
     * Adds a new sample for each axis. Maintains a sliding window of maxSamples.
     */
    fun addData(x: Float, y: Float, z: Float) {
        if (xData.size >= maxSamples) {
            xData.removeAt(0)
            yData.removeAt(0)
            zData.removeAt(0)
        }
        xData.add(x)
        yData.add(y)
        zData.add(z)
        invalidate() // Request redraw with the new data.
    }

    /**
     * Sets the most recent accelerometer values for display in the legend text.
     */
    fun setCurrentValues(x: Float, y: Float, z: Float) {
        currentX = x
        currentY = y
        currentZ = z
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Define the drawing area for the chart lines.
        val chartWidth = w - leftMargin - rightMargin
        val chartHeight = h - topMargin - bottomMargin

        // Draw the Y-axis ticks from -12 to 12 in steps of 6: (-12, -6, 0, 6, 12).
        val step = 6
        for (value in -12..12 step step) {
            val yPos = mapY(value.toFloat(), minY, maxY, topMargin, chartHeight)
            // Draw a small tick on the left.
            canvas.drawLine(leftMargin - 10f, yPos, leftMargin, yPos, axisPaint)
            // Draw horizontal grid line across the chart (optional).
            canvas.drawLine(leftMargin, yPos, leftMargin + chartWidth, yPos, axisPaint)
            // Label the tick.
            canvas.drawText(
                value.toString(),
                leftMargin - 60f, // shift text to the left
                yPos + 10f,      // shift down a bit so text is centered on the tick
                textPaint
            )
        }

        // Draw axis label text for each line (legend).
        // We'll place them near the top-left corner.
        var legendStartX = leftMargin
        var legendStartY = 180f

        canvas.drawText("X (Red)", legendStartX, legendStartY, paintX)
        legendStartX += 180f
        canvas.drawText("Y (Green)", legendStartX, legendStartY, paintY)
        legendStartX += 180f
        canvas.drawText("Z (Blue)", legendStartX, legendStartY, paintZ)

        // Display the current accelerometer values in text
        // just below the legend.
//        legendStartY += 40f
//        val currentValuesText = "Current: X=%.2f, Y=%.2f, Z=%.2f".format(currentX, currentY, currentZ)
//        canvas.drawText(currentValuesText, legendStartX, legendStartY, textPaint)

        // If we have fewer than 2 data points, no lines to draw.
        if (xData.size < 2) return

        // We'll compute how wide each step is horizontally.
        val dx = chartWidth / (maxSamples - 1)

        // Draw the lines for x, y, z data.
        for (i in 0 until xData.size - 1) {
            val startX = leftMargin + i * dx
            val stopX = leftMargin + (i + 1) * dx

            // X axis line
            canvas.drawLine(
                startX,
                mapY(xData[i], minY, maxY, topMargin, chartHeight),
                stopX,
                mapY(xData[i + 1], minY, maxY, topMargin, chartHeight),
                paintX
            )

            // Y axis line
            canvas.drawLine(
                startX,
                mapY(yData[i], minY, maxY, topMargin, chartHeight),
                stopX,
                mapY(yData[i + 1], minY, maxY, topMargin, chartHeight),
                paintY
            )

            // Z axis line
            canvas.drawLine(
                startX,
                mapY(zData[i], minY, maxY, topMargin, chartHeight),
                stopX,
                mapY(zData[i + 1], minY, maxY, topMargin, chartHeight),
                paintZ
            )
        }
    }

    /**
     * Maps a sensor value (value) in the range [minVal, maxVal] to
     * a Y coordinate on the canvas, starting at topMargin, with total
     * height = chartHeight.
     */
    private fun mapY(
        value: Float,
        minVal: Float,
        maxVal: Float,
        topOffset: Float,
        chartHeight: Float
    ): Float {
        val ratio = (value - minVal) / (maxVal - minVal)
        // 0 -> topOffset + chartHeight (bottom)
        // 1 -> topOffset (top)
        return topOffset + (1 - ratio) * chartHeight
    }
}
