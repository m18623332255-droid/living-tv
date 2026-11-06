package cn.living.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.LruCache
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

internal class CinemaPosters(private val context: Context) : AutoCloseable {
    private val decoder = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val local = runCatching {
        context.assets.open("posters/index.json").bufferedReader().use { JSONObject(it.readText()) }
    }.getOrDefault(JSONObject())

    fun load(entry: MediaEntry, view: ImageView) {
        val asset = local.optString(entry.name)
        val address = asset.ifBlank { entry.poster }
        if (address.isBlank()) return
        view.tag = address
        cache.get(address)?.let { view.setImageBitmap(it); return }
        decoder.execute {
            val bitmap = runCatching {
                if (asset.isNotBlank()) context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
                else decodeRemote(address)
            }.getOrNull() ?: return@execute
            cache.put(address, bitmap)
            view.post { if (view.isAttachedToWindow && view.tag == address) view.setImageBitmap(bitmap) }
        }
    }

    private fun decodeRemote(address: String): Bitmap? {
        var url = CatalogParser.networkUrl(address)
        repeat(4) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 4000
                connection.readTimeout = 4000
                connection.instanceFollowRedirects = false
                if (connection.responseCode in listOf(301, 302, 303, 307, 308)) {
                    url = CatalogParser.networkUrl(java.net.URI(url).resolve(connection.getHeaderField("Location") ?: return null).toString())
                } else {
                    if (connection.responseCode != 200) return null
                    val bytes = connection.inputStream.use { it.readBytesLimited(3 * 1024 * 1024) }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val dimension = maxOf(bounds.outWidth, bounds.outHeight)
                    if (dimension !in 1..12_000) return null
                    bounds.inSampleSize = Integer.highestOneBit((dimension / 480).coerceAtLeast(1))
                    bounds.inJustDecodeBounds = false
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                }
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    override fun close() { decoder.shutdownNow() }
}

internal fun cinemaBackdrop() = GradientDrawable(GradientDrawable.Orientation.TL_BR,
    intArrayOf(0xFF152B4B.toInt(), 0xFF101A2D.toInt(), 0xFF080C16.toInt()))

internal fun posterFocus(view: View, focused: Boolean, radius: Int = 15) {
    val density = view.resources.displayMetrics.density
    val scale = if (focused) 1.055f else 1f
    view.foreground = GradientDrawable().apply {
        cornerRadius = radius * density
        setColor(Color.TRANSPARENT)
        setStroke((density * if (focused) 2 else 1).toInt().coerceAtLeast(1),
            if (focused) 0xFFF3F8FF.toInt() else 0x39D5E9FF)
    }
    val reducedMotion = Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    view.animate().scaleX(scale).scaleY(scale).translationZ(if (focused) 10 * density else 0f)
        .setInterpolator(DecelerateInterpolator(1.5f)).setDuration(if (reducedMotion) 0 else 175).start()
}

internal class CinemaGlyph(context: Context, private val icon: String) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE6F1FF.toInt()
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        val scale = minOf(width, height) / 24f
        canvas.save()
        canvas.translate((width - 24 * scale) / 2, (height - 24 * scale) / 2)
        canvas.scale(scale, scale)
        when (icon) {
            "搜索" -> {
                canvas.drawCircle(10.3f, 10.3f, 5.4f, paint)
                canvas.drawLine(14.2f, 14.2f, 20f, 20f, paint)
            }
            "扫码搜索" -> {
                for ((x, y) in listOf(4f to 4f, 14f to 4f, 4f to 14f)) {
                    canvas.drawRoundRect(x, y, x + 6f, y + 6f, 1f, 1f, paint)
                }
                canvas.drawLine(14f, 14f, 17f, 14f, paint)
                canvas.drawLine(14f, 14f, 14f, 19f, paint)
                canvas.drawLine(17f, 20f, 20f, 20f, paint)
                canvas.drawLine(20f, 17f, 20f, 20f, paint)
            }
            "历史" -> {
                canvas.drawArc(4f, 4f, 20f, 20f, -136f, 318f, false, paint)
                canvas.drawLine(4f, 8f, 4f, 3f, paint)
                canvas.drawLine(4f, 8f, 9f, 8f, paint)
                canvas.drawLine(12f, 7f, 12f, 12f, paint)
                canvas.drawLine(12f, 12f, 16f, 13.5f, paint)
            }
            "收藏" -> {
                val path = android.graphics.Path().apply {
                    moveTo(12f, 20f); cubicTo(10f, 18f, 3f, 13f, 3f, 8.8f)
                    cubicTo(3f, 4f, 9f, 3f, 12f, 7.4f)
                    cubicTo(15f, 3f, 21f, 4f, 21f, 8.8f)
                    cubicTo(21f, 13f, 14f, 18f, 12f, 20f)
                }
                canvas.drawPath(path, paint)
            }
            "直播" -> {
                canvas.drawRoundRect(3f, 5f, 21f, 17f, 2.5f, 2.5f, paint)
                canvas.drawLine(8f, 21f, 16f, 21f, paint)
                val path = android.graphics.Path().apply { moveTo(10f, 8f); lineTo(15f, 11f); lineTo(10f, 14f); close() }
                canvas.drawPath(path, paint)
            }
            else -> {
                for (y in listOf(6f, 12f, 18f)) canvas.drawLine(4f, y, 20f, y, paint)
                canvas.drawCircle(8f, 6f, 2f, paint)
                canvas.drawCircle(15f, 12f, 2f, paint)
                canvas.drawCircle(11f, 18f, 2f, paint)
            }
        }
        canvas.restore()
    }
}
