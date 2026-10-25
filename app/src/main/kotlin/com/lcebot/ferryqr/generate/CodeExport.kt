package com.lcebot.ferryqr.generate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.lcebot.ferryqr.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Both operations do file I/O and belong on a background dispatcher. */
object CodeExport {

    /** The clipboard holds a content URI to a PNG in the cache, served by the app's FileProvider. */
    fun copy(context: Context, code: Bitmap): Boolean = try {
        val dir = File(context.cacheDir, "qr").apply { mkdirs() }
        val file = File(dir, "ferryqr.png")
        file.outputStream().use { CodeGenerator.forExport(code).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val clip = ClipData.newUri(context.contentResolver, context.getString(R.string.generate_clip_label), uri)
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
        true
    } catch (e: Exception) {
        false
    }

    /** Writes to Pictures/FerryQR through MediaStore, which needs no storage permission. */
    fun save(context: Context, code: Bitmap): Boolean {
        val name = "FerryQR_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FerryQR")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            val stream = resolver.openOutputStream(uri) ?: throw IllegalStateException("no output stream")
            stream.use { CodeGenerator.forExport(code).compress(Bitmap.CompressFormat.PNG, 100, it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }
}
