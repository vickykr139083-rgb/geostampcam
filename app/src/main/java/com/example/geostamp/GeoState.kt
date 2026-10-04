package com.example.geostamp

import android.graphics.Bitmap
import android.location.Location

/** Kept at process level so rotating the phone doesn't lose the address/map. */
object GeoState {
    @Volatile var loc: Location? = null
    @Volatile var autoPlace: Place? = null
    @Volatile var manualPlace: Place? = null
    @Volatile var manualAt: Location? = null
    @Volatile var mapBmp: Bitmap? = null
    @Volatile var mapFor: Location? = null
    @Volatile var geocodedAt: Location? = null
    @Volatile var lastAttempt = 0L
}
