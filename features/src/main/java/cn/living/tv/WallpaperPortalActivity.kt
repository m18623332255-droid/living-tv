package cn.living.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class WallpaperPortalActivity : Activity() {
    private data class Wallpaper(val name: String, val address: String, val source: String, val variant: Int = -1) {
        val video: Boolean get() = address.endsWith(".mp4", true) || source == "本地视频"
    }

    private lateinit var store: Store
    private lateinit var backdrop: ImageView
    private lateinit var preview: ImageView
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var count: TextView
    private lateinit var strip: LinearLayout
    private lateinit var scroll: HorizontalScrollView
    private lateinit var uploadButton: View
    private lateinit var applyButton: View
    private lateinit var optionsButton: View
    private var items = emptyList<Wallpaper>()
    private var imported = emptyList<String>()
    private var tiles = emptyList<FrameLayout>()
    private var selected = 0
    private var uploadDialog: AlertDialog? = null
    private var uploadServer: LanInputServer? = null
    private val decoder = Executors.newFixedThreadPool(2)
    private val thumbnails = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(chineseContext(base))

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = Store(this)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        buildGallery()
        refresh(store.string("wallpaper"))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val current = items.getOrNull(selected)?.address.orEmpty()
        buildGallery()
        refresh(current)
    }

    override fun onResume() {
        super.onResume()
        if (store.order("wallpapers") != imported) refresh(items.getOrNull(selected)?.address.orEmpty())
    }

    override fun onPause() {
        uploadDialog?.dismiss()
        super.onPause()
    }

    override fun onDestroy() {
        uploadServer?.close()
        decoder.shutdownNow()
        super.onDestroy()
    }

    private fun px(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun label(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
    }

    private fun panel(color: Int, corner: Int, border: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = px(corner).toFloat()
        setStroke(px(1), border)
    }

    private fun button(name: String, primary: Boolean = false, action: () -> Unit) = label(name, 14f).apply {
        id = View.generateViewId()
        gravity = Gravity.CENTER
        setPadding(px(16), 0, px(16), 0)
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        fun color(focused: Boolean) {
            val bright = focused || primary
            setTextColor(if (bright) 0xFF101723.toInt() else Color.WHITE)
            background = panel(if (bright) 0xFFF0F5FC.toInt() else 0x24FFFFFF, 23, 0x48FFFFFF).apply {
                if (focused) setStroke(px(2), 0xFF91BDEB.toInt())
            }
            scaleX = if (focused) 1.04f else 1f
            scaleY = if (focused) 1.04f else 1f
        }
        color(false)
        setOnFocusChangeListener { _, focused -> color(focused) }
        setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) view.requestFocus()
            false
        }
        setOnClickListener { action() }
    }

    private fun buildGallery() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF101520.toInt()) }
        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = .58f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (Build.VERSION.SDK_INT >= 31) {
                setRenderEffect(RenderEffect.createBlurEffect(px(30).toFloat(), px(30).toFloat(), Shader.TileMode.CLAMP))
            }
        }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x80101621.toInt(), 0xC0101520.toInt(), 0xF00B1019.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(46), px(24), px(46), px(22))
        }
        root.addView(body, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("壁纸图库", 30f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        uploadButton = button("扫码上传") { showUpload() }
        header.addView(uploadButton, LinearLayout.LayoutParams(-2, px(43)))
        header.addView(button("从本地导入") { pick() }, LinearLayout.LayoutParams(-2, px(43)).apply {
            leftMargin = px(10)
        })
        body.addView(header, LinearLayout.LayoutParams(-1, px(52)))

        val hero = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = panel(0x30171F30, 22, 0x40FFFFFF)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        hero.addView(preview, LinearLayout.LayoutParams(0, -1, 1.65f))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(28), 0, 0, 0)
        }
        detail = label("", 13f, 0xFFD1DDEC.toInt())
        info.addView(detail)
        title = label("", 27f).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, px(9), 0, px(16))
        }
        info.addView(title)
        applyButton = button("设为壁纸", true) { applySelected() }
        info.addView(applyButton, LinearLayout.LayoutParams(-2, px(43)))
        optionsButton = button("轮换与屏保") { startActivity(Intent(this, WallpaperOptionsActivity::class.java)) }
        info.addView(optionsButton, LinearLayout.LayoutParams(-2, px(42)).apply { topMargin = px(9) })
        uploadButton.nextFocusDownId = applyButton.id
        applyButton.nextFocusUpId = uploadButton.id
        applyButton.nextFocusDownId = optionsButton.id
        optionsButton.nextFocusUpId = applyButton.id
        hero.addView(info, LinearLayout.LayoutParams(0, -1, 1f))
        body.addView(hero, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(14); bottomMargin = px(17) })

        val subhead = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        subhead.addView(label("全部壁纸", 16f), LinearLayout.LayoutParams(0, -2, 1f))
        count = label("", 12f, 0xFFB5C2D4.toInt())
        subhead.addView(count)
        body.addView(subhead, LinearLayout.LayoutParams(-1, px(27)))
        scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        strip = LinearLayout(this).apply { setPadding(0, px(6), 0, px(2)) }
        scroll.addView(strip, FrameLayout.LayoutParams(-2, -1))
        body.addView(scroll, LinearLayout.LayoutParams(-1, px(89)))
        body.addView(label("方向键左右浏览  ·  确认应用  ·  返回退出", 11f, 0xFFB5C2D4.toInt()),
            LinearLayout.LayoutParams(-1, px(16)).apply { topMargin = px(5) })
        setContentView(root)
    }

    private fun refresh(preferred: String) {
        imported = store.order("wallpapers")
        val local = imported.mapIndexed { index, address ->
            val savedName = store.string("wallpaper-name:$address")
            val uri = Uri.parse(address)
            val mime = runCatching { contentResolver.getType(uri) }.getOrNull().orEmpty()
            val source = if (mime.startsWith("video/") || address.endsWith(".mp4", true)) "本地视频" else "本地图片"
            Wallpaper(savedName.ifBlank { "本地壁纸 ${index + 1}" }, address, source)
        }
        items = PapXWallpapers.entries(this).map { Wallpaper(it.name, it.address, "Pap X") } +
            listOf(Wallpaper("深蓝流光", "gradient:0", "内置背景", 0), Wallpaper("暮色紫霞", "gradient:1", "内置背景", 1)) + local
        val address = preferred.ifBlank { "gradient:${store.string("landscape", "0")}" }
        selected = items.indexOfFirst { it.address == address }.coerceAtLeast(0)
        strip.removeAllViews()
        val width = ((resources.displayMetrics.widthPixels / resources.displayMetrics.density - 92 - 7 * 8) / 8)
            .toInt().coerceIn(74, 124)
        tiles = items.mapIndexed { index, item -> makeTile(index, item, width) }
        renderSelection(selected)
        strip.post { tiles.getOrNull(selected)?.requestFocus() }
    }

    private fun makeTile(index: Int, item: Wallpaper, width: Int): FrameLayout {
        val card = FrameLayout(this).apply {
            id = View.generateViewId()
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            contentDescription = item.name
            nextFocusUpId = uploadButton.id
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = panel(0xFF253246.toInt(), 10)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        card.addView(image, FrameLayout.LayoutParams(-1, -1).apply { setMargins(px(3), px(3), px(3), px(3)) })
        card.setOnFocusChangeListener { _, focused -> if (focused) renderSelection(index) }
        card.setOnTouchListener { v, event -> if (event.action == MotionEvent.ACTION_DOWN) v.requestFocus(); false }
        card.setOnClickListener { selected = index; applySelected() }
        strip.addView(card, LinearLayout.LayoutParams(px(width), -1).apply { rightMargin = px(8) })
        load(item) { image.setImageBitmap(it) }
        return card
    }

    private fun renderSelection(index: Int) {
        val item = items.getOrNull(index) ?: return
        selected = index
        count.text = "${index + 1} / ${items.size}"
        title.text = item.name
        detail.text = "${item.source}  ·  ${if (item.video) "动态壁纸" else "静态壁纸"}"
        preview.setImageDrawable(null)
        for ((position, tile) in tiles.withIndex()) {
            tile.background = if (position == index) panel(0x29FFFFFF, 12, Color.WHITE)
                else panel(Color.TRANSPARENT, 12)
        }
        tiles.getOrNull(index)?.let { tile ->
            optionsButton.nextFocusDownId = tile.id
            scroll.smoothScrollTo((tile.left - (scroll.width - tile.width) / 2).coerceAtLeast(0), 0)
        }
        load(item) { image ->
            if (items.getOrNull(selected)?.address == item.address) {
                preview.setImageBitmap(image)
                backdrop.setImageBitmap(image)
            }
        }
    }

    private fun load(item: Wallpaper, show: (Bitmap?) -> Unit) {
        thumbnails.get(item.address)?.let { show(it); return }
        if (item.variant >= 0) {
            val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            LandscapeView(this).apply { variant = item.variant; layout(0, 0, 640, 360); draw(Canvas(bitmap)) }
            thumbnails.put(item.address, bitmap)
            show(bitmap)
            return
        }
        decoder.execute {
            val bitmap = runCatching { readPreview(item) }.getOrNull()
            bitmap?.let { thumbnails.put(item.address, it) }
            runOnUiThread { if (!isDestroyed) show(bitmap) }
        }
    }

    private fun readPreview(item: Wallpaper): Bitmap? {
        val uri = Uri.parse(item.address)
        if (item.address.startsWith("file:///android_asset/papx/")) {
            val preview = "papx/previews/${uri.lastPathSegment?.substringBeforeLast('.')}.jpg"
            return assets.open(preview).use { BitmapFactory.decodeStream(it) }
        }
        if (item.video) {
            val media = MediaMetadataRetriever()
            return try {
                media.setDataSource(this, uri)
                if (Build.VERSION.SDK_INT >= 27) {
                    media.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 360)
                } else {
                    media.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            } finally { media.release() }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > 1280 || bounds.outHeight / inSampleSize > 720) inSampleSize *= 2
        }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun applySelected() {
        val item = items.getOrNull(selected) ?: return
        if (item.variant >= 0) store.put("landscape", item.variant.toString())
        store.put("wallpaper", if (item.variant >= 0) "" else item.address)
        finish()
    }

    private fun pick() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        }
        try { startActivityForResult(intent, 21) }
        catch (_: android.content.ActivityNotFoundException) { Toast.makeText(this, "此电视没有文件选择器", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Activity result entry for the TV document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != 21 || resultCode != RESULT_OK) return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }.orEmpty()
            saveImport(uri, name)
            refresh(uri.toString())
        } catch (_: Exception) { Toast.makeText(this, "无法读取文件，请重新选择", Toast.LENGTH_LONG).show() }
    }

    private fun saveImport(uri: Uri, name: String) {
        val address = uri.toString()
        store.saveOrder("wallpapers", (store.order("wallpapers") + address).distinct())
        if (name.isNotBlank()) store.put("wallpaper-name:$address", name)
    }

    private fun showUpload() {
        if (uploadDialog != null) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(26), px(8), px(26), px(8))
        }
        val qr = ImageView(this).apply { contentDescription = "壁纸上传二维码" }
        box.addView(qr, LinearLayout.LayoutParams(px(212), px(212)))
        val status = label("正在创建上传链接…", 14f).apply { gravity = Gravity.CENTER }
        val address = label("", 11f, 0xFFC6D2E3.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, px(7), 0, 0) }
        box.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        box.addView(address, LinearLayout.LayoutParams(-1, -2))
        val dialog = AlertDialog.Builder(this).setTitle("用手机上传壁纸").setView(box)
            .setPositiveButton("完成", null).create()
        uploadDialog = dialog
        var newest: Uri? = null
        var received = 0
        dialog.setOnDismissListener {
            uploadServer?.close()
            uploadServer = null
            uploadDialog = null
            newest?.let { refresh(it.toString()) }
        }
        dialog.show()
        SettingsAppearance.dialog(dialog)
        uploadServer = LanInputServer(applicationContext, onReady = { url ->
            val image = qrBitmap(url)
            runOnUiThread {
                if (uploadDialog === dialog) {
                    qr.setImageBitmap(image)
                    status.text = "手机和电视连接同一局域网后，用相机扫码"
                    address.text = url
                }
            }
        }, onFile = { uri, name -> runOnUiThread {
            saveImport(uri, name)
            newest = uri
            received++
            if (uploadDialog === dialog) {
                status.text = "已收到 $received 个文件，完成后回图库选择"
            } else if (!isFinishing && !isDestroyed) {
                refresh(uri.toString())
            }
        } }, onError = { error -> runOnUiThread {
            if (uploadDialog === dialog) {
                status.text = error
                qr.setImageDrawable(null)
                address.text = ""
            }
        } }).also { it.start() }
    }
}
