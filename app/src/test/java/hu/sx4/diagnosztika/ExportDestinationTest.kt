package hu.sx4.diagnosztika

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,35])
class ExportDestinationTest {
    class Provider : ContentProvider() {
        lateinit var file: File
        var reject = "unsupported"
        var closedDescriptor = false
        val modes=mutableListOf<String>()
        override fun onCreate()=true
        override fun getType(uri:Uri)="text/csv"
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
        override fun insert(uri:Uri,values:ContentValues?):Uri?=null
        override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
        override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            modes+=mode
            if(mode=="wt") when(reject) {
                "missing" -> throw FileNotFoundException("unsupported")
                "argument" -> throw IllegalArgumentException("unsupported")
                "unsupported" -> throw UnsupportedOperationException("unsupported")
                "security" -> throw SecurityException("denied")
                "io" -> throw IOException("provider failed")
            }
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
                (if(mode=="wt") ParcelFileDescriptor.MODE_TRUNCATE else 0)).also { if(closedDescriptor) it.close() }
        }
    }
    private fun provider():Pair<Provider,Uri> {
        val context=RuntimeEnvironment.getApplication()
        val p=Provider();p.file=File(context.cacheDir,"provider.csv").apply{writeText("OLD-TAIL-TO-REMOVE")}
        p.attachInfo(context,ProviderInfo().apply{authority="test.exports";exported=true})
        ShadowContentResolver.registerProviderInternal("test.exports",p)
        return p to Uri.parse("content://test.exports/file")
    }
    @Test fun unsupportedTruncatingModesFallBackToVerifiedTruncation() {
        val context=RuntimeEnvironment.getApplication();val (p,uri)=provider()
        val source=File(context.cacheDir,"prepared.csv").apply{writeText("X")}
        for(reason in listOf("missing","argument","unsupported")) {
            p.file.writeText("OLD-TAIL-TO-REMOVE");p.reject=reason;p.modes.clear()
            ExportDestination.copy(context.contentResolver,uri,source)
            assertEquals(listOf("wt","w"),p.modes);assertEquals("X",p.file.readText())
        }
    }
    @Test fun permissionAndGeneralIoFailuresNeverRetryAsPlainWrite() {
        val context=RuntimeEnvironment.getApplication();val (p,uri)=provider()
        val source=File(context.cacheDir,"prepared.csv").apply{writeText("X")}
        for(reason in listOf("security","io")) {
            p.reject=reason;p.modes.clear()
            assertThrows(Exception::class.java){ExportDestination.copy(context.contentResolver,uri,source)}
            assertEquals(listOf("wt"),p.modes);assertEquals("OLD-TAIL-TO-REMOVE",p.file.readText())
        }
    }
    @Test fun unusableFallbackDescriptorFailsWithoutWritingPayload() {
        val context=RuntimeEnvironment.getApplication();val (p,uri)=provider();p.closedDescriptor=true
        val source=File(context.cacheDir,"prepared.csv").apply{writeText("X")}
        assertThrows(Exception::class.java){ExportDestination.copy(context.contentResolver,uri,source)}
        assertEquals(listOf("wt","w"),p.modes);assertEquals("OLD-TAIL-TO-REMOVE",p.file.readText())
    }
    @Test fun supportedTruncatingModeDoesNotUseFallback() {
        val context=RuntimeEnvironment.getApplication();val (p,uri)=provider();p.reject="none"
        val source=File(context.cacheDir,"prepared.csv").apply{writeText("OK")}
        ExportDestination.copy(context.contentResolver,uri,source)
        assertEquals(listOf("wt"),p.modes);assertEquals("OK",p.file.readText())
    }
}
