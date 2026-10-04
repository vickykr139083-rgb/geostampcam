package com.example.geostamp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Name shown in the little chip at the top-right of every photo. Change it here. */
const val APP_LABEL = "GeoStamp Cam"

data class Place(val title: String, val address: String, val flag: String = "")
data class Snap(val loc: Location, val place: Place?, val time: Long, val map: Bitmap?)

object StampPainter {

    fun coords(l: Location) =
        "Lat %.6f° Long %.6f°".format(Locale.US, l.latitude, l.longitude)

    fun timeText(ms: Long): String {
        val tz = TimeZone.getDefault()
        val off = tz.getOffset(ms) / 60000
        val sign = if (off < 0) "-" else "+"
        val gmt = "GMT %s%02d:%02d".format(sign, abs(off) / 60, abs(off) % 60)
        val f = SimpleDateFormat("EEEE, dd/MM/yyyy hh:mm a", Locale.ENGLISH).apply { timeZone = tz }
        return "${f.format(Date(ms))} $gmt"
    }

    private fun tp(size: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = size
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setShadowLayer(size * 0.06f, 0f, 0f, Color.argb(160, 0, 0, 0))
    }

    fun draw(c: Canvas, w: Int, h: Int, s: Snap) {
        // Everything is sized from the SHORT edge, so portrait and landscape look alike
        val u = minOf(w, h).toFloat()
        val m = u * 0.03f
        val pad = u * 0.03f
        val gap = u * 0.008f
        val map = s.map
        val mapS = if (map != null) u * 0.30f else 0f
        val boxLeft = if (map != null) m + mapS + u * 0.015f else m
        val boxRight = w - m
        val textW = (boxRight - boxLeft - 2 * pad).toInt().coerceAtLeast(100)

        fun wrap(t: String, p: TextPaint, maxLines: Int) =
            StaticLayout.Builder.obtain(t, 0, t.length, p, textW)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()

        val lines = mutableListOf<StaticLayout>()
        s.place?.let { p ->
            val title = if (p.flag.isNotEmpty()) p.title + " " + p.flag else p.title
            val titlePaint = tp(u * 0.05f)
            while (titlePaint.measureText(title) > textW && titlePaint.textSize > u * 0.028f) {
                titlePaint.textSize = titlePaint.textSize * 0.95f
            }
            lines += wrap(title, titlePaint, 1)
            if (p.address.isNotBlank()) lines += wrap(p.address, tp(u * 0.034f), 3)
        }
        lines += wrap(coords(s.loc), tp(u * 0.034f), 1)
        lines += wrap(timeText(s.time), tp(u * 0.034f), 1)

        var textH = 0f
        lines.forEachIndexed { i, l -> textH += l.height + if (i > 0) gap else 0f }
        val boxH = maxOf(textH + 2 * pad, mapS)
        val boxBottom = h - m
        val boxTop = boxBottom - boxH
        val rad = u * 0.02f

        // dark rounded box
        c.drawRoundRect(
            RectF(boxLeft, boxTop, boxRight, boxBottom), rad, rad,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
        )

        // map thumbnail with pin
        if (map != null) {
            val top = boxTop + (boxH - mapS) / 2f
            val r = RectF(m, top, m + mapS, top + mapS)
            c.save()
            c.clipPath(Path().apply { addRoundRect(r, rad, rad, Path.Direction.CW) })
            c.drawBitmap(map, null, r, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            drawPin(c, r.centerX(), r.centerY(), mapS * 0.06f)
            c.drawText("Esri", r.left + mapS * 0.04f, r.bottom - mapS * 0.04f, tp(mapS * 0.075f))
            c.restore()
        }

        // text block
        var y = boxTop + (boxH - textH) / 2f
        for (l in lines) {
            c.save(); c.translate(boxLeft + pad, y); l.draw(c); c.restore()
            y += l.height + gap
        }

        drawChip(c, w, u, m)
    }

    private fun drawPin(c: Canvas, cx: Float, cy: Float, r: Float) {
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 57, 53) }
        val tri = Path().apply {
            moveTo(cx - r * 0.85f, cy - r * 1.7f)
            lineTo(cx + r * 0.85f, cy - r * 1.7f)
            lineTo(cx, cy)
            close()
        }
        c.drawPath(tri, red)
        c.drawCircle(cx, cy - r * 2.3f, r * 1.2f, red)
        c.drawCircle(cx, cy - r * 2.3f, r * 0.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }

    /** App-name chip at the top-right, like "GPS Map Camera" in the sample. */
    private fun drawChip(c: Canvas, w: Int, u: Float, m: Float) {
        val text = tp(u * 0.03f)
        val iconS = u * 0.05f
        val padC = u * 0.012f
        val chipH = iconS + 2 * padC
        val chipW = padC + iconS + padC + text.measureText(APP_LABEL) + padC * 1.5f
        val chip = RectF(w - m - chipW, m, w - m, m + chipH)
        c.drawRoundRect(
            chip, chipH / 2.5f, chipH / 2.5f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 0, 0, 0) }
        )
        val icon = RectF(chip.left + padC, chip.top + padC, chip.left + padC + iconS, chip.top + padC + iconS)
        c.drawRoundRect(icon, iconS * 0.25f, iconS * 0.25f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(41, 98, 255) })
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = iconS * 0.08f
        }
        c.drawCircle(icon.centerX(), icon.centerY(), iconS * 0.28f, white)
        c.drawCircle(icon.centerX(), icon.centerY(), iconS * 0.09f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val baseline = chip.centerY() - (text.ascent() + text.descent()) / 2f
        c.drawText(APP_LABEL, icon.right + padC, baseline, text)
    }
}
