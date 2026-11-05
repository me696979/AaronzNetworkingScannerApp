package net.aaronznetworking.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.random.Random

/**
 * Playback activity indicator. Driven by the app's active-call state;
 * it is not a calibrated waveform or dB measurement.
 */
class ScannerAudioMeter @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bars = FloatArray(23) { 0.08f }
    private var active = false
    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            for (i in bars.indices) {
                val target = if (active) Random.nextFloat() * 0.75f + 0.2f else 0.08f
                bars[i] = bars[i] * 0.35f + target * 0.65f
            }
            invalidate()
            postDelayed(this, 95)
        }
    }

    fun setActive(value: Boolean) {
        active = value
        contentDescription = if (value) "Audio activity: receiving" else "Audio activity: scanning"
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(tick)
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val radius = 7f * density
        paint.color = Color.rgb(5, 17, 9)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = Color.rgb(46, 110, 59)
        canvas.drawRoundRect(density, density, width - density, height - density, radius, radius, paint)
        paint.style = Paint.Style.FILL
        val gap = 3f * density
        val left = 13f * density
        val right = width - left
        val usable = max(1f, right - left)
        val step = usable / bars.size
        val barWidth = max(2f * density, step - gap)
        val bottom = height - 9f * density
        val maxHeight = height - 18f * density
        for (i in bars.indices) {
            val level = bars[i]
            paint.color = when {
                level > 0.82f -> Color.rgb(255, 172, 72)
                level > 0.6f -> Color.rgb(150, 255, 116)
                else -> Color.rgb(70, 255, 104)
            }
            val x = left + i * step
            canvas.drawRoundRect(x, bottom - maxHeight * level, x + barWidth, bottom, 2f * density, 2f * density, paint)
        }
    }
}
