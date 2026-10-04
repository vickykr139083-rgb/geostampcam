package com.example.geostamp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.location.Location
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Builds the small satellite thumbnail for the stamp from Esri World Imagery tiles.
 * (Google map tiles cannot be used without a paid key.)
 * To use a street map instead, change TILE_URL to an OpenStreetMap-style URL.
 */
object MapTiles {
    private const val Z = 18
    private const val TILE_URL =
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/%d/%d/%d" // z/y/x

    fun fetch(l: Location): Bitmap? {
        val n = (1 shl Z).toDouble()
        val latRad = Math.toRadians(l.latitude)
        val fx = (l.longitude + 180.0) / 360.0 * n
        val fy = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n
        val tx = fx.toInt()
        val ty = fy.toInt()

        val mosaic = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888)
        val cv = Canvas(mosaic)
        for (dy in -1..1) for (dx in -1..1) {
            val t = tile(tx + dx, ty + dy) ?: return null
            cv.drawBitmap(t, ((dx + 1) * 256).toFloat(), ((dy + 1) * 256).toFloat(), null)
        }
        val px = ((fx - tx) * 256).toInt() + 256
        val py = ((fy - ty) * 256).toInt() + 256
        val half = 128
        return Bitmap.createBitmap(mosaic, px - half, py - half, half * 2, half * 2)
    }

    private fun tile(x: Int, y: Int): Bitmap? = try {
        val c = URL(String.format(Locale.US, TILE_URL, Z, y, x)).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 4000
        c.setRequestProperty("User-Agent", "GeoStampCam/1.0 (personal photo app)")
        c.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }
}
