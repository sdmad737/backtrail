package com.backtrail.app.trip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.backtrail.app.contracts.ImageRef
import java.io.File
import java.util.UUID
import kotlin.math.max

/** Freezes image orientation and pixels into one app-owned, bounded JPEG per request. */
class PhotoImporter(private val context: Context) {
    fun import(uri: Uri): ImageRef {
        val directory = File(context.cacheDir, "smoke-photos").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val target = File(directory, "$id.jpg")
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = max(info.size.width, info.size.height).toFloat() / 1536
            if (scale > 1) decoder.setTargetSize((info.size.width / scale).toInt().coerceAtLeast(1), (info.size.height / scale).toInt().coerceAtLeast(1))
        }
        try {
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            // Imported images have an unknown original capture time.
            return ImageRef(id, target.absolutePath, capturedAtMillis = null)
        } catch (failure: Exception) {
            target.delete()
            throw failure
        } finally {
            bitmap.recycle()
        }
    }
}
