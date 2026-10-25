package hu.sx4.diagnosztika

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object ReportSharing {
    fun shareIntent(context: Context, file: File): Intent {
        require(file.isFile && file.extension == "txt" && file.name.startsWith("sx4-report-") &&
            file.canonicalFile.parentFile == File(context.filesDir, "reports").canonicalFile)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.measurements", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("SX4 diagnosztikai jelentés", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    fun saveIntent(file: File) = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, file.name)
    }
}
