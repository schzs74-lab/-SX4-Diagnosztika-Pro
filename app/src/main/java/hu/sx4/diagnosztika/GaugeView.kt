package hu.sx4.diagnosztika

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Analog dial (270° sweep) or digital readout. Never draws a needle for missing or unsupported data. */
class GaugeView(context: Context, val spec: GaugeSpec, private val digital: Boolean = false) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    var reading: GaugeReading = GaugeReading.NoData
        private set

    init { tag = "gauge-%02X".format(spec.pid); describe() }

    fun show(next: GaugeReading) {
        if (next == reading) return
        reading = next; describe(); invalidate()
    }

    private fun describe() {
        contentDescription = spec.label + ": " + when (val r = reading) {
            GaugeReading.Unsupported -> "az ECU nem támogatja"
            GaugeReading.NoData -> "nincs érvényes adat"
            is GaugeReading.Value -> Gauges.format(spec, r.value) + " " + spec.unit + if (r.stale) ", elavult, ${r.ageMs / 1000} másodperce" else ""
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, if (digital) (72 * d).toInt() else w)
    }

    private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false,
                     align: Paint.Align = Paint.Align.CENTER, mono: Boolean = false) {
        paint.style = Paint.Style.FILL; paint.color = color; paint.textSize = size; paint.textAlign = align
        paint.typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        canvas.drawText(value, x, y, paint)
    }

    private val valueText get() = (reading as? GaugeReading.Value)?.let { Gauges.format(spec, it.value) } ?: "—"
    private val note get() = when (val r = reading) {
        GaugeReading.Unsupported -> "nem támogatott"
        GaugeReading.NoData -> "nincs adat"
        is GaugeReading.Value -> if (r.stale) "ELAVULT ${r.ageMs / 1000} s" else null
    }
    private val stale get() = (reading as? GaugeReading.Value)?.stale == true
    private val unsupported get() = reading == GaugeReading.Unsupported

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (digital) drawDigital(canvas) else drawDial(canvas)
    }

    private fun drawDigital(canvas: Canvas) {
        paint.style = Paint.Style.FILL; paint.color = if (stale) WARN_BG else PANEL
        val box = RectF(d, d, width - d, height - d)
        canvas.drawRoundRect(box, 10 * d, 10 * d, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = d; paint.color = LINE
        canvas.drawRoundRect(box, 10 * d, 10 * d, paint)
        val left = 10 * d
        text(canvas, spec.label, left, 20 * d, 11 * d, MUTED, bold = true, align = Paint.Align.LEFT)
        val color = if (unsupported) DIM else if (stale) ACCENT else INK
        text(canvas, valueText, left, 46 * d, 20 * d, color, bold = true, align = Paint.Align.LEFT, mono = true)
        paint.textSize = 20 * d
        val valueWidth = paint.measureText(valueText)
        text(canvas, spec.unit, left + valueWidth + 4 * d, 46 * d, 11 * d, MUTED, align = Paint.Align.LEFT)
        note?.let { text(canvas, it, left, 63 * d, 10 * d, if (stale) ACCENT else MUTED, bold = true, align = Paint.Align.LEFT) }
    }

    private fun drawDial(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        val r = size / 2 - 4 * d
        val big = size > 200 * d
        paint.style = Paint.Style.FILL; paint.color = DIAL
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * d; paint.color = LINE
        canvas.drawCircle(cx, cy, r, paint)
        val track = r - 8 * d
        val oval = RectF(cx - track, cy - track, cx + track, cy + track)
        paint.strokeWidth = 5 * d; paint.strokeCap = Paint.Cap.BUTT
        paint.color = TRACK; canvas.drawArc(oval, START, SWEEP, false, paint)
        spec.redFrom?.let { red ->
            val f = Gauges.fraction(spec, red).toFloat()
            paint.color = RED; canvas.drawArc(oval, START + f * SWEEP, (1 - f) * SWEEP, false, paint)
        }
        val value = (reading as? GaugeReading.Value)?.value
        if (value != null) {
            val f = Gauges.fraction(spec, value).toFloat()
            val from = if (spec.centered) Gauges.fraction(spec, 0.0).toFloat() else 0f
            paint.color = if (stale) DIM else ACCENT
            canvas.drawArc(oval, START + min(from, f) * SWEEP, kotlin.math.abs(f - from) * SWEEP, false, paint)
        }
        // Ticks: majors with labels on large dials, one minor between majors.
        val steps = spec.majorTicks * 2
        for (i in 0..steps) {
            val v = spec.min + (spec.max - spec.min) * i / steps
            val a = Math.toRadians((START + SWEEP * i / steps).toDouble())
            val major = i % 2 == 0
            val outer = track - 8 * d; val inner = outer - (if (major) 10 else 5) * d
            paint.style = Paint.Style.STROKE; paint.strokeWidth = (if (major) 2 else 1) * d
            paint.color = if (spec.redFrom != null && v >= spec.redFrom) RED else TICK
            canvas.drawLine(cx + (outer * cos(a)).toFloat(), cy + (outer * sin(a)).toFloat(),
                cx + (inner * cos(a)).toFloat(), cy + (inner * sin(a)).toFloat(), paint)
            if (major && (big || (i in 1 until steps))) {
                val lr = inner - 12 * d
                val label = if (spec.max >= 1000) Gauges.format(spec.copy(decimals = 0), v / 1000) else
                    Gauges.format(spec.copy(decimals = if (spec.max - spec.min < 5) 1 else 0, centered = false), v)
                text(canvas, label, cx + (lr * cos(a)).toFloat(), cy + (lr * sin(a)).toFloat() + 4 * d,
                    (if (big) 13 else 10) * d, TICK)
            }
        }
        if (value != null) {
            val a = Math.toRadians((START + SWEEP * Gauges.fraction(spec, value)).toDouble())
            val tip = track - 12 * d
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * d; paint.strokeCap = Paint.Cap.ROUND
            paint.color = if (stale) DIM else NEEDLE
            canvas.drawLine(cx - (12 * d * cos(a)).toFloat(), cy - (12 * d * sin(a)).toFloat(),
                cx + (tip * cos(a)).toFloat(), cy + (tip * sin(a)).toFloat(), paint)
            paint.strokeCap = Paint.Cap.BUTT
        }
        paint.style = Paint.Style.FILL; paint.color = LINE; canvas.drawCircle(cx, cy, 7 * d, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * d; paint.color = NEEDLE; canvas.drawCircle(cx, cy, 7 * d, paint)
        val valueSize = (if (big) 30 else 18) * d
        val color = if (unsupported) DIM else if (stale) ACCENT else INK
        text(canvas, valueText, cx, cy + r * 0.5f, valueSize, color, bold = true, mono = true)
        // The state note takes the unit's place, so the instrument name always stays readable.
        text(canvas, note ?: spec.unit, cx, cy + r * 0.5f + (if (big) 18 else 13) * d, (if (big) 12 else 10) * d,
            if (stale) ACCENT else MUTED, bold = note != null)
        text(canvas, spec.label, cx, cy + r * 0.86f, (if (big) 13 else 10) * d, MUTED, bold = true)
    }

    companion object {
        private const val START = 135f
        private const val SWEEP = 270f
        val INK = Color.rgb(237, 242, 245)
        val MUTED = Color.rgb(140, 154, 165)
        val DIM = Color.rgb(92, 105, 115)
        val ACCENT = Color.rgb(255, 181, 71)
        val PANEL = Color.rgb(17, 26, 33)
        val LINE = Color.rgb(42, 54, 64)
        private val WARN_BG = Color.rgb(42, 30, 8)
        private val DIAL = Color.rgb(11, 16, 21)
        private val TRACK = Color.rgb(36, 49, 59)
        private val TICK = Color.rgb(201, 211, 218)
        private val RED = Color.rgb(229, 72, 59)
        private val NEEDLE = Color.rgb(255, 107, 61)
    }
}
