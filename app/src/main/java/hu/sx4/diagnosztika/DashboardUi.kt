package hu.sx4.diagnosztika

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/**
 * Native, offline dark dashboard: an instrument-style main menu opens each destination, and "Menü"
 * returns to it. Switching pages never starts or stops a diagnostic operation.
 */
internal class DashboardUi(private val activity: Activity) {
    private val ink = GaugeView.INK
    private val muted = GaugeView.MUTED
    private val accent = GaugeView.ACCENT
    private val canvas = Color.rgb(9, 14, 18)
    private val panel = GaugeView.PANEL
    private val line = GaugeView.LINE
    private val pages = mutableListOf<ScrollView>()
    private val bodies = mutableListOf<LinearLayout>()
    private val tabs = mutableListOf<View>()
    private val headings = listOf("Kapcsolat", "Élő adat és grafikon", "Mentések és jelentések", "Hibakódok és állapot", "Élő műszerfal")
    private val descriptions = listOf("ADAPTER, VEZÉRLŐ, AZONOSÍTÓK", "MÉRÉS, LAMBDAGRAFIKON, RÖGZÍTÉS", "CSV-MÉRÉSEK ÉS JELENTÉSEK",
        "HIBAKÓDOK, READINESS, FREEZE FRAME", "ANALÓG ÓRÁK • LAPOZHATÓ")
    /** Menu order and icon of each destination; the index is the page. */
    private val menu = listOf(4 to "dash", 1 to "graph", 3 to "dtc", 0 to "adapter", 2 to "files")
    private var selected = HOME
    private val home: LinearLayout
    private val pageTitle: TextView
    private val menuButton: Button
    private val brand: LinearLayout
    private val lights = mutableMapOf<String, View>()
    val status: TextView

    init {
        activity.window.statusBarColor = canvas
        activity.window.navigationBarColor = canvas
        activity.window.decorView.systemUiVisibility = 0
        val shell = column().apply { setBackgroundColor(canvas) }
        shell.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        val header = column().apply { setPadding(dp(16), dp(12), dp(16), dp(6)) }
        brand = column().apply {
            addView(label("SX4 Diagnosztika", 24f, ink, true))
            addView(label("Suzuki SX4 2007 • M15A 1.5 • OBD-II", 13f, muted))
        }
        header.addView(brand)
        val bar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        menuButton = Button(activity).apply {
            text = "‹ Menü"; isAllCaps = false; textSize = 15f; setTextColor(accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minHeight = dp(48); minimumHeight = dp(48); minWidth = 0; minimumWidth = 0
            setPadding(dp(4), 0, dp(12), 0); tag = "dashboard-menu"
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, shape(Color.WHITE, 12))
            contentDescription = "Vissza a főmenübe"
            setOnClickListener { select(HOME) }
        }
        pageTitle = label("", 18f, ink, true)
        bar.addView(menuButton); bar.addView(pageTitle, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(bar)
        status = label("DEMO mód • nincs ECU-kapcsolat", 14f, ink).apply {
            tag = "dashboard-status"
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = shape(panel, 12, line)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        header.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        shell.addView(header)

        val stage = FrameLayout(activity)
        home = column().apply { tag = "dashboard-home"; setPadding(dp(16), dp(8), dp(16), 0) }
        val grid = GridLayout(activity).apply { columnCount = 3; useDefaultMargins = false }
        for ((page, icon) in menu) {
            val tile = column().apply {
                gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(14), dp(4), dp(12))
                background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape(panel, 16, line), null)
                isClickable = true; isFocusable = true; tag = "dashboard-tab-$page"
                contentDescription = headings[page]
                addView(MenuIcon(activity, icon), LinearLayout.LayoutParams(dp(56), dp(56)))
                addView(label(headings[page], 13f, ink, true).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0); maxLines = 2
                })
                setOnClickListener { select(page) }
            }
            tabs += tile
            grid.addView(tile, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        home.addView(grid, LinearLayout.LayoutParams(-1, -2))
        home.addView(label("Szabványos OBD-II diagnosztika egy kiválasztott vezérlőtől. Gyártói szervizfunkciókat nem tartalmaz.",
            12f, muted).apply { setPadding(dp(4), dp(16), dp(4), 0) })
        home.addView(View(activity), LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(14)); tag = "dashboard-lights"
        }
        for (name in listOf("Wi-Fi", "Adapter", "ECU", "Rögzítés")) {
            val dot = View(activity).apply { background = shape(Color.rgb(74, 85, 96), 6) }
            lights[name] = dot
            footer.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(6) })
                addView(label(name, 12f, muted, true))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        home.addView(footer, LinearLayout.LayoutParams(-1, -2))
        stage.addView(home, FrameLayout.LayoutParams(-1, -1))
        headings.indices.forEach { index ->
            val body = column().apply {
                setPadding(dp(16), dp(4), dp(16), dp(20))
                addView(label(descriptions[index], 11f, muted, true).apply {
                    setPadding(dp(4), dp(6), 0, dp(10)); letterSpacing = .08f
                })
            }
            val page = ScrollView(activity).apply {
                tag = "dashboard-page-$index"; isFillViewport = true
                clipToPadding = false; addView(body)
            }
            bodies += body; pages += page; stage.addView(page, FrameLayout.LayoutParams(-1, -1))
        }
        shell.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        activity.setContentView(shell)
        select(HOME)
    }

    fun card(page: Int, title: String, subtitle: String? = null): LinearLayout = column().apply {
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = shape(panel, 16, line)
        addView(label(title, 18f, ink, true).apply { setPadding(0, 0, 0, dp(6)) })
        if (subtitle != null) addView(label(subtitle, 14f, muted).apply { setPadding(0, 0, 0, dp(6)) })
        bodies[page].addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    /** Adds an undecorated view (e.g. the instrument panel) straight to a page. */
    fun add(page: Int, view: View) { bodies[page].addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }) }

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
                view.thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, muted))
                view.trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.rgb(122, 86, 30), line))
                view.setPadding(0, dp(8), 0, dp(8))
            }
            is Button -> {
                val text = view.text.toString()
                val danger = text == "Hibakódok törlése"
                val primary = text in PRIMARY
                val foreground = if(danger) Color.rgb(255, 139, 126) else if(primary) Color.rgb(26, 18, 6) else ink
                val fill = if(danger) panel else if(primary) accent else Color.rgb(28, 40, 48)
                view.textSize = 14f; view.isAllCaps = false; view.setTextColor(foreground)
                view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                view.minHeight = dp(52); view.minimumHeight = dp(52)
                view.setPadding(dp(12), dp(12), dp(12), dp(12))
                view.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                    shape(fill, 12, if (danger) Color.rgb(142, 42, 34) else null), null)
                view.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            }
            is TextView -> {
                view.setTextColor(Color.rgb(201, 211, 218)); view.setLineSpacing(dp(3).toFloat(), 1f)
                if(view.textSize / activity.resources.displayMetrics.scaledDensity < 14f) view.textSize = 14f
                view.setPadding(0, dp(6), 0, dp(8))
            }
        }
        if(view is ViewGroup) for(index in 0 until view.childCount) decorate(view.getChildAt(index))
    }

    fun finish(state: Bundle?) {
        bodies.forEach { body ->
            // Leave section headings and the instrument panel intact.
            for(i in 1 until body.childCount) {
                val card = body.getChildAt(i) as? ViewGroup ?: continue
                if (card.tag == INSTRUMENTS) continue
                for(j in 1 until card.childCount) decorate(card.getChildAt(j))
            }
        }
        select(state?.getInt("dashboardPage", HOME) ?: HOME)
    }

    fun save(state: Bundle) { state.putInt("dashboardPage", selected) }

    /** Back from a destination returns to the menu; on the menu itself the system handles it. */
    fun back(): Boolean = if (selected != HOME) { select(HOME); true } else false

    fun showLights(wifi: Boolean, adapter: Boolean, ecu: Boolean, recording: Boolean) {
        mapOf("Wi-Fi" to wifi, "Adapter" to adapter, "ECU" to ecu, "Rögzítés" to recording).forEach { (name, on) ->
            val dot = lights[name] ?: return@forEach
            val color = if (!on) Color.rgb(74, 85, 96) else if (name == "Rögzítés") Color.rgb(255, 107, 91) else Color.rgb(61, 220, 132)
            dot.background = shape(color, 6)
            (dot.parent as View).contentDescription = "$name: ${if (on) "aktív" else "nem aktív"}"
        }
    }

    private fun select(index: Int) {
        selected = if (index in headings.indices) index else HOME
        home.visibility = if (selected == HOME) View.VISIBLE else View.GONE
        brand.visibility = home.visibility
        menuButton.visibility = if (selected == HOME) View.GONE else View.VISIBLE
        pageTitle.visibility = menuButton.visibility
        pageTitle.text = headings.getOrNull(selected) ?: ""
        pages.forEachIndexed { i, page -> page.visibility = if(i == selected) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, tab ->
            val page = menu[i].first
            tab.isSelected = page == selected
            tab.contentDescription = headings[page] + if(page == selected) ", kiválasztva" else ""
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

    companion object {
        const val HOME = -1
        const val GAUGES = 4
        const val INSTRUMENTS = "instrument-panel"
        private val PRIMARY = setOf("Csatlakozás az adapterhez", "Élő adatok indítása", "Rögzítés indítása",
            "Folyamatos mérés és rögzítés indítása", "Mentett mérés mentése fájlba", "Friss diagnosztikai jelentés készítése",
            "Műszerfal indítása")
    }
}
