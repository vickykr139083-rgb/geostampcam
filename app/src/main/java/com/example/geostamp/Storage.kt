package com.example.geostamp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import java.io.File

/** App-private folder: photos here do NOT show up in the phone's Gallery. */
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
