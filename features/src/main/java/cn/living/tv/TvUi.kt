package cn.living.tv

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.*

fun Activity.dp(n: Int) = (n * resources.displayMetrics.density).toInt()
fun rounded(color: Int, radius: Float = 18f, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (stroke != null) setStroke(2, stroke)
}
fun Activity.text(value: String, size: Float = 18f, color: Int = Color.WHITE) = TextView(this).apply {
    text = value; textSize = size; setTextColor(color); includeFontPadding = false
}
fun Activity.focusable(view: View, fill: Int = Color.rgb(32, 48, 69)) {
    view.isFocusable = true; view.isFocusableInTouchMode=true; view.isClickable = true
    view.setOnTouchListener{v,event->if(event.action==android.view.MotionEvent.ACTION_DOWN)v.requestFocus();false}
    view.background = rounded(fill, dp(14).toFloat())
    view.setOnFocusChangeListener { v, focused ->
        val reduced = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        v.animate().scaleX(if (focused) 1.018f else 1f).scaleY(if (focused) 1.018f else 1f).setDuration(if (reduced) 0 else 170).start()
        v.elevation = dp(if (focused) 16 else 1).toFloat()
        v.background = rounded(if (focused) Color.rgb(222, 235, 249) else fill, dp(14).toFloat(), if (focused) Color.WHITE else null)
        if (v is TextView) v.setTextColor(if (focused) Color.rgb(15, 29, 48) else Color.WHITE)
        if (v is ViewGroup) for (i in 0 until v.childCount) { val c = v.getChildAt(i); if (c is TextView) c.setTextColor(if (focused) Color.rgb(15,29,48) else Color.WHITE); if(c is TileIcon)c.tint=if(focused)Color.rgb(15,29,48)else Color.WHITE }
    }
}
fun Activity.button(label: String, action: () -> Unit): Button = Button(this).apply {
    text = label; textSize = 16f; isAllCaps = false; setTextColor(Color.WHITE)
    minHeight = dp(48); setPadding(dp(18), dp(8), dp(18), dp(8))
    focusable(this)
    setOnClickListener { action() }
}

/** Original code-drawn landscape, independent of system live wallpaper services. */
class LandscapeView(context: android.content.Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var variant = 0
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val colors=if(variant%2==0)intArrayOf(0xFF0B1328.toInt(),0xFF172849.toInt(),0xFF070D1C.toInt())else intArrayOf(0xFF1F102C.toInt(),0xFF49324B.toInt(),0xFF101527.toInt())
        p.shader=LinearGradient(0f,0f,w,h,colors,null,Shader.TileMode.CLAMP);c.drawRect(0f,0f,w,h,p)
        p.shader=RadialGradient(w*.82f,h*.10f,w*.62f,intArrayOf(0x55387CA9,Color.TRANSPARENT),null,Shader.TileMode.CLAMP);c.drawRect(0f,0f,w,h,p)
        p.shader=RadialGradient(w*.18f,h*.93f,w*.72f,intArrayOf(0x453F286F,Color.TRANSPARENT),null,Shader.TileMode.CLAMP);c.drawRect(0f,0f,w,h,p);p.shader=null
    }
}

class TileIcon(context: android.content.Context, private val kind: String) : View(context) {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    var tint: Int = Color.WHITE
        set(value) { field=value; invalidate() }
    override fun onDraw(c: Canvas) {
        val s = minOf(width,height)/100f; c.save(); c.translate((width-100*s)/2,(height-100*s)/2); c.scale(s,s)
        p.color=tint; p.strokeWidth=4f; p.style=Paint.Style.STROKE; p.strokeCap=Paint.Cap.ROUND
        when(kind) {
            "play" -> { c.drawRoundRect(15f,23f,85f,73f,8f,8f,p); p.style=Paint.Style.FILL; val path=Path(); path.moveTo(43f,36f);path.lineTo(65f,48f);path.lineTo(43f,61f);path.close();c.drawPath(path,p) }
            "wall" -> { c.drawRoundRect(17f,20f,83f,78f,7f,7f,p); c.drawCircle(65f,37f,6f,p); val q=Path();q.moveTo(20f,69f);q.lineTo(40f,45f);q.lineTo(58f,63f);q.lineTo(69f,53f);q.lineTo(81f,68f);c.drawPath(q,p) }
            "folder" -> { val q=Path();q.moveTo(16f,33f);q.lineTo(16f,75f);q.lineTo(84f,75f);q.lineTo(84f,30f);q.lineTo(50f,30f);q.lineTo(43f,23f);q.lineTo(16f,23f);q.close();c.drawPath(q,p) }
            "shield" -> { val q=Path();q.moveTo(50f,15f);q.lineTo(80f,28f);q.lineTo(76f,58f);q.quadTo(66f,80f,50f,87f);q.quadTo(25f,72f,22f,54f);q.lineTo(20f,28f);q.close();c.drawPath(q,p);c.drawLine(37f,48f,47f,59f,p);c.drawLine(47f,59f,65f,39f,p) }
            else -> { c.drawCircle(50f,50f,19f,p); c.drawCircle(50f,50f,7f,p); for(i in 0..7){val a=i*Math.PI/4;c.drawLine((50+27*Math.cos(a)).toFloat(),(50+27*Math.sin(a)).toFloat(),(50+36*Math.cos(a)).toFloat(),(50+36*Math.sin(a)).toFloat(),p)} }
        }
        c.restore()
    }
}
