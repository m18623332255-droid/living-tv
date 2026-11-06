package cn.living.tv

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import java.util.concurrent.Executors

/** Shared wallpaper-backed glass and remote focus for Living TV settings. */
object SettingsAppearance {
    private val focus = intArrayOf(android.R.attr.state_focused)
    private val selected = intArrayOf(android.R.attr.state_selected)
    private val pressed = intArrayOf(android.R.attr.state_pressed)
    private val disabled = intArrayOf(-android.R.attr.state_enabled)
    private val ink = 0xFF16232C.toInt()
    private val worker = Executors.newSingleThreadExecutor()
    private var cached: Pair<String, Bitmap?>? = null // Accessed only by the worker.
    private val ease = DecelerateInterpolator(1.6f)

    fun background() = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xFF346371.toInt(), 0xFF1C303F.toInt(), 0xFF1D1D32.toInt()))

    fun surface(context: Context, color: Int = 0x20E4F2FF, radius: Int = 22) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * context.resources.displayMetrics.density
            setStroke(context.resources.displayMetrics.density.toInt().coerceAtLeast(1), 0x46FFFFFF)
        }

    fun glass(context: Context) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(0x48FFFFFF, 0x1DE1F0FF, 0x24182738)).apply {
        cornerRadius = 28 * context.resources.displayMetrics.density
        setStroke(context.resources.displayMetrics.density.toInt().coerceAtLeast(1), 0x65FFFFFF)
    }

    @JvmStatic fun decorate(view: View) {
        view.background = background()
        val context = view.context.applicationContext
        val address = Store(context).string("wallpaper")
        if (address.isBlank()) return
        worker.execute {
            val snapshot = cached?.takeIf { it.first == address }
                ?: (address to runCatching { wallpaper(context, address)?.let(::blur) }.getOrNull())
                    .also { cached = it }
            val bitmap = snapshot.second ?: return@execute
            view.post {
                if (!view.isAttachedToWindow || Store(context).string("wallpaper") != address) return@post
                view.background = LayerDrawable(arrayOf(
                    BitmapDrawable(view.resources, bitmap).apply { gravity = Gravity.FILL; isFilterBitmap = true },
                    GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        intArrayOf(0x8B0C1A24.toInt(), 0xA40C1525.toInt(), 0xAF101729.toInt()))
                ))
            }
        }
    }

    private fun wallpaper(context: Context, address: String): Bitmap? {
        val uri = Uri.parse(address)
        if (address.startsWith("file:///android_asset/papx/")) {
            return context.assets.open("papx/previews/${uri.lastPathSegment?.substringBeforeLast('.')}.jpg")
                .use(BitmapFactory::decodeStream)
        }
        if (uri.lastPathSegment?.endsWith(".mp4", ignoreCase = true) == true ||
            context.contentResolver.getType(uri)?.startsWith("video/") == true) {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                if (android.os.Build.VERSION.SDK_INT >= 27)
                    retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90)
                else retriever.getFrameAtTime(0)
            } finally { retriever.release() }
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        PapXWallpapers.openImage(context, uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / 384).coerceAtLeast(1)
        options.inJustDecodeBounds = false
        return PapXWallpapers.openImage(context, uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun blur(source: Bitmap): Bitmap {
        val width = 144
        val height = 81
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val cropWidth = minOf(source.width, source.height * width / height).coerceAtLeast(1)
        val cropHeight = minOf(source.height, source.width * height / width).coerceAtLeast(1)
        val left = (source.width - cropWidth) / 2
        val top = (source.height - cropHeight) / 2
        Canvas(bitmap).drawBitmap(source, Rect(left, top, left + cropWidth, top + cropHeight),
            Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        source.recycle()
        val pixels = IntArray(width * height)
        val out = IntArray(pixels.size)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val radius = 5
        val area = (2 * radius + 1) * (2 * radius + 1)
        for (y in 0 until height) for (x in 0 until width) {
            var red = 0
            var green = 0
            var blue = 0
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val color = pixels[(y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)]
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
            }
            out[y * width + x] = Color.rgb(red / area, green / area, blue / area)
        }
        bitmap.setPixels(out, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun motionEnabled(view: View) =
        Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    @JvmStatic fun reveal(view: View, direction: Int) {
        view.animate().cancel()
        if (!motionEnabled(view)) {
            view.alpha = 1f
            view.translationX = 0f
            return
        }
        view.alpha = .2f
        view.translationX = direction * 22 * view.resources.displayMetrics.density
        view.animate().alpha(1f).translationX(0f).setInterpolator(ease).setDuration(260).start()
    }

    @JvmStatic fun row(view: TextView) {
        val context = view.context
        view.stateListAnimator = null
        view.backgroundTintList = null
        view.background = StateListDrawable().apply {
            addState(disabled, surface(context, 0x0CFFFFFF, 14))
            addState(pressed, surface(context, 0xB9CDE9F6.toInt(), 14))
            addState(focus, GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFFAFCFF.toInt(), 0xFFD7E8F4.toInt())).apply {
                cornerRadius = 15 * context.resources.displayMetrics.density
                setStroke(context.resources.displayMetrics.density.toInt().coerceAtLeast(1), Color.WHITE)
            })
            addState(selected, surface(context, 0x55B9DBEC, 14))
            addState(intArrayOf(), surface(context, 0x12E4F2FF, 14))
        }
        view.setTextColor(ColorStateList(arrayOf(disabled, pressed, focus, intArrayOf()),
            intArrayOf(0xFF9EAEBE.toInt(), ink, ink, Color.WHITE)))
        view.isFocusableInTouchMode = true
        view.setOnFocusChangeListener { target, focused ->
            val scale = if (focused) 1.04f else 1f
            target.animate().scaleX(scale).scaleY(scale)
                .translationZ(if (focused) 9 * view.resources.displayMetrics.density else 0f)
                .setInterpolator(ease).setDuration(if (motionEnabled(target)) 180 else 0).start()
        }
        view.setOnTouchListener { target, event ->
            if (event.action == MotionEvent.ACTION_DOWN) target.requestFocus()
            false
        }
    }

    @JvmStatic fun dialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(surface(dialog.context, 0xF02A3D4B.toInt(), 24))
    }
}
