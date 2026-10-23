package hu.sx4.diagnosztika

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

class OxygenChartView(context: Context) : View(context) {
    private val history = OxygenHistory()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private val font = resources.displayMetrics.scaledDensity
    private var now = 0L
    private var running = false
    private var simulated = true
    private var supported = emptySet<Int>()
    fun start(demo: Boolean, pids: Set<Int>, atMs: Long) {
        history.clear(); simulated = demo; supported = pids; now = atMs; running = true; invalidate()
    }
    fun reset() { history.clear(); running = false; supported = emptySet(); invalidate() }
    fun pause(atMs: Long) { now = atMs; running = false; invalidate() }
    fun tick(atMs: Long) { if (running) { now = atMs; invalidate() } }
    fun sample(sample: LiveSample, atMs: Long) {
        if (sample.pid !in setOf(0x14, 0x15)) return
        history.add(sample.pid, atMs, sample.value!!); now = maxOf(now, atMs); invalidate()
    }
    fun gap(pid: Int) { history.gap(pid); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(241, 246, 249)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 12*d, 12*d, paint)
        fun text(value: String, x: Float, y: Float, color: Int = Color.rgb(35, 56, 68), size: Float = 12f) {
            paint.style = Paint.Style.FILL; paint.color = color; paint.textSize = size*font
            canvas.drawText(value, x, y, paint)
        }
        val badge = if (supported.isEmpty()) "NINCS MÉRÉS" else if (simulated) "DEMO / SZIMULÁLT" else "VALÓDI ECU"
        text("$badge • ${if (running) "ÉLŐ" else "SZÜNETEL"}", 14*d, 23*d, size = 13f)
        val left = 44*d; val right = width - 14*d
        if (right <= left) return
        for ((index, pid) in listOf(0x14, 0x15).withIndex()) {
            val top = (52 + index*112)*d; val bottom = top + 80*d
            val color = if (index == 0) Color.rgb(91, 69, 178) else Color.rgb(0, 119, 124)
            text(if (index == 0) "B1S1 • első szonda" else "B1S2 • második szonda", 14*d, top - 8*d, color)
            for (v in listOf(0.0, 0.6, 1.2)) {
                val y = bottom - (v/1.3 * (bottom-top)).toFloat()
                paint.color = Color.rgb(210, 221, 228); paint.strokeWidth = d
                canvas.drawLine(left, y, right, y, paint)
                text(java.lang.String.format(java.util.Locale.ROOT, "%.1f", v), 8*d, y + 4*d, size = 10f)
            }
            val points = history.points(pid, now)
            if (points.isEmpty()) text(if (pid !in supported) "Nincs támogatott adat" else "Még nincs minta", left+8*d, top+44*d, size = 11f)
            canvas.save(); canvas.clipRect(left, top, right, bottom)
            val path = Path()
            for ((i, point) in points.withIndex()) {
                val x = right - ((now-point.atMs)/60_000.0 * (right-left)).toFloat()
                val y = bottom - (point.volts/1.3*(bottom-top)).toFloat()
                if (i == 0 || point.startsSegment) path.moveTo(x, y) else path.lineTo(x, y)
                paint.color = color; paint.style = Paint.Style.FILL
                canvas.drawCircle(x, y, 2*d, paint)
            }
            paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2*d
            canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
            canvas.restore()
        }
        text("V", 10*d, 37*d, size = 10f)
        text("−60 s", left, 264*d, size = 10f)
        text("−30 s", (left+right)/2-14*d, 264*d, size = 10f)
        text(if (running) "most" else "vége", right-25*d, 264*d, size = 10f)
        contentDescription = "$badge. Két szonda feszültsége az utolsó 60 másodpercben. ${if (running) "Élő mérés" else "Mérés szünetel"}."
    }
}
