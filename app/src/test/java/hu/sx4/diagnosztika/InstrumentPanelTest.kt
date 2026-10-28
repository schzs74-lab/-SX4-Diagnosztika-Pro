package hu.sx4.diagnosztika

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class InstrumentPanelTest {
    private fun get(a: MainActivity, name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(a)
    private fun invoke(a: MainActivity, name: String, vararg args: Any) =
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(a, *args)
    private fun gauge(a: MainActivity, pid: Int) = a.window.decorView.findViewWithTag<GaugeView>("gauge-%02X".format(pid))

    /** Lets the 200 ms polling schedule deliver at least one full demo cycle to the UI thread. */
    private fun poll(a: MainActivity) {
        repeat(12) {
            Thread.sleep(250)
            (get(a, "worker") as ScheduledExecutorService).submit {}.get(3, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun demoValuesReachTheDialsAndStoppingRemovesEveryNeedle() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a = controller.get()
            a.window.decorView.findViewWithTag<View>("dashboard-tab-4").performClick()
            invoke(a, "startReading"); poll(a)
            val rpm = gauge(a, 0x0C).reading
            assertTrue(rpm is GaugeReading.Value && rpm.value == 780.0 && !rpm.stale)
            assertTrue(gauge(a, 0x05).reading is GaugeReading.Value)
            // Speed is not in the demo support mask: it must never be shown as a value.
            assertEquals(GaugeReading.Unsupported, gauge(a, 0x0D).reading)
            assertEquals("Sebesség: az ECU nem támogatja", gauge(a, 0x0D).contentDescription)

            val content = a.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            content.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, 360, 800)
            val bitmap = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888); content.draw(Canvas(bitmap))
            val dir = File(System.getProperty("sx4.previewDir", "build/reports/screenshots")).apply { mkdirs() }
            File(dir, "instruments-live.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()

            invoke(a, "pauseMeasurement", "teszt")
            for (pid in listOf(0x0C, 0x05, 0x06, 0x07)) assertEquals(GaugeReading.NoData, gauge(a, pid).reading)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun pageDotsSwitchBetweenEngineAndMixturePages() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a = controller.get()
            val panel = get(a, "instruments") as InstrumentPanel
            assertEquals(0, panel.page)
            val enginePage = gauge(a, 0x0C).parent as View
            assertEquals(View.VISIBLE, enginePage.visibility)
            a.window.decorView.findViewWithTag<View>("gauge-page-1").performClick()
            assertEquals(1, panel.page)
            assertEquals(View.GONE, enginePage.visibility)
            assertTrue(a.window.decorView.findViewWithTag<View>("gauge-page-1").isSelected)
            a.window.decorView.findViewWithTag<View>("gauge-page-0").performClick()
            assertEquals(0, panel.page)
        } finally { controller.pause().stop().destroy() }
    }
}
