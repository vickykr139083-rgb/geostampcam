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
import android.os.Build
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
import androidx.appcompat.widget.SwitchCompat
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

    private val io = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()

    private val required = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION
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

    // Tells the camera how the phone is really held (works even if auto-rotate is off)
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

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        findViewById<SwitchCompat>(R.id.galleryToggle).apply {
            isChecked = prefs.getBoolean("gallery", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("gallery", on).apply() }
        }

        findViewById<View>(R.id.shutter).setOnClickListener { capture() }
        info.setOnClickListener { editPlace() }
        thumb.setOnClickListener { startActivity(Intent(this, GalleryActivity::class.java)) }

        updateInfo()
        if (required.all { granted(it) }) start() else {
            val ask = mutableListOf(*required, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT <= 28) ask += Manifest.permission.WRITE_EXTERNAL_STORAGE
            permLauncher.launch(ask.toTypedArray())
        }
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
        GeoState.loc = l
        updateInfo()
        val last = GeoState.geocodedAt
        val now = System.currentTimeMillis()
        if ((last == null || last.distanceTo(l) > 25f) && now - GeoState.lastAttempt > 4000) {
            GeoState.lastAttempt = now
            GeoState.geocodedAt = l
            net.execute { refreshPlaceAndMap(l) }
        }
    }

    private fun refreshPlaceAndMap(l: Location) {
        val p = geocode(l)
        if (p != null) GeoState.autoPlace = p else GeoState.geocodedAt = null // retry later
        val mb = MapTiles.fetch(l)
        if (mb != null) { GeoState.mapBmp = mb; GeoState.mapFor = l }
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
        val mp = GeoState.manualPlace
        val at = GeoState.manualAt
        val l = GeoState.loc
        if (mp != null && at != null && l != null && at.distanceTo(l) < 150f) return mp
        return GeoState.autoPlace
    }

    private fun updateInfo() {
        val l = GeoState.loc ?: return
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
                GeoState.manualPlace = Place(t.text.toString(), a.text.toString(), p?.flag ?: "")
                GeoState.manualAt = GeoState.loc
                updateInfo()
            }
            .setNeutralButton("Auto") { _, _ ->
                GeoState.manualPlace = null; GeoState.manualAt = null; updateInfo()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Capture ----------
    private fun capture() {
        val ic = imageCapture ?: return
        val l = GeoState.loc
        if (l == null) { toast("Waiting for GPS fix…"); return }
        if (l.accuracy > 50f) toast("Weak GPS (±${l.accuracy.roundToInt()} m). Go near open sky for better accuracy.")

        val mb = GeoState.mapBmp
        val mf = GeoState.mapFor
        val usableMap = if (mb != null && mf != null && mf.distanceTo(l) < 30f) mb else null
        val snap = Snap(l, currentPlace(), System.currentTimeMillis(), usableMap)
        val pw = previewView.width.coerceAtLeast(1)
        val ph = previewView.height.coerceAtLeast(1)
        val screenRatio = maxOf(pw, ph).toFloat() / minOf(pw, ph)
        val uiLandscape = pw > ph

        val raw = File.createTempFile("raw", ".jpg", cacheDir)
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(raw).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    io.execute { stampAndSave(raw, snap, screenRatio, uiLandscape) }
                }
                override fun onError(e: ImageCaptureException) {
                    toast("Capture failed: ${e.message}")
                }
            })
    }

    /** Trim to the screen's shape (long side / short side = ratio) so photo = what you saw. */
    private fun cropToRatio(b: Bitmap, ratio: Float): Bitmap {
        val long = maxOf(b.width, b.height).toFloat()
        val short = minOf(b.width, b.height).toFloat()
        val cur = long / short
        val tall = b.height >= b.width
        return if (cur < ratio) {
            val ns = (long / ratio).toInt()
            if (tall) Bitmap.createBitmap(b, (b.width - ns) / 2, 0, ns, b.height)
            else Bitmap.createBitmap(b, 0, (b.height - ns) / 2, b.width, ns)
        } else if (cur > ratio) {
            val nl = (short * ratio).toInt()
            if (tall) Bitmap.createBitmap(b, 0, (b.height - nl) / 2, b.width, nl)
            else Bitmap.createBitmap(b, (b.width - nl) / 2, 0, nl, b.height)
        } else b
    }

    private fun stampAndSave(raw: File, s: Snap, screenRatio: Float, uiLandscape: Boolean) {
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

            // Crop only when the photo's orientation matches the on-screen preview's
            if ((bmp.width > bmp.height) == uiLandscape) bmp = cropToRatio(bmp, screenRatio)
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

            val toGallery = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("gallery", true)
            val shared = if (toGallery) exportToGallery(this, out) else false
            toast(if (shared) "Saved (also in Gallery)" else "Saved")
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
