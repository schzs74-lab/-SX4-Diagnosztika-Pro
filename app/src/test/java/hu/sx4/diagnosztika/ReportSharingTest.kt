package hu.sx4.diagnosztika

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ReportSharingTest {
    @Before fun attach() {
        val context = RuntimeEnvironment.getApplication()
        MeasurementFileProvider().attachInfo(context, context.packageManager.resolveContentProvider(
            "${context.packageName}.measurements", PackageManager.GET_META_DATA)!!)
    }
    @Test fun completedTextReportCanBeReadThroughTemporaryReadGrant() {
        val context = RuntimeEnvironment.getApplication()
        val file = DiagnosticReport.save(File(context.filesDir, "reports"), DiagnosticReport.demo("test"))
        val intent = ReportSharing.shareIntent(context, file)
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme); assertEquals("text/plain", intent.type)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertEquals(file.readText(), context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
    }
    @Test fun partialReportsAndPrivateBackupsAreNotShareable() {
        val context = RuntimeEnvironment.getApplication()
        for (path in listOf("reports/sx4-report-test.txt.part", "diagnostic-backups/sx4-report-secret.txt")) {
            val file = File(context.filesDir, path).apply { parentFile!!.mkdirs(); writeText("private") }
            assertThrows(IllegalArgumentException::class.java) { ReportSharing.shareIntent(context, file) }
        }
        val privateFile = File(context.filesDir, "diagnostic-backups/secret.json").apply { parentFile!!.mkdirs(); writeText("secret") }
        assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, "${context.packageName}.measurements", privateFile) }
    }
    @Test fun saveUsesSystemDocumentPicker() {
        val intent = ReportSharing.saveIntent(File("sx4-report-demo.txt"))
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("text/plain", intent.type); assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("sx4-report-demo.txt", intent.getStringExtra(Intent.EXTRA_TITLE))
    }
}
