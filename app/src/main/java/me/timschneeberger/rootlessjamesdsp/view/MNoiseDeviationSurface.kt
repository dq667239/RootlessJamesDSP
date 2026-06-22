package me.timschneeberger.rootlessjamesdsp.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.withStyledAttributes
import me.timschneeberger.rootlessjamesdsp.analysis.TonalityFrame
import kotlin.math.ln

class MNoiseDeviationSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tracePath = Path()
    private var frequencyHz = FloatArray(0)
    private var liveDeviationDb = FloatArray(0)
    private var trackDeviationDb = FloatArray(0)

    init {
        gridPaint.color = getColor(android.R.attr.colorControlHighlight)
        gridPaint.style = Paint.Style.STROKE
        gridPaint.strokeWidth = 2f

        zeroPaint.color = getColor(android.R.attr.textColorPrimary)
        zeroPaint.alpha = 128
        zeroPaint.style = Paint.Style.STROKE
        zeroPaint.strokeWidth = 3f

        textPaint.color = getColor(android.R.attr.textColorSecondary)
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            10f,
            resources.displayMetrics
        )

        trackPaint.color = getColor(android.R.attr.colorAccent)
        trackPaint.style = Paint.Style.STROKE
        trackPaint.strokeWidth = 5f

        livePaint.color = getColor(android.R.attr.colorAccent)
        livePaint.alpha = 128
        livePaint.style = Paint.Style.STROKE
        livePaint.strokeWidth = 3f
    }

    fun submitFrame(frame: TonalityFrame?) {
        if (frame == null) {
            frequencyHz = FloatArray(0)
            liveDeviationDb = FloatArray(0)
            trackDeviationDb = FloatArray(0)
        }
        else {
            frequencyHz = frame.frequencyHz.copyOf()
            liveDeviationDb = frame.liveDeviationDb.copyOf()
            trackDeviationDb = frame.trackDeviationDb.copyOf()
        }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGrid(canvas)

        if (frequencyHz.isNotEmpty()) {
            drawTrace(canvas, trackDeviationDb, trackPaint)
            drawTrace(canvas, liveDeviationDb, livePaint)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()

        for (db in DB_LABELS) {
            val y = yForDb(db.toFloat())
            canvas.drawLine(left, y, right, y, if (db == 0) zeroPaint else gridPaint)
            canvas.drawText(dbLabel(db), left, (y - 4f).coerceAtLeast(top + textPaint.textSize), textPaint)
        }

        for (freq in FREQ_LABELS) {
            val x = xForFrequency(freq.toFloat())
            canvas.drawLine(x, top, x, bottom, gridPaint)
            canvas.drawText(freqLabel(freq), x + 4f, bottom - 4f, textPaint)
        }
    }

    private fun drawTrace(canvas: Canvas, deviationDb: FloatArray, paint: Paint) {
        if (frequencyHz.size != deviationDb.size || frequencyHz.isEmpty()) return

        tracePath.rewind()
        for (i in frequencyHz.indices) {
            val x = xForFrequency(frequencyHz[i])
            val y = yForDb(deviationDb[i])
            if (i == 0) tracePath.moveTo(x, y) else tracePath.lineTo(x, y)
        }
        canvas.drawPath(tracePath, paint)
    }

    private fun xForFrequency(freqHz: Float): Float {
        val clamped = freqHz.coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ)
        val min = ln(MIN_FREQ_HZ)
        val max = ln(MAX_FREQ_HZ)
        val x = (ln(clamped) - min) / (max - min)
        return paddingLeft + x * (width - paddingLeft - paddingRight)
    }

    private fun yForDb(db: Float): Float {
        val clamped = db.coerceIn(MIN_DB, MAX_DB)
        val y = 0.5f - clamped / (MAX_DB - MIN_DB)
        return paddingTop + y * (height - paddingTop - paddingBottom)
    }

    private fun getColor(colorAttribute: Int): Int {
        if (isInEditMode) return 0xff000000.toInt()

        var color = 0
        context.withStyledAttributes(TypedValue().data, intArrayOf(colorAttribute)) {
            color = getColor(0, 0)
        }
        return color
    }

    private fun dbLabel(db: Int): String = if (db > 0) "+$db" else db.toString()

    private fun freqLabel(freq: Int): String = if (freq >= 1_000) "${freq / 1_000}k" else freq.toString()

    companion object {
        private const val MIN_FREQ_HZ = 20f
        private const val MAX_FREQ_HZ = 20_000f
        private const val MIN_DB = -12f
        private const val MAX_DB = 12f
        private val DB_LABELS = intArrayOf(-12, -6, 0, 6, 12)
        private val FREQ_LABELS = intArrayOf(20, 100, 1_000, 10_000, 20_000)
    }
}
