package hu.sx4.diagnosztika

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@LooperMode(LooperMode.Mode.PAUSED)
class ReportsUiTest {
    private fun get(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun set(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    private fun all(view: View): List<View> = listOf(view) + if(view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun click(a: MainActivity, label: String) = all(a.window.decorView).filterIsInstance<Button>().single { it.text.toString() == label }.performClick()
    private fun text(a: MainActivity, name: String) = (get(a, name) as TextView).text.toString()
    private fun flush(a: MainActivity) {
        (get(a, "worker") as ScheduledExecutorService).submit {}.get(3, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun rejectedAndBusyRequestsNeverShowFalseProgressOrEraseExistingData() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a = c.get(); (get(a, "demo") as Switch).isChecked = false
            click(a, "Összes hibakódtípus olvasása"); click(a, "Friss diagnosztikai jelentés készítése")
            assertFalse(text(a, "errors").contains("folyamatban"))
            assertFalse(text(a, "diagnosticReportInfo").contains("folyamatban…"))
            (get(a, "demo") as Switch).isChecked = true
            set(a, "operation", true); set(a, "measuring", true)
            val previous = text(a, "diagnosticReportInfo")
            click(a, "Friss diagnosztikai jelentés készítése")
            assertEquals(previous, text(a, "diagnosticReportInfo")); assertEquals(true, get(a, "measuring"))
        } finally { c.pause().stop().destroy() }
    }
    @Test fun demoReportIsPersistedAndCanBeReopenedAfterBackgrounding() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a = c.get()
            click(a, "Friss diagnosztikai jelentés készítése"); flush(a)
            val file = DiagnosticReport.saved(File(a.filesDir, "reports")).single()
            assertTrue(file.readText().contains("DEMO / SZIMULÁLT"))
            assertTrue(text(a, "diagnosticReportInfo").contains("mentve"))
            c.pause().stop().start().resume(); flush(a)
            assertTrue(text(a, "diagnosticReportInfo").contains("1 mentett jelentés"))
            assertTrue(file.isFile)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun demoCodesAreExplicitlySimulatedAndNeverClearUnknownRealOutcome() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a = c.get()
            val preferences = a.getSharedPreferences("diagnostic-state", 0)
            preferences.edit().putBoolean("clearPending", true).commit()
            click(a, "Összes hibakódtípus olvasása")
            val result = text(a, "errors")
            assertTrue(result.contains("DEMO / SZIMULÁLT")); assertTrue(result.contains("(03)"))
            assertTrue(result.contains("(07)")); assertTrue(result.contains("(0A)"))
            assertTrue(result.contains("NEM ELLENŐRIZHETŐ")); assertTrue(preferences.getBoolean("clearPending", false))
        } finally { c.pause().stop().destroy() }
    }
}
