package com.example.geostamp

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class PhotoActivity : AppCompatActivity() {

    private val files = mutableListOf<File>()
    private var index = 0
    private lateinit var img: ImageView
    private lateinit var counter: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo)
        img = findViewById(R.id.img)
        counter = findViewById(R.id.counter)

        intent.getStringArrayExtra("paths")?.forEach { files.add(File(it)) }
        index = intent.getIntExtra("index", 0)
        if (files.isEmpty()) { finish(); return }

        findViewById<Button>(R.id.close).setOnClickListener { finish() }
        findViewById<Button>(R.id.share).setOnClickListener { share() }
        findViewById<Button>(R.id.delete).setOnClickListener { confirmDelete() }

        // swipe left/right to move between photos
        var downX = 0f
        img.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> downX = ev.x
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX
                    if (dx < -150) show(index + 1) else if (dx > 150) show(index - 1)
                }
            }
            true
        }
        show(index)
    }

    private fun show(i: Int) {
        if (i < 0 || i >= files.size) return
        index = i
        img.setImageBitmap(decodeSampled(files[i].absolutePath, 2048))
        counter.text = "${i + 1} / ${files.size}"
    }

    private fun share() {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", files[index])
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share photo"))
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete this photo?")
            .setPositiveButton("Delete") { _, _ ->
                files[index].delete()
                files.removeAt(index)
                if (files.isEmpty()) finish() else show(index.coerceAtMost(files.size - 1))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
