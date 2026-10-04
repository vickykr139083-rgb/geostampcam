package com.example.geostamp

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors

class GalleryActivity : AppCompatActivity() {

    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private var files: List<File> = emptyList()
    private val exec = Executors.newFixedThreadPool(2)
    private val cache = LruCache<String, Bitmap>(80)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        grid.setOnItemClickListener { _, _, pos, _ ->
            startActivity(
                Intent(this, PhotoActivity::class.java)
                    .putExtra("paths", files.map { it.absolutePath }.toTypedArray())
                    .putExtra("index", pos)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        files = listPhotos(this)
        empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.title).text = "My photos (${files.size})"
        grid.adapter = Adapter()
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown()
    }

    private inner class Adapter : BaseAdapter() {
        private val size = resources.displayMetrics.widthPixels / 3

        override fun getCount() = files.size
        override fun getItem(position: Int) = files[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val iv = (convertView as? ImageView) ?: ImageView(this@GalleryActivity).apply {
                layoutParams = AbsListView.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF222222.toInt())
            }
            val path = files[position].absolutePath
            iv.tag = path
            val cached = cache.get(path)
            if (cached != null) {
                iv.setImageBitmap(cached)
            } else {
                iv.setImageDrawable(null)
                exec.execute {
                    val b = decodeSampled(path, 360) ?: return@execute
                    cache.put(path, b)
                    runOnUiThread { if (iv.tag == path) iv.setImageBitmap(b) }
                }
            }
            return iv
        }
    }
}
