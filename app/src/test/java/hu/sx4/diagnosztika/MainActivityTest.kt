package hu.sx4.diagnosztika

import android.app.AlertDialog
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import java.net.Socket
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityTest {
    private fun get(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun set(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    private fun text(activity: MainActivity, name: String) = (get(activity, name) as TextView).text.toString()
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun click(activity: MainActivity, label: String) = views(activity.window.decorView).filterIsInstance<Button>().single { it.text.toString() == label }.performClick()
    private fun realMode(activity: MainActivity) { (get(activity, "demo") as Switch).isChecked = false }

    /** No network traffic: enough connection state for testing UI acceptance and cancellation. */
    private fun connected(activity: MainActivity): Elm327 {
        val elm = get(activity, "elm") as Elm327
        set(elm, "socket", object : Socket() { override fun isConnected() = true })
        set(elm, "ready", true)
        set(elm, "ecuSources", mapOf("7E8" to setOf(12, 13)))
        elm.selectEcu("7E8")
        return elm
    }

    @Test fun rejectedReadsNeverShowProgress() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get(); realMode(activity)
            for (label in listOf("Tárolt hibakódok olvasása", "Állapot és Freeze Frame olvasása", "Adapter és ECU azonosítóinak olvasása")) click(activity, label)
            for (field in listOf("errors", "statusReport", "identity")) assertFalse(text(activity, field).contains("folyamatban"))
            assertTrue(text(activity, "status").contains("csatlakozz"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun busyRequestsLeaveExistingReadingsAndMeasurementUntouched() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get(); realMode(activity); connected(activity)
            set(activity, "operation", true); set(activity, "measuring", true)
            (get(activity, "values") as TextView).text = "Meglévő mérés"
            val before = listOf("errors", "statusReport", "identity").associateWith { text(activity, it) }
            for (label in listOf("Tárolt hibakódok olvasása", "Állapot és Freeze Frame olvasása", "Adapter és ECU azonosítóinak olvasása", "Hibakódok törlése")) click(activity, label)
            assertEquals(true, get(activity, "measuring"))
            assertEquals("Meglévő mérés", text(activity, "values"))
            before.forEach { (field, value) -> assertEquals(value, text(activity, field)) }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun displayedAgeExpiresWithoutAnotherNetworkResult() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get(); realMode(activity)
            set(activity, "measuring", true); set(activity, "activeSequence", listOf(12))
            @Suppress("UNCHECKED_CAST")
            val latest = get(activity, "latest") as MutableMap<Int, Pair<String, Long>>
            latest[12] = "Fordulatszám: 800 1/perc" to SystemClock.elapsedRealtime()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            val table = get(activity, "liveTable") as LiveTable
            assertTrue(table.text().contains("ELAVULT"))
            assertTrue(table.rows.first { it.text.contains("Fordulatszám") }.warn)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun unknownClearOutcomeSurvivesConnectionResetOnScreen() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get(); realMode(activity); val elm = connected(activity)
            val accepted: () -> Unit = {}
            val failure: (String) -> Unit = {}
            val block: (Int) -> Unit = { throw ClearOutcomeUnknown() }
            MainActivity::class.java.getDeclaredMethod("task", Function0::class.java, Function1::class.java, Function1::class.java)
                .apply { isAccessible = true }.invoke(activity, accepted, failure, block)
            (get(activity, "worker") as ScheduledExecutorService).submit {}.get(2, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(text(activity, "status").contains("BIZONYTALAN"))
            assertFalse(elm.isConnected())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun modeChangeDismissesClearConfirmationAndInvalidatesDisplayedData() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get(); realMode(activity); connected(activity)
            val dialog = AlertDialog.Builder(activity).setMessage("Törlési megerősítés").create()
            set(activity, "clearDialog", dialog); dialog.show()
            (get(activity, "demo") as Switch).isChecked = true
            assertFalse(dialog.isShowing)
            assertTrue(text(activity, "errors").contains("Nincs érvényes"))
            assertFalse((get(activity, "elm") as Elm327).isConnected())
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun pendingClearWarningSurvivesActivityRecreation() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        first.get().getSharedPreferences("diagnostic-state", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("clearPending", true).commit()
        first.pause().stop().destroy()
        val second = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertTrue(text(second.get(), "status").contains("BIZONYTALAN"))
        } finally {
            second.get().getSharedPreferences("diagnostic-state", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            second.pause().stop().destroy()
        }
    }

    @Test fun freshReadFromOtherEcuNeverErasesUnknownClearAttempt() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity=controller.get();realMode(activity)
            val prefs=activity.getSharedPreferences("diagnostic-state",0)
            ClearJournal(prefs).dispatch("7E8",6,"old-connection")
            val elm=get(activity,"elm") as Elm327
            set(elm,"socket",object:Socket(){
                override fun isConnected()=true
                override fun getInputStream()=java.io.ByteArrayInputStream("7E9 02 43 00\r>".toByteArray())
                override fun getOutputStream()=java.io.ByteArrayOutputStream()
            })
            set(elm,"ready",true);set(elm,"canProtocol",true);set(elm,"protocolNumber",6)
            set(elm,"ecuSources",mapOf("7E9" to setOf(12,13)));elm.selectEcu("7E9")
            click(activity,"Tárolt hibakódok olvasása")
            (get(activity,"worker") as ScheduledExecutorService).submit{}.get(3,TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(prefs.getBoolean("clearPending",false))
            assertTrue(text(activity,"status").contains("7E8"))
            assertTrue(text(activity,"errors").contains("Nincs tárolt hibakód"))
        } finally {controller.pause().stop().destroy()}
    }
}

