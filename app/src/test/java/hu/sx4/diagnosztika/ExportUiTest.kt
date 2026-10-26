package hu.sx4.diagnosztika

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@LooperMode(LooperMode.Mode.PAUSED)
class ExportUiTest {
    private fun field(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun settle(activity: MainActivity) {
        (field(activity, "worker").get(activity) as ScheduledExecutorService).submit {}.get(3, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun deliver(activity: MainActivity, uri: Uri) {
        MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
            .apply { isAccessible = true }.invoke(activity, 4102, Activity.RESULT_OK, Intent().setData(uri))
        settle(activity)
    }
    private fun recording(activity: MainActivity): File {
        val log = MeasurementLog(File(activity.filesDir, "measurements"), "DEMO", "DEMO", "OXYGEN_ONLY", "test", 0, 1000)
        log.append(LiveSample.decode(0x06, listOf(100)), 1450)
        return log.finish("kézi mentés", 1500)
    }

    @Test fun hungarianExportWritesConvertedCopyAndLeavesTheStoredFileUnchanged() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get(); val file = recording(activity); val original = file.readText()
            val uri = Uri.parse("content://test.documents/hu.csv"); val out = ByteArrayOutputStream()
            shadowOf(activity.contentResolver).registerOutputStream(uri, out)
            field(activity, "pendingExportName").set(activity, file.name)
            field(activity, "pendingExportHungarian").set(activity, true)
            deliver(activity, uri)
            assertEquals(CsvExport.toHungarianExcel(original), out.toString(Charsets.UTF_8.name()))
            assertTrue(out.toString(Charsets.UTF_8.name()).contains("\"-21,875\""))
            assertEquals(original, file.readText())
            assertEquals(false, field(activity, "pendingExportHungarian").get(activity))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun standardExportIsAByteExactCopy() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get(); val file = recording(activity)
            val uri = Uri.parse("content://test.documents/std.csv"); val out = ByteArrayOutputStream()
            shadowOf(activity.contentResolver).registerOutputStream(uri, out)
            field(activity, "pendingExportName").set(activity, file.name)
            field(activity, "pendingExportHungarian").set(activity, false)
            deliver(activity, uri)
            assertArrayEquals(file.readBytes(), out.toByteArray())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun exportFormatSurvivesActivityRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            field(activity, "pendingExportName").set(activity, "sx4-demo-x.csv")
            field(activity, "pendingExportHungarian").set(activity, true)
            val state = android.os.Bundle(); controller.saveInstanceState(state)
            assertTrue(state.getBoolean("pendingExportHungarian"))
            assertEquals("sx4-demo-x.csv", state.getString("pendingExportName"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun saveIntentUsesTheChosenTitle() {
        val intent = MeasurementSharing.saveIntent(File("sx4-demo-test.csv"), CsvExport.hungarianName("sx4-demo-test.csv"))
        assertEquals("sx4-demo-test-excel-hu.csv", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals("sx4-demo-test.csv", MeasurementSharing.saveIntent(File("sx4-demo-test.csv")).getStringExtra(Intent.EXTRA_TITLE))
    }
}
