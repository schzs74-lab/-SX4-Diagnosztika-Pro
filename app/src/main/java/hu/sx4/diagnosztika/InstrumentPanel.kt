package hu.sx4.diagnosztika

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs

/** Swipeable instrument pages: engine (page 1) and mixture control (page 2). Display only; it sends nothing to the car. */
internal class InstrumentPanel(context: Context) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val pages: List<LinearLayout>
    private val dots = mutableListOf<View>()
    val gauges = mutableListOf<GaugeView>()
    var page = 0
        private set

    private val swipe = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) < abs(vy) || abs(vx) < 300 * d) return false
            show(if (vx < 0) page + 1 else page - 1); return true
        }
    })

    init {
        orientation = VERTICAL; tag = DashboardUi.INSTRUMENTS
        pages = listOf(
            page(big = Gauges.rpm, dials = Gauges.engineDials, digital = Gauges.engineDigital),
            page(big = null, dials = Gauges.mixtureDials, digital = Gauges.mixtureDigital))
        val stage = FrameLayout(context)
        pages.forEach { stage.addView(it, FrameLayout.LayoutParams(-1, -2)) }
        addView(stage, LayoutParams(-1, -2))
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        pages.indices.forEach { index ->
            val dot = View(context)
            dots += dot
            row.addView(FrameLayout(context).apply {
                tag = "gauge-page-$index"; isClickable = true; isFocusable = true
                contentDescription = "Műszerfal ${index + 1}. oldal"
                setOnClickListener { show(index) }
                addView(dot, FrameLayout.LayoutParams(px(8), px(8), Gravity.CENTER))
            }, LayoutParams(px(48), px(48)))
        }
        addView(row, LayoutParams(-1, -2))
        show(0)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = swipe.onTouchEvent(event) || super.onTouchEvent(event)

    fun show(index: Int) {
        page = index.coerceIn(pages.indices)
        pages.forEachIndexed { i, p -> p.visibility = if (i == page) VISIBLE else GONE }
        dots.forEachIndexed { i, dot ->
            dot.layoutParams = (dot.layoutParams as FrameLayout.LayoutParams).apply { width = px(if (i == page) 20 else 8) }
            dot.background = GradientDrawable().apply {
                setColor(if (i == page) GaugeView.ACCENT else GaugeView.LINE); cornerRadius = 4 * d
            }
            (dot.parent as View).isSelected = i == page
        }
    }

    fun update(supported: Set<Int>, latest: Map<Int, Pair<Double?, Long>>, now: Long, staleAfterMs: (Int) -> Long) {
        for (gauge in gauges) gauge.show(Gauges.reading(gauge.spec, supported, latest, now, staleAfterMs))
    }

    fun clear() { for (gauge in gauges) gauge.show(GaugeReading.NoData) }

    private fun page(big: GaugeSpec?, dials: List<GaugeSpec>, digital: List<GaugeSpec>) = LinearLayout(context).apply {
        orientation = VERTICAL
        if (big != null) addView(gauge(big), LayoutParams(px(250), -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        dials.chunked(2).forEach { pair -> addView(row(pair.map { gauge(it) })) }
        addView(row(digital.map { gauge(it, digital = true) }), LayoutParams(-1, -2).apply { topMargin = px(6) })
    }

    private fun row(views: List<View>) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        views.forEach { addView(it, LayoutParams(0, -2, 1f).apply { setMargins(px(3), px(3), px(3), px(3)) }) }
    }

    private fun gauge(spec: GaugeSpec, digital: Boolean = false) = GaugeView(context, spec, digital).also { gauges += it }

    private fun px(value: Int) = (value * d + .5f).toInt()
}
