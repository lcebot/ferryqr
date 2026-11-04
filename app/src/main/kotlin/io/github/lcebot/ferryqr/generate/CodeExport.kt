package io.github.lcebot.ferryqr.generate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import io.github.lcebot.ferryqr.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every operation does file I/O and belongs on a background dispatcher. */
object CodeExport {

    /** The clipboard holds a content URI to a PNG in the cache, served by the app's FileProvider. */
    fun copy(context: Context, code: Bitmap): Boolean = try {
        val uri = cached(context, code, "ferryqr.png")
        val clip = ClipData.newUri(context.contentResolver, context.getString(R.string.generate_clip_label), uri)
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * A chooser that sends the code as a PNG to any app. The file is separate from the copied one,
     * so a later copy cannot change an image another app is still reading. The caller starts the
     * returned intent; null means the image could not be written.
     */
    fun share(context: Context, code: Bitmap): Intent? = try {
        val uri = cached(context, code, "share.png")
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The clip lets the chooser show the image as its preview.
        send.clipData = ClipData.newUri(context.contentResolver, context.getString(R.string.generate_clip_label), uri)
        Intent.createChooser(send, null)
    } catch (e: Exception) {
        null
    }

    private fun cached(context: Context, code: Bitmap, name: String): Uri {
        val dir = File(context.cacheDir, "qr").apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use { CodeGenerator.forExport(code).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
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
