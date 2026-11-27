package io.github.lcebot.ferryqr.ui

import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The most of a clip the page takes in. It is beyond what any format holds, so a longer clip still
 * reads as too long rather than becoming a code of its beginning.
 */
private const val CLIP_MAX_CHARS = 4096

/**
 * The text on the clipboard, up to [CLIP_MAX_CHARS]. Some apps put text on the clipboard as a
 * content URI, which `coerceToText` would read to its end however large it is, so the URI is read
 * here only as far as the field can take. It asks the clipboard and possibly another app's
 * provider, so it runs off the main thread.
 */
internal fun readClipboardText(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    if (clipboard.primaryClipDescription?.hasMimeType("text/*") != true) return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    val item = clip.getItemAt(0)
    item.text?.let { return it.take(CLIP_MAX_CHARS).toString() }
    item.uri?.let { uri ->
        // A URI that cannot be read as text stands for itself, unless it points into this device,
        // as coerceToText treats it.
        return readText(context, uri) ?: uri.toString().take(CLIP_MAX_CHARS).takeIf { uri.scheme !in LOCAL_SCHEMES }
    }
    return item.intent?.toUri(Intent.URI_INTENT_SCHEME)?.take(CLIP_MAX_CHARS)
}

/** URI schemes that name something on this device rather than a link worth showing as text. */
private val LOCAL_SCHEMES = setOf(ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_ANDROID_RESOURCE, ContentResolver.SCHEME_FILE)

/** The first [CLIP_MAX_CHARS] of the text behind [uri], in UTF-8 as the clipboard reads it, or null when it cannot be read. */
private fun readText(context: Context, uri: Uri): String? = try {
    context.contentResolver.openTypedAssetFileDescriptor(uri, "text/*", null)?.use { descriptor ->
        descriptor.createInputStream().bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(CLIP_MAX_CHARS)
            var read = 0
            while (read < buffer.size) {
                val count = reader.read(buffer, read, buffer.size - read)
                if (count < 0) break
                read += count
            }
            String(buffer, 0, read)
        }
    }
} catch (e: Exception) {
    // A provider may refuse, go away or fail midway, and the clip then counts as unreadable.
    null
}
