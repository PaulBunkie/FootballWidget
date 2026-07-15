package com.shrewd.bet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class MomentumGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val homePaint = Paint().apply {
        color = Color.parseColor("#4CAF50") // Green
        style = Paint.Style.FILL
    }

    private val awayPaint = Paint().apply {
        color = Color.parseColor("#2196F3") // Blue
        style = Paint.Style.FILL
    }

    private val linePaint = Paint().apply {
        color = Color.parseColor("#444444")
        strokeWidth = 2f
    }

    private var momentumPoints: List<MomentumPoint> = emptyList()

    data class MomentumPoint(val minute: Int, val value: Float)

    fun setData(points: List<MomentumPoint>) {
        this.momentumPoints = points
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (momentumPoints.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f

        // Draw middle line
        canvas.drawLine(0f, centerY, w, centerY, linePaint)

        val maxMinute = 90f // Default match length
        val barWidth = w / maxMinute
        val maxVal = 100f // Typical max value in momentum

        momentumPoints.forEach { point ->
            val x = (point.minute - 1) * barWidth
            val barHeight = (Math.abs(point.value) / maxVal) * (h / 2f)
            
            if (point.value > 0) {
                // Home (Up)
                canvas.drawRect(x, centerY - barHeight, x + barWidth - 1f, centerY, homePaint)
            } else {
                // Away (Down)
                canvas.drawRect(x, centerY, x + barWidth - 1f, centerY + barHeight, awayPaint)
            }
        }
    }
}
