package hu.sx4.diagnosztika

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Native, offline dashboard. Switching pages never starts or stops a diagnostic operation. */
internal class DashboardUi(private val activity: Activity) {
    private val ink = Color.rgb(22, 43, 64)
    private val muted = Color.rgb(81, 101, 119)
    private val teal = Color.rgb(0, 107, 99)
    private val canvas = Color.rgb(241, 245, 248)
    private val line = Color.rgb(215, 225, 232)
    private val pages = mutableListOf<ScrollView>()
    private val bodies = mutableListOf<LinearLayout>()
    private val tabs = mutableListOf<Button>()
    private val headings = listOf("Kapcsolat", "Élő adatok", "Mentések", "Diagnosztika")
    private val descriptions = listOf("01  /  ADAPTER ÉS JÁRMŰ", "02  /  MÉRÉS ÉS GRAFIKON", "03  /  FÁJLOK ÉS JELENTÉSEK", "04  /  HIBAKÓDOK ÉS ÁLLAPOT")
    private var selected = 0
    val status: TextView

    init {
        activity.window.statusBarColor = canvas
        activity.window.navigationBarColor = Color.WHITE
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val shell = column().apply { setBackgroundColor(canvas) }
        shell.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        val header = column().apply { setPadding(dp(20), dp(14), dp(20), dp(8)) }
        header.addView(label("SUZUKI  /  SX4", 12f, teal, true).apply { letterSpacing = .14f })
        header.addView(label("Diagnosztika", 28f, ink, true))
        status = label("DEMO mód • nincs ECU-kapcsolat", 14f, ink).apply {
            tag = "dashboard-status"
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = shape(Color.WHITE, 12, line)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        header.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        shell.addView(header)
        val stage = FrameLayout(activity)
        headings.indices.forEach { index ->
            val body = column().apply {
                setPadding(dp(16), dp(8), dp(16), dp(20))
                addView(label(descriptions[index], 11f, muted, true).apply {
                    setPadding(dp(4), dp(8), 0, dp(12)); letterSpacing = .08f
                })
            }
            val page = ScrollView(activity).apply {
                tag = "dashboard-page-$index"; isFillViewport = true
                clipToPadding = false; addView(body)
            }
            bodies += body; pages += page; stage.addView(page, FrameLayout.LayoutParams(-1, -1))
        }
        shell.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE)
            setPadding(dp(6), dp(6), dp(6), dp(6)); elevation = dp(8).toFloat()
        }
        headings.forEachIndexed { index, name ->
            val tab = Button(activity).apply {
                text = name; textSize = 11f; isAllCaps = false
                setPadding(dp(2), dp(8), dp(2), dp(8))
                minWidth = 0; minimumWidth = 0; minHeight = dp(52); minimumHeight = dp(52)
                tag = "dashboard-tab-$index"
                setOnClickListener { select(index) }
            }
            tabs += tab; nav.addView(tab, LinearLayout.LayoutParams(0, -2, 1f))
        }
        shell.addView(nav)
        activity.setContentView(shell)
        select(0)
    }

    fun card(page: Int, title: String, subtitle: String? = null): LinearLayout = column().apply {
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = shape(Color.WHITE, 18, line)
        elevation = dp(1).toFloat()
        addView(label(title, 20f, ink, true).apply { setPadding(0, 0, 0, dp(8)) })
        if (subtitle != null) addView(label(subtitle, 14f, muted).apply { setPadding(0, 0, 0, dp(8)) })
        bodies[page].addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }

    fun decorate(view: View) {
        when (view) {
            is EditText -> {
                view.textSize = 16f; view.setTextColor(ink); view.setHintTextColor(muted)
                view.minHeight = dp(52); view.setPadding(dp(12), dp(10), dp(12), dp(10))
                view.background = shape(canvas, 10, line)
                view.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(6) }
            }
            is Switch -> {
                view.setTextColor(ink); view.textSize = 15f; view.minHeight = dp(56)
                view.thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(teal, muted))
                view.setPadding(0, dp(8), 0, dp(8))
            }
            is Button -> {
                val text = view.text.toString()
                val danger = text == "Hibakódok törlése"
                val primary = text in setOf("Csatlakozás az adapterhez", "Élő adatok indítása", "Rögzítés indítása",
                    "Folyamatos mérés és rögzítés indítása", "Mentett mérés mentése fájlba", "Friss diagnosztikai jelentés készítése")
                val foreground = if(danger) Color.rgb(161, 39, 49) else if(primary) Color.WHITE else ink
                val fill = if(danger) Color.rgb(255, 242, 242) else if(primary) teal else canvas
                view.textSize = 14f; view.isAllCaps = false; view.setTextColor(foreground)
                view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                view.minHeight = dp(52); view.minimumHeight = dp(52)
                view.setPadding(dp(12), dp(12), dp(12), dp(12))
                view.background = RippleDrawable(ColorStateList.valueOf(0x22000000), shape(fill, 12), null)
                view.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            }
            is TextView -> {
                view.setTextColor(muted); view.setLineSpacing(dp(3).toFloat(), 1f)
                if(view.textSize / activity.resources.displayMetrics.scaledDensity < 14f) view.textSize = 14f
                view.setPadding(0, dp(6), 0, dp(8))
            }
        }
        if(view is ViewGroup) for(index in 0 until view.childCount) decorate(view.getChildAt(index))
    }

    fun finish(state: Bundle?) {
        bodies.forEach { body ->
            // Leave section headings and navigation typography intact.
            for(i in 1 until body.childCount) {
                val card = body.getChildAt(i) as? ViewGroup ?: continue
                for(j in 1 until card.childCount) decorate(card.getChildAt(j))
            }
        }
        select(state?.getInt("dashboardPage", 0) ?: 0)
    }

    fun save(state: Bundle) { state.putInt("dashboardPage", selected) }

    private fun select(index: Int) {
        selected = index.coerceIn(headings.indices)
        pages.forEachIndexed { i, page -> page.visibility = if(i == selected) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, tab ->
            tab.isSelected = i == selected
            tab.setTextColor(if(i == selected) teal else muted)
            tab.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            tab.background = RippleDrawable(ColorStateList.valueOf(0x22000000),
                shape(if(i == selected) Color.rgb(222, 242, 238) else Color.WHITE, 12), null)
            tab.contentDescription = headings[i] + if(i == selected) ", kiválasztva" else ""
        }
    }

    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create(if(bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
    private fun shape(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if(stroke != null) setStroke(dp(1), stroke)
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + .5f).toInt()
}
