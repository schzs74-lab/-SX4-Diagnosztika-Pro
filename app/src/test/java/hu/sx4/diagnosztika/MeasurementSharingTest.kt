package hu.sx4.diagnosztika

import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class MeasurementSharingTest {
    @Before fun attachProviderToCurrentSandbox() {
        // FileProvider keeps a static root cache while Robolectric gives each test
        // a different filesDir. Reattach as Android does when a process starts.
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.resolveContentProvider(
            "${context.packageName}.measurements", PackageManager.GET_META_DATA
        )!!
        MeasurementFileProvider().attachInfo(context, info)
    }
    @Test fun shareUsesContentUriAndReadOnlyTemporaryGrant() {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.filesDir, "measurements").apply { mkdirs() }
        val file = File(dir, "sx4-demo-test.csv").apply { writeText("mode,value\nDEMO,0.4\n") }
        val intent = MeasurementSharing.shareIntent(context, file)
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme); assertEquals("text/csv", intent.type)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertEquals(file.readText(), context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
    }
    @Test fun backupsAndUnfinishedFilesAreOutsideTheShareFlow() {
        val context = RuntimeEnvironment.getApplication()
        val backup = File(context.filesDir, "diagnostic-backups/private.json").apply { parentFile!!.mkdirs(); writeText("private") }
        assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, "${context.packageName}.measurements", backup) }
        val partial = File(context.filesDir, "measurements/sx4-demo-test.csv.part").apply { parentFile!!.mkdirs(); writeText("unfinished") }
        assertThrows(IllegalArgumentException::class.java) { MeasurementSharing.shareIntent(context, partial) }
    }
    @Test fun saveLetsTheUserChooseTheDestination() {
        val intent = MeasurementSharing.saveIntent(File("sx4-demo-test.csv"))
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("text/csv", intent.type)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("sx4-demo-test.csv", intent.getStringExtra(Intent.EXTRA_TITLE))
    }
}
