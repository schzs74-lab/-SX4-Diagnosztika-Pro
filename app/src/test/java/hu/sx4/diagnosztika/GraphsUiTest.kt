package hu.sx4.diagnosztika

import android.os.Looper
import android.widget.Switch
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
class GraphsUiTest {
    private fun get(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
    private fun invoke(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible=true }.invoke(activity)
    private fun settle(activity: MainActivity) {
        (get(activity,"worker") as ScheduledExecutorService).submit {}.get(3,TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun modeChangeFinalizesDemoRecordingWithoutMixingRealData() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity=controller.get(); invoke(activity,"startRecording"); settle(activity)
            assertNotNull(get(activity,"recording"))
            (get(activity,"demo") as Switch).isChecked=false
            settle(activity)
            assertNull(get(activity,"recording")); assertEquals(false,get(activity,"measuring"))
            val files=MeasurementLog.saved(File(activity.filesDir,"measurements"))
            assertEquals(1,files.size); assertTrue(files.single().readText().contains("\"DEMO\",\"DEMO\""))
            assertFalse(files.single().readText().contains("\"REAL\""))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun backgroundingClosesTheRecordingBeforeWorkerShutdown() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity=controller.get()
        try {
            invoke(activity,"startRecording"); settle(activity)
            controller.pause().stop(); settle(activity)
            assertNull(get(activity,"recording"))
            val files=MeasurementLog.saved(File(activity.filesDir,"measurements"))
            assertEquals(1,files.size); assertTrue(files.single().readText().contains("\"END\""))
        } finally { controller.destroy() }
    }
    @Test fun changingThePollingProfileStopsRecordingAndRequiresExplicitRestart() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity=controller.get(); invoke(activity,"startRecording"); settle(activity)
            (get(activity,"oxygenMode") as Switch).isChecked=true; settle(activity)
            assertNull(get(activity,"recording")); assertEquals(false,get(activity,"measuring"))
            invoke(activity,"startReading")
            assertEquals(listOf(20,21),get(activity,"activeSequence"))
        } finally { controller.pause().stop().destroy() }
    }
}
