package cn.living.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** A consistent entry page for controls provided by the television firmware. */
class DeviceSettingsActivity : Activity() {
    private data class Page(val name: String, val icon: String, val detail: String, val button: String)

    private lateinit var action: String
    private lateinit var status: TextView

    override fun attachBaseContext(base: Context) = super.attachBaseContext(chineseContext(base))

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        action = intent.getStringExtra("settings_action").orEmpty()
        val page = pages[action] ?: run { finish(); return }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val frame = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = SettingsAppearance.background()
            setPadding(dp(52), dp(32), dp(52), dp(32))
        }
        val introduction = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(28), 0)
        }
        val symbol = FrameLayout(this).apply { background = SettingsAppearance.surface(this@DeviceSettingsActivity) }
        symbol.addView(ControlGlyph(this, page.icon), FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        introduction.addView(symbol, LinearLayout.LayoutParams(dp(76), dp(76)))
        introduction.addView(text(page.name, 36f).apply { setPadding(0, dp(16), 0, dp(8)) })
        introduction.addView(text("控制中心", 15f, 0xFFB8C8DA.toInt()))
        frame.addView(introduction, LinearLayout.LayoutParams(0, -2, .8f))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SettingsAppearance.surface(this@DeviceSettingsActivity)
            setPadding(dp(26), dp(23), dp(26), dp(24))
        }
        status = text("", 21f)
        panel.addView(status)
        panel.addView(text(page.detail, 16f, 0xFFCAD8E8.toInt()).apply {
            setPadding(0, dp(10), 0, dp(21))
        })
        val primary = button(page.button) { openSystem(action) }
        SettingsAppearance.row(primary)
        panel.addView(primary, LinearLayout.LayoutParams(-1, dp(52)))
        frame.addView(panel, LinearLayout.LayoutParams(0, -2, 1.2f))
        setContentView(frame)
        primary.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (!::status.isInitialized) return
        status.text = when (action) {
            Settings.ACTION_WIFI_SETTINGS -> networkStatus(this)
            Settings.ACTION_BLUETOOTH_SETTINGS -> bluetoothStatus(this)
            Settings.ACTION_CAST_SETTINGS -> if (Intent(action).resolveActivity(packageManager) == null) "本机未提供独立入口" else "屏幕投放"
            Settings.ACTION_DISPLAY_SETTINGS -> "电视画面"
            else -> "已安装的应用"
        }
    }

    private fun openSystem(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "本机未提供独立入口，请从原厂设置查看", Toast.LENGTH_LONG).show()
            startActivity(Intent().setClassName(packageName, "com.gathin.tvhome.FactorySettingsActivity"))
        } catch (_: SecurityException) {
            Toast.makeText(this, "电视系统限制了该入口，请从原厂设置查看", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private val pages = mapOf(
            Settings.ACTION_WIFI_SETTINGS to Page("无线网络", "wifi", "选择无线网络、连接或查看网络详情。", "选择网络"),
            Settings.ACTION_BLUETOOTH_SETTINGS to Page("蓝牙", "bluetooth", "配对遥控器、耳机或其他蓝牙配件。", "管理蓝牙配件"),
            Settings.ACTION_CAST_SETTINGS to Page("投屏", "cast", "查看电视系统提供的投屏与连接选项。", "打开投屏设置"),
            Settings.ACTION_DISPLAY_SETTINGS to Page("画面", "display", "调整电视的图像模式、亮度与其他画面参数。", "打开电视画面设置"),
            Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS to Page("应用管理", "apps", "查看应用、存储空间、权限和通知。", "查看已安装的应用")
        )

        internal fun networkStatus(context: Context): String = runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            when {
                caps == null -> "未连接"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线网络已连接"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> "已连接 · 无互联网"
                else -> "已连接"
            }
        }.getOrDefault("查看网络状态")

        internal fun bluetoothStatus(context: Context): String = runCatching {
            when (Settings.Global.getString(context.contentResolver, "bluetooth_on")) {
                "1" -> "已开启"
                "0" -> "未开启"
                else -> "配对与设备"
            }
        }.getOrDefault("配对与设备")
    }
}
