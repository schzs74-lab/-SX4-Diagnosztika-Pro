package hu.sx4.diagnosztika

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object MeasurementSharing {
    fun shareIntent(context: Context, file: File): Intent {
        require(file.isFile && file.extension == "csv" && file.canonicalFile.parentFile == File(context.filesDir, "measurements").canonicalFile)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.measurements", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("SX4 mérés", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    fun saveIntent(file: File, title: String = file.name): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = "text/csv"; putExtra(Intent.EXTRA_TITLE, title)
    }
}
