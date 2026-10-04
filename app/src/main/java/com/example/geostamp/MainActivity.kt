package com.example.geostamp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var info: TextView
    private lateinit var thumb: ImageView
    private lateinit var fused: FusedLocationProviderClient
    private var imageCapture: ImageCapture? = null

    @Volatile private var loc: Location? = null
    @Volatile private var autoPlace: Place? = null
    @Volatile private var manualPlace: Place? = null
    @Volatile private var manualAt: Location? = null
    @Volatile private var mapBmp: Bitmap? = null
    @Volatile private var mapFor: Location? = null
    private var geocodedAt: Location? = null
    private var lastAttempt = 0L

    private val io = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()

    private val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { g ->
            if (g[Manifest.permission.CAMERA] == true &&
                g[Manifest.permission.ACCESS_FINE_LOCATION] == true
            ) start() else toast("Camera and precise location permission are required")
        }

    private val locCb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.lastLocation?.let { onFix(it) }
        }
    }

    // Keeps the saved photo upright when the phone is held sideways
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                imageCapture?.targetRotation = when {
                    o >= 315 || o < 45 -> Surface.ROTATION_0
                    o < 135 -> Surface.ROTATION_270
                    o < 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false) // true full screen
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        info = findViewById(R.id.info)
        thumb = findViewById(R.id.thumb)
        fused = LocationServices.getFusedLocationProviderClient(this)

        findViewById<View>(R.id.shutter).setOnClickListener { capture() }
        info.setOnClickListener { editPlace() }
        thumb.setOnClickListener { startActivity(Intent(this, GalleryActivity::class.java)) }

        if (permissions.all { granted(it) }) start() else permLauncher.launch(permissions)
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        refreshThumb()
    }

    override fun onPause() {
        super.onPause()
        orientationListener.disable()
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
                .setTargetAspectRatio(AspectRatio.RATIO_16_9).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------- Location ----------
    @Suppress("MissingPermission")
    private fun startLocation() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        fused.requestLocationUpdates(req, locCb, Looper.getMainLooper())
    }

    private fun onFix(l: Location) {
        loc = l
        updateInfo()
        val last = geocodedAt
        val now = System.currentTimeMillis()
        if ((last == null || last.distanceTo(l) > 25f) && now - lastAttempt > 4000) {
            lastAttempt = now
            geocodedAt = l
            net.execute { refreshPlaceAndMap(l) }
        }
    }

    private fun refreshPlaceAndMap(l: Location) {
        val p = geocode(l)
        if (p != null) autoPlace = p else geocodedAt = null // retry later
        val mb = MapTiles.fetch(l)
        if (mb != null) { mapBmp = mb; mapFor = l }
        runOnUiThread { updateInfo() }
    }

    @Suppress("DEPRECATION")
    private fun geocode(l: Location): Place? = try {
        val list = Geocoder(this, Locale.getDefault()).getFromLocation(l.latitude, l.longitude, 5)
        val a = list?.firstOrNull { !it.postalCode.isNullOrBlank() } ?: list?.firstOrNull()
        if (a == null) null else {
            val flag = a.countryCode?.takeIf { it.length == 2 }?.uppercase()?.let { cc ->
                String(Character.toChars(0x1F1E6 + (cc[0] - 'A'))) +
                    String(Character.toChars(0x1F1E6 + (cc[1] - 'A')))
            } ?: ""
            val title = listOfNotNull(a.locality ?: a.subAdminArea, a.adminArea, a.countryName)
                .joinToString(", ")
            Place(title, a.getAddressLine(0) ?: "", flag)
        }
    } catch (e: Exception) {
        null
    }

    private fun currentPlace(): Place? {
        val mp = manualPlace
        val at = manualAt
        val l = loc
        if (mp != null && at != null && l != null && at.distanceTo(l) < 150f) return mp
        return autoPlace
    }

    private fun updateInfo() {
        val l = loc ?: return
        val p = currentPlace()
        info.text = buildString {
            if (p != null) {
                append(p.title).append('\n')
                if (p.address.isNotBlank()) append(p.address).append('\n')
            }
            append(StampPainter.coords(l)).append("  (±").append(l.accuracy.roundToInt()).append(" m)\n")
            append(StampPainter.timeText(System.currentTimeMillis()))
        }
    }

    /** Tap the info box to correct the heading / pincode by hand. */
    private fun editPlace() {
        val p = currentPlace()
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val t = EditText(this).apply { hint = "Heading (City, State, Country)"; setText(p?.title ?: "") }
        val a = EditText(this).apply { hint = "Full address with pincode"; setText(p?.address ?: "") }
        lay.addView(t); lay.addView(a)
        AlertDialog.Builder(this)
            .setTitle("Edit address")
            .setView(lay)
            .setPositiveButton("Use this") { _, _ ->
                manualPlace = Place(t.text.toString(), a.text.toString(), p?.flag ?: "")
                manualAt = loc
                updateInfo()
            }
            .setNeutralButton("Auto") { _, _ ->
                manualPlace = null; manualAt = null; updateInfo()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Capture ----------
    private fun capture() {
        val ic = imageCapture ?: return
        val l = loc
        if (l == null) { toast("Waiting for GPS fix…"); return }
        if (l.accuracy > 50f) toast("Weak GPS (±${l.accuracy.roundToInt()} m). Go near open sky for better accuracy.")

        val mb = mapBmp
        val mf = mapFor
        val usableMap = if (mb != null && mf != null && mf.distanceTo(l) < 30f) mb else null
        val snap = Snap(l, currentPlace(), System.currentTimeMillis(), usableMap)
        val screenRatio = maxOf(previewView.width, previewView.height).toFloat() /
            minOf(previewView.width, previewView.height).coerceAtLeast(1)

        val raw = File.createTempFile("raw", ".jpg", cacheDir)
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(raw).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    io.execute { stampAndSave(raw, snap, screenRatio) }
                }
                override fun onError(e: ImageCaptureException) {
                    toast("Capture failed: ${e.message}")
                }
            })
    }

    private fun stampAndSave(raw: File, s: Snap, screenRatio: Float) {
        try {
            val orientation = ExifInterface(raw.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(raw.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
            val src = BitmapFactory.decodeFile(raw.path, BitmapFactory.Options().apply {
                inSampleSize = sample; inMutable = true
            }) ?: error("decode failed")

            val rot = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            var bmp = if (rot == 0f) src else Bitmap.createBitmap(
                src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rot) }, true
            )

            // Portrait shots: crop to the screen shape so the photo = what you saw (full screen)
            if (bmp.height > bmp.width) {
                val cur = bmp.height.toFloat() / bmp.width
                if (cur < screenRatio) {
                    val nw = (bmp.height / screenRatio).toInt()
                    bmp = Bitmap.createBitmap(bmp, (bmp.width - nw) / 2, 0, nw, bmp.height)
                } else if (cur > screenRatio) {
                    val nh = (bmp.width * screenRatio).toInt()
                    bmp = Bitmap.createBitmap(bmp, 0, (bmp.height - nh) / 2, bmp.width, nh)
                }
            }
            if (!bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true)

            val map = s.map ?: MapTiles.fetch(s.loc)
            StampPainter.draw(Canvas(bmp), bmp.width, bmp.height, s.copy(map = map))

            val name = "GEO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(s.time)) + ".jpg"
            val out = File(photosDir(this), name)
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }

            ExifInterface(out.absolutePath).apply {
                setGpsInfo(s.loc)
                setAttribute(
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(s.time))
                )
                saveAttributes()
            }
            raw.delete()
            toast("Saved")
            refreshThumb()
        } catch (e: Exception) {
            toast("Error: ${e.message}")
        }
    }

    private fun refreshThumb() {
        io.execute {
            val latest = listPhotos(this).firstOrNull()
            val bm = latest?.let { decodeSampled(it.absolutePath, 200) }
            runOnUiThread {
                if (bm != null) thumb.setImageBitmap(bm) else thumb.setImageDrawable(null)
            }
        }
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        fused.removeLocationUpdates(locCb)
        io.shutdown()
        net.shutdown()
    }
}
