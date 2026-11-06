package cn.living.tv

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class CleanBridge(private val activity:Activity,private val root:FrameLayout,private val block:LinearLayout){
    private val controls=ControlCenter(activity,root){touch()}
    fun showControls(){controls.show()}
    fun closeControls(){controls.close()}
    private val repository=WeatherRepository(activity)
    private val store=Store(activity)
    private val handler=Handler(Looper.getMainLooper())
    private val executor=Executors.newSingleThreadExecutor()
    private val weatherLine=LinearLayout(activity)
    private val glyph=WeatherGlyph(activity)
    private val weatherText=TextView(activity)
    private var resumed=false
    private var busy=false
    private var player:ExoPlayer?=null
    private var wallpaperView:View?=null
    private var wallpaperAddress=""
    private var closed=false
    private var lastInput=android.os.SystemClock.elapsedRealtime()
    private var lastRotation=lastInput
    private var lastWeather=0L
    private val landscape=LandscapeView(activity)
    fun touch(){lastInput=android.os.SystemClock.elapsedRealtime()}
    private val tick=object:Runnable{override fun run(){
        if(resumed){
            val now=android.os.SystemClock.elapsedRealtime()
            if(now-lastWeather>30*60*1000L){lastWeather=now;refresh()}
            val rotate=store.string("rotate","0").toLongOrNull()?:0
            if (rotate > 0 && now - lastRotation > rotate * 60_000) {
                lastRotation = now
                val selected = store.string("wallpaper")
                val list = PapXWallpapers.rotation(activity, selected, store.order("wallpapers"))
                if (list.size > 1) {
                    store.put("wallpaper", list[(list.indexOf(selected) + 1) % list.size])
                    updateWallpaper()
                }
            }
            val idle=store.string("idle","5").toLongOrNull()?:5
            if(idle>0&&!controls.isShowing()&&now-lastInput>idle*60000){lastInput=now;activity.startActivity(Intent().setComponent(ComponentName(activity.packageName,"com.gathin.tvhome.ClockActivity")))}
        }
        handler.postDelayed(this,1000)
    }}
    init{
        root.addView(landscape,0,FrameLayout.LayoutParams(-1,-1))
        weatherLine.orientation=LinearLayout.HORIZONTAL;weatherLine.gravity=Gravity.CENTER_VERTICAL
        weatherLine.setPadding(dp(3),dp(10),dp(8),dp(6));weatherLine.isFocusable=true;weatherLine.isFocusableInTouchMode=true;weatherLine.isClickable=true
        weatherLine.contentDescription="时间与天气";weatherLine.tag="clean-weather"
        weatherLine.addView(glyph,LinearLayout.LayoutParams(dp(27),dp(27)))
        weatherText.textSize=17f;weatherText.setTextColor(Color.WHITE);weatherText.isSingleLine=true;weatherText.ellipsize=android.text.TextUtils.TruncateAt.END;weatherText.setPadding(dp(8),0,0,0)
        weatherLine.addView(weatherText,LinearLayout.LayoutParams(0,-2,1f));block.addView(weatherLine,LinearLayout.LayoutParams(-1,-2))
        weatherLine.setOnClickListener{activity.startActivity(Intent(activity,WeatherPortalActivity::class.java))}
        weatherLine.setOnFocusChangeListener{v,f->v.background=if(f)rounded(0x28FFFFFF,dp(10).toFloat(),0x60FFFFFF)else null}
        weatherLine.setOnTouchListener{v,e->if(e.action==android.view.MotionEvent.ACTION_DOWN)v.requestFocus();false}
        show();handler.post(tick)
    }
    private fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun show(){
        if(!repository.enabled()){weatherLine.visibility=View.GONE;return};weatherLine.visibility=View.VISIBLE
        val s=repository.cached()
        weatherText.text=if(WeatherLogic.usable(s)&&s!=null){glyph.code=s.code;glyph.day=s.day;glyph.invalidate();"${s.city.name}  ${s.degrees}  ${WeatherLogic.description(s.code)}  · ${if(s.city.automatic)"网络城市"else"手动城市"}"}else "正在识别网络城市…"
    }
    private fun refresh(){
        show();if(busy||!repository.enabled())return;busy=true
        executor.execute{try{repository.refresh();handler.post{if(!closed){busy=false;show()}}}catch(_:Exception){handler.post{if(!closed){busy=false;show();if(!WeatherLogic.usable(repository.cached()))weatherText.text="天气暂不可用 · 确认可重试或更正城市"}}}}
    }
    fun resume(){
        resumed=true;touch();landscape.variant=store.string("landscape","0").toIntOrNull()?:0;landscape.invalidate();refresh();updateWallpaper()}
    fun pause(){resumed=false;releaseWallpaper()}
    fun destroy(){controls.destroy();closed=true;handler.removeCallbacksAndMessages(null);executor.shutdownNow();releaseWallpaper()}
    private fun releaseWallpaper(){player?.release();player=null;wallpaperView?.let{root.removeView(it)};wallpaperView=null;wallpaperAddress=""}
    private fun updateWallpaper(){
        val address=store.string("wallpaper");if(address.isBlank()||address==wallpaperAddress)return
        releaseWallpaper();wallpaperAddress=address
        try{
            val uri=Uri.parse(address)
            if((activity.contentResolver.getType(uri)?:android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(android.webkit.MimeTypeMap.getFileExtensionFromUrl(address))).orEmpty().startsWith("video/")){
                val p=ExoPlayer.Builder(activity).build();player=p
                val view=PlayerView(activity).apply{setShutterBackgroundColor(Color.TRANSPARENT);useController=false;this.player=p;resizeMode=androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM;isFocusable=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
                view.tag="clean-wallpaper";wallpaperView=view;root.addView(view,1,FrameLayout.LayoutParams(-1,-1));p.volume=0f;p.repeatMode=Player.REPEAT_MODE_ONE
                p.addListener(object:Player.Listener{override fun onPlayerError(error:PlaybackException){releaseWallpaper()}})
                p.setMediaItem(MediaItem.fromUri(uri));p.prepare();p.playWhenReady=resumed
            }else{
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};PapXWallpapers.openImage(activity, uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
                require(bounds.outWidth>0);val opts=BitmapFactory.Options().apply{inSampleSize=1;while(bounds.outWidth/inSampleSize>2560||bounds.outHeight/inSampleSize>1440)inSampleSize*=2}
                val bitmap=PapXWallpapers.openImage(activity, uri)?.use{BitmapFactory.decodeStream(it,null,opts)} ?: error("壁纸不可用")
                val view=ImageView(activity).apply{setImageBitmap(bitmap);scaleType=ImageView.ScaleType.CENTER_CROP;alpha=.72f;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
                view.tag="clean-wallpaper";wallpaperView=view;root.addView(view,1,FrameLayout.LayoutParams(-1,-1))
            }
        }catch(_:Exception){releaseWallpaper()}
    }
    companion object {
        @JvmStatic fun attach(activity:Activity,root:FrameLayout,block:LinearLayout)=CleanBridge(activity,root,block)
        @JvmStatic fun entryKey(component:ComponentName):String=when(component.className){PlayerPortalActivity::class.java.name->"livingtv.player";WeatherPortalActivity::class.java.name->"livingtv.weather";else->component.packageName}
        @JvmStatic fun banner(context:Context,kind:String):Drawable{
            val view=HomeIconView(context,kind);view.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY));view.layout(0,0,400,240)
            val b=Bitmap.createBitmap(400,240,Bitmap.Config.ARGB_8888);view.draw(Canvas(b));return BitmapDrawable(context.resources,b)
        }
    }
}
