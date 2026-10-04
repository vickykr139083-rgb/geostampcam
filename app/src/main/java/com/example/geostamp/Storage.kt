package com.example.geostamp

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** App-private folder (the "My photos" screen reads from here). */
fun photosDir(ctx: Context): File {
    val base = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.filesDir
    base.mkdirs()
    return base
}

fun listPhotos(ctx: Context): List<File> =
    photosDir(ctx).listFiles { f -> f.extension.equals("jpg", true) }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()

fun decodeSampled(path: String, maxSide: Int): Bitmap? {
    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, b)
    var s = 1
    while (maxOf(b.outWidth, b.outHeight) / s > maxSide) s *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
}

/** Copies a photo into the phone Gallery (Pictures/GeoStampCam). GPS EXIF is kept. */
fun exportToGallery(ctx: Context, src: File): Boolean = try {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, src.name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.DATE_TAKEN, src.lastModified())
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GeoStampCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
    ctx.contentResolver.openOutputStream(uri)!!.use { out ->
        src.inputStream().use { it.copyTo(out) }
    }
    if (Build.VERSION.SDK_INT >= 29) {
        ctx.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null)
    }
    true
} catch (e: Exception) {
    false
}
