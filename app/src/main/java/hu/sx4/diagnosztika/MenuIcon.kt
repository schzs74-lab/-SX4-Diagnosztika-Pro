package hu.sx4.diagnosztika

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Round main-menu icon drawn in code (24-unit grid), so the menu needs no image resources. */
internal class MenuIcon(context: Context, private val kind: String) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height).toFloat()
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(28, 40, 48)
        canvas.drawCircle(width / 2f, height / 2f, s / 2, paint)
        val u = s / 2 / 24f // icon occupies the middle half of the circle
        canvas.save(); canvas.translate(width / 2f - 12 * u, height / 2f - 12 * u)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * u
        paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        paint.color = when (kind) { "dtc" -> Color.rgb(255, 122, 107); "adapter" -> Color.rgb(111, 195, 255); else -> GaugeView.ACCENT }
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) = canvas.drawLine(x0 * u, y0 * u, x1 * u, y1 * u, paint)
        fun poly(vararg p: Float) = canvas.drawPath(Path().apply {
            moveTo(p[0] * u, p[1] * u); var i = 2; while (i < p.size) { lineTo(p[i] * u, p[i + 1] * u); i += 2 }
        }, paint)
        when (kind) {
            "dash" -> { canvas.drawArc(RectF(3 * u, 4 * u, 21 * u, 22 * u), 150f, 240f, false, paint); line(12f, 13f, 16.5f, 8.5f); line(8f, 19f, 16f, 19f) }
            "graph" -> { poly(3f, 17f, 9f, 11f, 13f, 15f, 21f, 7f); poly(15f, 7f, 21f, 7f, 21f, 13f) }
            "dtc" -> { poly(12f, 3f, 22f, 20f, 2f, 20f, 12f, 3f); line(12f, 9f, 12f, 13.5f); line(12f, 17f, 12f, 17.1f) }
            "adapter" -> {
                canvas.drawArc(RectF(2 * u, 6 * u, 22 * u, 26 * u), 225f, 90f, false, paint)
                canvas.drawArc(RectF(6 * u, 10.5f * u, 18 * u, 22.5f * u), 225f, 90f, false, paint)
                line(12f, 19.5f, 12f, 19.6f)
            }
            "files" -> { poly(14f, 2f, 6f, 2f, 4f, 4f, 4f, 20f, 6f, 22f, 18f, 22f, 20f, 20f, 20f, 8f, 14f, 2f); poly(14f, 2f, 14f, 8f, 20f, 8f) }
        }
        canvas.restore()
    }
}
