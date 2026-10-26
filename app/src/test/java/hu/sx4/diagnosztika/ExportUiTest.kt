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
@Config(sdk=[28,35])
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

    @Test fun sourceAndFormatSurviveActualActivityRecreationForBothFormats() {
        for (hungarian in listOf(false,true)) {
            val first = Robolectric.buildActivity(MainActivity::class.java).setup()
            val file = recording(first.get())
            val original = file.readBytes()
            field(first.get(), "pendingExportName").set(first.get(),file.name)
            field(first.get(), "pendingExportHungarian").set(first.get(),hungarian)
            val state=android.os.Bundle();first.saveInstanceState(state)
            first.pause().stop().destroy()
            val restored=Robolectric.buildActivity(MainActivity::class.java).create(state).start().restoreInstanceState(state).resume().visible()
            try {
                val activity=restored.get()
                assertEquals(file.name,field(activity,"pendingExportName").get(activity))
                assertEquals(hungarian,field(activity,"pendingExportHungarian").get(activity))
                val uri=Uri.parse("content://test.documents/restored-$hungarian.csv");val out=ByteArrayOutputStream()
                shadowOf(activity.contentResolver).registerOutputStream(uri,out)
                deliver(activity,uri)
                val expected=if(hungarian)CsvExport.toHungarianExcel(original.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8) else original
                assertArrayEquals(expected,out.toByteArray());assertArrayEquals(original,file.readBytes())
                assertEquals(false,field(activity,"pendingExportHungarian").get(activity))
                assertTrue(File(activity.cacheDir,"csv-export").listFiles()!!.isEmpty())
            } finally{restored.pause().stop().destroy()}
        }
    }

    @Test fun malformedSourceNeverOpensOrChangesTheDestination() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity=controller.get();val file=recording(activity)
            file.writeText(file.readText().replaceFirst("\"2\",","\"2\"x,"))
            val uri=Uri.parse("content://test.exports/file")
            val provider=ExportDestinationTest.Provider().apply {
                this.file=File(activity.cacheDir,"untouched.csv").apply{writeText("KEEP")}
                attachInfo(activity,android.content.pm.ProviderInfo().apply{authority="test.exports";exported=true})
            }
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("test.exports",provider)
            field(activity,"pendingExportName").set(activity,file.name);field(activity,"pendingExportHungarian").set(activity,true)
            deliver(activity,uri)
            assertEquals("KEEP",provider.file.readText());assertTrue(provider.modes.isEmpty())
            assertTrue(File(activity.cacheDir,"csv-export").listFiles()!!.isEmpty())
            assertTrue((field(activity,"recordingInfo").get(activity) as android.widget.TextView).text.contains("sikertelen"))
        } finally{controller.pause().stop().destroy()}
    }

    @Test fun cancelledPickerWritesNothingAndClearsPendingFormat() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity=controller.get();val file=recording(activity)
            field(activity,"pendingExportName").set(activity,file.name);field(activity,"pendingExportHungarian").set(activity,true)
            MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java)
                .apply{isAccessible=true}.invoke(activity,4102,Activity.RESULT_CANCELED,null)
            assertNull(field(activity,"pendingExportName").get(activity));assertEquals(false,field(activity,"pendingExportHungarian").get(activity))
            assertFalse(File(activity.cacheDir,"csv-export").exists())
        } finally{controller.pause().stop().destroy()}
    }

    @Test fun saveIntentUsesTheChosenTitle() {
        val intent = MeasurementSharing.saveIntent(File("sx4-demo-test.csv"), CsvExport.hungarianName("sx4-demo-test.csv"))
        assertEquals("sx4-demo-test-excel-hu.csv", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals("sx4-demo-test.csv", MeasurementSharing.saveIntent(File("sx4-demo-test.csv")).getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test fun restoredReportExportIsByteExactAndNeverUsesPendingCsvFormat() {
        val first=Robolectric.buildActivity(MainActivity::class.java).setup()
        val file=DiagnosticReport.save(File(first.get().filesDir,"reports"),DiagnosticReport.demo("test"))
        field(first.get(),"pendingReportExportName").set(first.get(),file.name)
        field(first.get(),"pendingExportHungarian").set(first.get(),true)
        val state=android.os.Bundle();first.saveInstanceState(state);first.pause().stop().destroy()
        val restored=Robolectric.buildActivity(MainActivity::class.java).create(state).start().restoreInstanceState(state).resume().visible()
        try {
            val a=restored.get();val uri=Uri.parse("content://test.documents/report.txt");val out=ByteArrayOutputStream()
            shadowOf(a.contentResolver).registerOutputStream(uri,out)
            MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java)
                .apply{isAccessible=true}.invoke(a,4103,Activity.RESULT_OK,Intent().setData(uri))
            settle(a);assertArrayEquals(file.readBytes(),out.toByteArray())
            assertNull(field(a,"pendingReportExportName").get(a))
        } finally{restored.pause().stop().destroy()}
    }
}

