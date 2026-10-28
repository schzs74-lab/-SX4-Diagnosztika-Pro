package hu.sx4.diagnosztika

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Live values as fixed-height rows: an age column and one line of text that shrinks to fit instead of
 * wrapping or being cut, so changing numbers never move the page. Rows are reused between refreshes.
 */
internal class LiveTable(context: Context) : LinearLayout(context) {
    data class Row(val age: String, val text: String, val warn: Boolean = false)
    private val d = resources.displayMetrics.density
    var rows: List<Row> = emptyList()
        private set

    init { orientation = VERTICAL; tag = TAG }

    fun show(next: List<Row>) {
        if (next == rows) return
        rows = next
        while (childCount < next.size) addView(row(), LayoutParams(-1, px(26)))
        while (childCount > next.size) removeViewAt(childCount - 1)
        next.forEachIndexed { i, r ->
            val line = getChildAt(i) as LinearLayout
            val color = if (r.warn) GaugeView.ACCENT else GaugeView.INK
            (line.getChildAt(0) as TextView).apply { text = r.age; setTextColor(if (r.warn) GaugeView.ACCENT else GaugeView.MUTED) }
            (line.getChildAt(1) as TextView).apply { text = r.text; setTextColor(color) }
            line.contentDescription = (if (r.age.isBlank()) "" else "${r.age.trim()} régi: ") + r.text
        }
    }

    /** Plain text of all rows, one per line (accessibility and tests). */
    fun text(): String = rows.joinToString("\n") { (if (it.age.isBlank()) "" else it.age + " ") + it.text }

    private fun row() = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            typeface = Typeface.MONOSPACE; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL; maxLines = 1
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(px(52), -1).apply { rightMargin = px(8) })
        addView(TextView(context).apply {
            maxLines = 1; gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            // Shrinks long readings (down to 9 sp) instead of cutting or wrapping them.
            setAutoSizeTextTypeUniformWithConfiguration(9, 15, 1, TypedValue.COMPLEX_UNIT_SP)
        }, LayoutParams(0, -1, 1f))
    }

    private fun px(value: Int) = (value * d + .5f).toInt()

    companion object { const val TAG = "live-table" }
}
