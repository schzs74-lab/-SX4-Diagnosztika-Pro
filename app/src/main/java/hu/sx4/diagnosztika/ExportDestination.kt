package hu.sx4.diagnosztika

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Retry unsupported opening modes only, never an interrupted write. No unverified append fallback. */
object ExportDestination {
    fun copy(resolver: ContentResolver, uri: Uri, prepared: File) {
        // Hold the source descriptor before opening/truncating any target, even if cache is reclaimed.
        prepared.inputStream().use { input ->
            val expected = input.channel.size()
            val truncating = try { resolver.openOutputStream(uri,"wt") }
                catch (_: FileNotFoundException) { null }
                catch (_: IllegalArgumentException) { null }
                catch (_: UnsupportedOperationException) { null }
            if(truncating != null) {
                truncating.use { write(input,expected,it) }
                return
            }
            val descriptor = resolver.openFileDescriptor(uri,"w") ?: throw IOException("A célfájl nem nyitható meg")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                try { output.channel.truncate(0); output.channel.position(0) }
                catch (e: IOException) { throw IOException("A szolgáltató nem támogat ellenőrizhető, biztonságos fájlba mentést",e) }
                write(input,expected,output)
                output.channel.truncate(expected)
                check(output.channel.size() == expected) { "A célfájl hossza nem egyezik az exportéval" }
            }
        }
    }
    private fun write(source: InputStream, expected: Long, output: OutputStream) {
        check(source.copyTo(output) == expected) { "Az export másolása hiányos" }
        output.flush()
    }
}
