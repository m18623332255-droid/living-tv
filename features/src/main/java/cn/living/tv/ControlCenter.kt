package cn.living.tv

import android.app.Activity
import android.app.Dialog
import android.content.*
import android.graphics.*
import android.graphics.drawable.*
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.*
import org.json.JSONArray

/** TV control centre. Network/device changes stay in the television's original settings. */
class ControlCenter(private val activity:Activity,private val root:FrameLayout,private val onClosed:()->Unit={}){
    private val store=Store(activity)
    private val audio=activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler=Handler(Looper.getMainLooper())
    private var dialog:Dialog?=null
    private val shade=View(activity).apply{setBackgroundColor(Color.BLACK);isClickable=false;isFocusable=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
    private fun dp(n:Int)=(n*activity.resources.displayMetrics.density+.5f).toInt()
    init{root.addView(shade,FrameLayout.LayoutParams(-1,-1));brightness(store.string("desktop_brightness","100").toIntOrNull()?:100)}
    private fun brightness(value:Int){shade.alpha=(100-value.coerceIn(35,100))*.008f}
    fun isShowing()=dialog?.isShowing==true
    fun close(){dialog?.dismiss()}
    fun destroy(){dialog?.dismiss();handler.removeCallbacksAndMessages(null)}
    private fun launch(intent:Intent){dialog?.dismiss();try{activity.startActivity(intent)}catch(_:Exception){Toast.makeText(activity,"本机未提供此入口，请进入电视原厂设置",Toast.LENGTH_LONG).show()}}
    private fun native(action: String) {
        launch(Intent(activity, DeviceSettingsActivity::class.java).putExtra("settings_action", action))
    }
    private fun own(cls:String)=launch(Intent().setComponent(ComponentName(activity.packageName,cls)))
    private fun text(value:String,size:Float=16f,color:Int=Color.WHITE)=TextView(activity).apply{this.text=value;textSize=size;setTextColor(color);includeFontPadding=false;isSingleLine=true;ellipsize=android.text.TextUtils.TruncateAt.END}
    private fun glass(radius:Int=24):Drawable=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0x39FFFFFF,0x19D8E5FF,0x24AAB9D0)).apply{cornerRadius=dp(radius).toFloat();setStroke(dp(1),0x48FFFFFF)}
    private fun focusBg(radius:Int=24)=StateListDrawable().apply{
        addState(intArrayOf(android.R.attr.state_focused),GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0x65FFFFFF,0x36DDEBFF)).apply{cornerRadius=dp(radius).toFloat();setStroke(dp(2),0xDCFFFFFF.toInt())})
        addState(intArrayOf(android.R.attr.state_pressed),GradientDrawable().apply{setColor(0x55FFFFFF);cornerRadius=dp(radius).toFloat()})
        addState(intArrayOf(),glass(radius))
    }
    private fun focus(view:View,label:String,run:()->Unit){
        view.id=View.generateViewId();view.contentDescription=label;view.isFocusable=true;view.isFocusableInTouchMode=true;view.isClickable=true;view.background=focusBg()
        view.setOnClickListener{run()};view.setOnTouchListener{v,e->if(e.action==MotionEvent.ACTION_DOWN)v.requestFocus();false}
        view.setOnFocusChangeListener{v,on->v.animate().scaleX(if(on)1.025f else 1f).scaleY(if(on)1.025f else 1f).setDuration(140).start()}
    }
    private fun icon(kind:String,size:Int=27)=ControlGlyph(activity,kind).apply{layoutParams=LinearLayout.LayoutParams(dp(size),dp(size))}
    private fun tile(label:String,status:String,kind:String,run:()->Unit):LinearLayout{
        val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(6),dp(10),dp(6))};focus(row,label,run)
        val bubble=FrameLayout(activity).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(0x26DDEEFF)}}
        bubble.addView(ControlGlyph(activity,kind),FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER));row.addView(bubble,LinearLayout.LayoutParams(dp(36),dp(36)))
        val words=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,0,0)}
        words.addView(text(label,15f));words.addView(text(status,11f,0xC2FFFFFF.toInt()).apply{tag="control-status";setPadding(0,dp(5),0,0)})
        row.addView(words,LinearLayout.LayoutParams(0,-2,1f));return row
    }
    private fun quick(label:String,kind:String,run:()->Unit):LinearLayout{
        val item=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER};focus(item,label,run)
        item.addView(icon(kind,23));item.addView(text(label,10f).apply{gravity=Gravity.CENTER;setPadding(0,dp(3),0,dp(2))});return item
    }
    private fun network() = DeviceSettingsActivity.networkStatus(activity)
    private fun bluetooth() = DeviceSettingsActivity.bluetoothStatus(activity)
    private fun backdrop():Bitmap?=try{
        val b=Bitmap.createBitmap(maxOf(1,root.width/16),maxOf(1,root.height/16),Bitmap.Config.ARGB_8888)
        val canvas=Canvas(b);canvas.scale(b.width.toFloat()/root.width,b.height.toFloat()/root.height);root.draw(canvas)
        val pixels=IntArray(b.width*b.height);b.getPixels(pixels,0,b.width,0,0,b.width,b.height);val out=IntArray(pixels.size)
        for(y in 0 until b.height)for(x in 0 until b.width){var red=0;var green=0;var blue=0;var count=0
            for(dy in -2..2)for(dx in -2..2){val xx=(x+dx).coerceIn(0,b.width-1);val yy=(y+dy).coerceIn(0,b.height-1);val c=pixels[yy*b.width+xx];red+=Color.red(c);green+=Color.green(c);blue+=Color.blue(c);count++}
            out[y*b.width+x]=Color.rgb(red/count,green/count,blue/count)
        }
        b.setPixels(out,0,b.width,0,0,b.width,b.height)
        val sheetWidth=dp(416).coerceAtMost(root.width-dp(32));val x=(b.width*(1f-sheetWidth.toFloat()/root.width)).toInt().coerceAtLeast(0)
        Bitmap.createBitmap(b,x,0,b.width-x,b.height)
    }catch(_:Exception){null}
    fun show(){
        if(dialog?.isShowing==true)return
        val previous=activity.currentFocus
        val d=Dialog(activity);dialog=d;d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val outer=FrameLayout(activity);outer.setOnClickListener{d.dismiss()}
        val width=dp(416).coerceAtMost(activity.resources.displayMetrics.widthPixels-dp(32))
        val sheet=FrameLayout(activity).apply{background=GradientDrawable().apply{cornerRadius=dp(32).toFloat();setColor(0xE5313B50.toInt());setStroke(dp(1),0x70FFFFFF)};foreground=GradientDrawable().apply{cornerRadius=dp(32).toFloat();setColor(Color.TRANSPARENT);setStroke(dp(1),0x70FFFFFF)};clipToOutline=true;elevation=dp(18).toFloat();isClickable=true}
        val photo=backdrop();if(photo!=null)sheet.addView(ImageView(activity).apply{setImageBitmap(photo);scaleType=ImageView.ScaleType.CENTER_CROP;alpha=.65f;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},FrameLayout.LayoutParams(-1,-1))
        sheet.addView(View(activity).apply{background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0x38658D94,0x7A283249,0xC51C2537.toInt()))},FrameLayout.LayoutParams(-1,-1))
        val scroll=ScrollView(activity).apply{isVerticalScrollBarEnabled=false;isFillViewport=true;clipToPadding=false}
        val column=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(14));clipChildren=false;clipToPadding=false}
        scroll.addView(column,FrameLayout.LayoutParams(-1,-2));sheet.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        outer.addView(sheet,FrameLayout.LayoutParams(width,-1,Gravity.END).apply{setMargins(dp(16),dp(18),dp(24),dp(18))})
        val heading=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL};heading.addView(text("控制中心",22f),LinearLayout.LayoutParams(0,-2,1f))
        val close=text("×",24f).apply{gravity=Gravity.CENTER};focus(close,"关闭控制中心"){d.dismiss()};heading.addView(close,LinearLayout.LayoutParams(dp(32),dp(32)));column.addView(heading,LinearLayout.LayoutParams(-1,dp(34)))
        fun row(height:Int,build:(LinearLayout)->Unit){val r=LinearLayout(activity).apply{clipChildren=false;clipToPadding=false};build(r);column.addView(r,LinearLayout.LayoutParams(-1,dp(height)).apply{topMargin=dp(9)})}
        fun halves(row:LinearLayout,left:View,right:View){row.addView(left,LinearLayout.LayoutParams(0,-1,1f).apply{rightMargin=dp(5)});row.addView(right,LinearLayout.LayoutParams(0,-1,1f).apply{leftMargin=dp(5)})}
        val wifi=tile("无线网络",network(),"wifi"){native(Settings.ACTION_WIFI_SETTINGS)}
        val bt=tile("蓝牙",bluetooth(),"bluetooth"){native(Settings.ACTION_BLUETOOTH_SETTINGS)}
        val queue=try{JSONArray(store.string("queue","[]"))}catch(_:Exception){JSONArray()}
        val lastIndex=(store.string("last_index","0").toIntOrNull()?:0).coerceIn(0,maxOf(0,queue.length()-1))
        fun play(delta:Int){if(queue.length()==0){own("cn.living.tv.PlayerPortalActivity");return};val index=(lastIndex+delta+queue.length())%queue.length();launch(Intent(activity,PlayerActivity::class.java).putExtra("index",index).putExtra("live",store.string("queue_live") == "true"))}
        val media=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(9));background=glass(24)}
        media.addView(text("客厅播放器",11f,0xACFFFFFF.toInt()))
        media.addView(text(if(queue.length()>0)"继续播放"else"未在播放",18f).apply{setPadding(0,dp(9),0,0)})
        media.addView(text(if(queue.length()>0)queue.optJSONObject(lastIndex)?.optString("name").orEmpty()else"添加你的直播与影视源",10f,0xBFFFFFFF.toInt()).apply{setPadding(0,dp(5),0,0)})
        val transport=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL};var playButton:View?=null
        listOf("previous","play","next").forEachIndexed{index,kind->val button=FrameLayout(activity);val label=listOf("上一项","播放","下一项")[index];focus(button,label){play(index-1)}
            button.addView(ControlGlyph(activity,kind),FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));transport.addView(button,LinearLayout.LayoutParams(0,dp(32),1f).apply{if(index==1){leftMargin=dp(5);rightMargin=dp(5)}})
            if(index==1)playButton=button else if(queue.length()==0){button.isEnabled=false;button.isFocusable=false;button.alpha=.35f}}
        media.addView(transport,LinearLayout.LayoutParams(-1,dp(32)).apply{topMargin=dp(10)})
        row(126){r->val left=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;clipChildren=false};left.addView(wifi,LinearLayout.LayoutParams(-1,0,1f));left.addView(bt,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(8)});halves(r,left,media)}
        wifi.nextFocusDownId=bt.id;wifi.nextFocusRightId=playButton!!.id;bt.nextFocusUpId=wifi.id;bt.nextFocusRightId=playButton!!.id
        val castAvailable = activity.packageManager.resolveActivity(Intent(Settings.ACTION_CAST_SETTINGS), 0) != null
        row(52) { bar ->
            val cast = tile("投屏", if (castAvailable) "系统投屏入口" else "查看设备支持", "cast") {
                native(Settings.ACTION_CAST_SETTINGS)
            }
            val right = LinearLayout(activity).apply { clipChildren = false }
            halves(right, quick("画面", "display") { native(Settings.ACTION_DISPLAY_SETTINGS) },
                quick("原厂设置", "settings") { own("com.gathin.tvhome.FactorySettingsActivity") })
            halves(bar, cast, right)
        }
        row(48) { bar ->
            val left = LinearLayout(activity).apply { clipChildren = false }
            val right = LinearLayout(activity).apply { clipChildren = false }
            halves(left, quick("壁纸", "wallpaper") { own("cn.living.tv.WallpaperPortalActivity") },
                quick("桌面设置", "grid") { own("com.gathin.tvhome.LauncherSettingsActivity") })
            halves(right, quick("应用管理", "apps") { native(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS) },
                quick("更多设置", "settings") { own("cn.living.tv.FeaturesSettingsActivity") })
            halves(bar, left, right)
        }
        fun slider(title:String,kind:String,min:Int,max:Int,value:Int,enabled:Boolean=true,change:(Int)->Int):SeekBar{
            val card=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(13),dp(8),dp(13),dp(5));background=glass(24)}
            val titleRow=LinearLayout(activity);titleRow.addView(text(title,13f),LinearLayout.LayoutParams(0,-2,1f));fun label(v:Int)=if(title=="桌面亮度")"$v%"else"$v/$max"
            val amount=text(if(enabled)label(value)else"外接设备控制",11f,0xC7FFFFFF.toInt());amount.tag="control-slider-value";titleRow.addView(amount);card.addView(titleRow)
            val bar=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL};bar.addView(icon(kind,20));val seek=SeekBar(activity).apply{this.max=max-min;progress=(value-min).coerceIn(0,max-min);keyProgressIncrement=if(max>30)5 else 1;isEnabled=enabled;isFocusable=true;contentDescription=title;progressTintList=android.content.res.ColorStateList.valueOf(Color.WHITE);thumbTintList=android.content.res.ColorStateList.valueOf(0xFFE6F3FF.toInt());setPadding(dp(8),0,dp(8),0)}
            seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar,p:Int,user:Boolean){if(user){val actual=change(p+min).coerceIn(min,max);amount.text=label(actual);if(actual!=p+min)s.progress=actual-min}};override fun onStartTrackingTouch(s:SeekBar){};override fun onStopTrackingTouch(s:SeekBar){}})
            bar.addView(seek,LinearLayout.LayoutParams(0,dp(29),1f));card.addView(bar);column.addView(card,LinearLayout.LayoutParams(-1,dp(58)).apply{topMargin=dp(9)});return seek
        }
        slider("桌面亮度","sun",35,100,store.string("desktop_brightness","100").toIntOrNull()?:100){brightness(it);store.put("desktop_brightness",it.toString());it}
        val maximum=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volumeSlider=slider("媒体音量","volume",0,maximum,audio.getStreamVolume(AudioManager.STREAM_MUSIC),!audio.isVolumeFixed){try{audio.setStreamVolume(AudioManager.STREAM_MUSIC,it,0)}catch(_:SecurityException){Toast.makeText(activity,"请在电视或音响上调节音量",Toast.LENGTH_SHORT).show()};audio.getStreamVolume(AudioManager.STREAM_MUSIC)}
        column.addView(text("屏幕背光与音效可进入原厂设置调整",10f,0xAFFFFFFF.toInt()).apply{gravity=Gravity.CENTER;setPadding(0,dp(10),0,dp(2))},LinearLayout.LayoutParams(-1,-2))
        d.setContentView(outer);d.window?.apply{setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));setLayout(-1,-1);addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);setDimAmount(.36f);decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY}
        d.setOnDismissListener{handler.removeCallbacksAndMessages(null);dialog=null;onClosed();previous?.requestFocus()};d.show();d.window?.setLayout(-1,-1);wifi.requestFocus()
        handler.postDelayed(object:Runnable{override fun run(){if(!d.isShowing)return
            fun update(tile:View,value:String){tile.findViewWithTag<TextView>("control-status")?.let{if(it.text.toString()!=value)it.text=value}}
            update(wifi,network());update(bt,bluetooth())
            if(volumeSlider.isEnabled&&!volumeSlider.isPressed){val current=audio.getStreamVolume(AudioManager.STREAM_MUSIC);if(volumeSlider.progress!=current){volumeSlider.progress=current;(volumeSlider.parent.parent as View).findViewWithTag<TextView>("control-slider-value")?.text="$current/$maximum"}}
            handler.postDelayed(this,2000)
        }},2000)
    }
}

/** Original vector symbols; no platform-exclusive Apple service icons. */
internal class ControlGlyph(context:Context,private val kind:String):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas){
        canvas.save();canvas.scale(width/32f,height/32f);val p=paint;p.color=Color.WHITE;p.style=Paint.Style.STROKE;p.strokeWidth=2.2f;p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND
        when(kind){
            "wifi"->{canvas.drawArc(3f,5f,29f,30f,222f,96f,false,p);canvas.drawArc(8f,11f,24f,27f,223f,94f,false,p);p.style=Paint.Style.FILL;canvas.drawCircle(16f,24f,2f,p)}
            "bluetooth"->{val path=Path();path.moveTo(16f,3f);path.lineTo(24f,10f);path.lineTo(8f,23f);path.moveTo(16f,3f);path.lineTo(16f,29f);path.lineTo(24f,22f);path.lineTo(8f,9f);canvas.drawPath(path,p)}
            "play","previous","next"->{p.style=Paint.Style.FILL;val path=Path();if(kind=="previous"){path.moveTo(24f,6f);path.lineTo(9f,16f);path.lineTo(24f,26f);path.close();canvas.drawRect(5f,6f,8f,26f,p)}else{path.moveTo(8f,6f);path.lineTo(24f,16f);path.lineTo(8f,26f);path.close();if(kind=="next")canvas.drawRect(25f,6f,28f,26f,p)};canvas.drawPath(path,p)}
            "cast"->{canvas.drawRoundRect(3f,5f,29f,25f,3f,3f,p);canvas.drawArc(2f,18f,14f,30f,270f,90f,false,p);canvas.drawArc(2f,23f,9f,30f,270f,90f,false,p)}
            "display"->{canvas.drawRoundRect(3f,5f,29f,24f,3f,3f,p);canvas.drawLine(16f,24f,16f,29f,p);canvas.drawLine(9f,29f,23f,29f,p)}
            "grid","apps"->{for(y in 0..1)for(x in 0..1)canvas.drawRoundRect(4f+x*14,4f+y*14,14f+x*14,14f+y*14,2f,2f,p)}
            "wallpaper"->{canvas.drawRoundRect(3f,4f,29f,28f,4f,4f,p);canvas.drawCircle(22f,11f,3f,p);val path=Path();path.moveTo(4f,25f);path.lineTo(12f,14f);path.lineTo(20f,24f);path.lineTo(25f,18f);path.lineTo(29f,23f);canvas.drawPath(path,p)}
            "sun","settings"->{canvas.drawCircle(16f,16f,if(kind=="sun")6f else 8f,p);for(i in 0..7){val a=i*Math.PI/4;canvas.drawLine((16+11*Math.cos(a)).toFloat(),(16+11*Math.sin(a)).toFloat(),(16+14*Math.cos(a)).toFloat(),(16+14*Math.sin(a)).toFloat(),p)};if(kind=="settings")canvas.drawCircle(16f,16f,3f,p)}
            "volume"->{val path=Path();path.moveTo(4f,12f);path.lineTo(10f,12f);path.lineTo(17f,6f);path.lineTo(17f,26f);path.lineTo(10f,20f);path.lineTo(4f,20f);path.close();canvas.drawPath(path,p);canvas.drawArc(15f,7f,29f,25f,-62f,124f,false,p)}
        };canvas.restore()
    }
}
