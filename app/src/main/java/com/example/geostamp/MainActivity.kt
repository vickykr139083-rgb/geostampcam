package com.example.geostamp

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity(), LocationListener {

    private data class Place(val title: String, val address: String)
    private data class Snap(val loc: Location, val place: Place?, val time: Long)

    private lateinit var previewView: PreviewView
    private lateinit var info: TextView
    private lateinit var lm: LocationManager
    private var imageCapture: ImageCapture? = null

    @Volatile private var loc: Location? = null
    @Volatile private var place: Place? = null
    private var geocodedAt: Location? = null
    private val io = Executors.newSingleThreadExecutor()

    private val permissions: Array<String> by lazy {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT <= 28) list += Manifest.permission.WRITE_EXTERNAL_STORAGE
        list.toTypedArray()
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.CAMERA] == true &&
                grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
            ) start() else toast("Camera and precise location permission are required")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        info = findViewById(R.id.info)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        findViewById<View>(R.id.shutter).setOnClickListener { capture() }

        if (permissions.all { granted(it) }) start() else permLauncher.launch(permissions)
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun start() {
        startCamera()
        startLocation()
    }

    // ---------- Camera ----------
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------- Location ----------
    @Suppress("MissingPermission")
    private fun startLocation() {
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (lm.allProviders.contains(p)) {
                lm.requestLocationUpdates(p, 2000L, 0f, this)
                lm.getLastKnownLocation(p)?.let { onLocationChanged(it) }
            }
        }
    }

    override fun onLocationChanged(l: Location) {
        val cur = loc
        val better = cur == null || l.accuracy <= cur.accuracy ||
            l.time - cur.time > 20_000
        if (!better) return
        loc = l
        updateInfo()
        val last = geocodedAt
        if (last == null || last.distanceTo(l) > 25f) {
            geocodedAt = l
            io.execute { reverseGeocode(l) }
        }
    }

    @Deprecated("Required on API < 30")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    @Suppress("DEPRECATION")
    private fun reverseGeocode(l: Location) {
        try {
            val a = Geocoder(this, Locale.getDefault())
                .getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull() ?: return
            val title = listOfNotNull(
                a.locality ?: a.subAdminArea, a.adminArea, a.countryName
            ).distinct().joinToString(", ")
            place = Place(title, a.getAddressLine(0) ?: "")
            runOnUiThread { updateInfo() }
        } catch (_: Exception) { /* offline: stamp falls back to coordinates */ }
    }

    private fun updateInfo() {
        val l = loc ?: return
        val p = place
        info.text = buildString {
            if (p != null) append(p.title).append('\n')
            append(coords(l)).append('\n')
            append(timeText(System.currentTimeMillis()))
        }
    }

    // ---------- Capture ----------
    private fun capture() {
        val ic = imageCapture ?: return
        val l = loc
        if (l == null) { toast("Waiting for GPS fix…"); return }
        val snap = Snap(l, place, System.currentTimeMillis())
        val raw = File.createTempFile("raw", ".jpg", cacheDir)
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(raw).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    io.execute { stampAndSave(raw, snap) }
                }
                override fun onError(e: ImageCaptureException) {
                    toast("Capture failed: ${e.message}")
                }
            })
    }

    private fun stampAndSave(raw: File, s: Snap) {
        try {
            val orientation = ExifInterface(raw)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

            // Decode (downsample very large frames to avoid OOM)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(raw.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
            val src = BitmapFactory.decodeFile(raw.path, BitmapFactory.Options().apply {
                inSampleSize = sample; inMutable = true
            }) ?: error("decode failed")

            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            }
            var bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            if (!bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true)

            drawStamp(Canvas(bmp), bmp.width, bmp.height, s)
            val uri = saveToGallery(bmp, s)
            raw.delete()
            toast(if (uri != null) "Saved to Pictures/GeoStampCam" else "Save failed")
        } catch (e: Exception) {
            toast("Error: ${e.message}")
        }
    }

    // ---------- Stamp drawing ----------
    private fun drawStamp(c: Canvas, w: Int, h: Int, s: Snap) {
        val pad = w * 0.035f
        val textW = (w - 2 * pad).toInt()

        fun paint(size: Float, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setShadowLayer(size * 0.08f, 0f, 0f, Color.BLACK)
        }
        fun layout(t: String, p: TextPaint) =
            StaticLayout.Builder.obtain(t, 0, t.length, p, textW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

        val lines = mutableListOf<StaticLayout>()
        s.place?.let {
            lines += layout(it.title, paint(w * 0.052f, true))
            if (it.address.isNotBlank()) lines += layout(it.address, paint(w * 0.034f, false))
        }
        lines += layout(coords(s.loc), paint(w * 0.034f, false))
        lines += layout(timeText(s.time), paint(w * 0.034f, false))

        val gap = w * 0.008f
        val total = lines.sumOf { it.height.toDouble() }.toFloat() + gap * (lines.size - 1)
        val top = h - total - 2 * pad

        c.drawRect(0f, top, w.toFloat(), h.toFloat(),
            Paint().apply { color = Color.argb(150, 0, 0, 0) })

        var y = top + pad
        for (l in lines) {
            c.save(); c.translate(pad, y); l.draw(c); c.restore()
            y += l.height + gap
        }
    }

    private fun coords(l: Location) =
        "Lat %.6f° Long %.6f°".format(Locale.US, l.latitude, l.longitude)

    private fun timeText(ms: Long): String {
        val tz = TimeZone.getDefault()
        val off = tz.getOffset(ms) / 60000
        val sign = if (off < 0) "-" else "+"
        val gmt = "GMT %s%02d:%02d".format(sign, abs(off) / 60, abs(off) % 60)
        val f = SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm a", Locale.ENGLISH).apply { timeZone = tz }
        return "${f.format(Date(ms))} $gmt"
    }

    // ---------- Save + EXIF ----------
    private fun saveToGallery(bmp: Bitmap, s: Snap): android.net.Uri? {
        val name = "GEO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(s.time)) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, s.time)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GeoStampCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }

        // Also embed real GPS EXIF (pixels are already upright -> orientation normal)
        contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            ExifInterface(pfd.fileDescriptor).apply {
                setGpsInfo(s.loc)
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                setAttribute(
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(s.time))
                )
                saveAttributes()
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
        }
        return uri
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        lm.removeUpdates(this)
        io.shutdown()
    }
}
