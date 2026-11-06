package cn.living.tv

import android.app.*
import android.content.*
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
open class MainActivity : Activity() {
    protected open val portalKind:String? = null
    private lateinit var store: Store
    private lateinit var weather:WeatherRepository
    private val weatherWorker=Executors.newSingleThreadExecutor()
    private var weatherLoading=false
    private var lastWeatherAttempt=0L
    private var weatherError=""
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private var wallpaperPlayer: ExoPlayer? = null
    private var wallpaperUri = ""
    private var wallpaperFailed = false
    private var page = "home"
    private var playerTab = "点播"
    private val cinemaPosters = lazy { CinemaPosters(applicationContext) }
    private var detailBack: (() -> Unit)? = null
    private var detailFavorite: (() -> Unit)? = null
    private var restorePoster: String? = null
    private var searchDialog: AlertDialog? = null
    private val vodCategories = listOf("电影", "连续剧", "综艺", "动画")
    private var settingsSection = ""
    private lateinit var settingsPanel: LinearLayout
    private lateinit var settingsScroll: ScrollView
    private lateinit var settingsNav: LinearLayout
    private val settingsSections = listOf("通用", "外观与壁纸", "时间与天气", "应用管理", "系统与遥控器", "关于本机")
    private var generation = 0
    private var foreground = false
    private var lastFocus = ""
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var lastInput = 0L
    private var screenSaver: View? = null
    private var activeDialog: AlertDialog? = null
    private var lastRotation = 0L
    private var registered = false
    private val packageReceiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { if (page == "home" && foreground) home() } }
    private val ticker = object : Runnable {
        override fun run() {
            if (foreground) {
                val now = SystemClock.elapsedRealtime()
                val minutes = store.string("idle", "5").toLongOrNull() ?: 5
                if (minutes > 0 && page == "home" && activeDialog == null && now-lastInput > minutes*60000 && screenSaver == null) showSaver()
                val rotations = store.string("rotate", "0").toLongOrNull() ?: 0
                if (rotations > 0 && page == "home" && now-lastRotation > rotations*60000) { rotateWallpaper(); lastRotation=now }
                root.findViewWithTag<TextView>("clock")?.let{v->val value=clockText();if(v.text.toString()!=value)v.text=value}
                root.findViewWithTag<TextView>("home-date")?.let{v->val value=chineseTime("M月d日  EEEE");if(v.text.toString()!=value)v.text=value}
                if(page=="home" && now-lastWeatherAttempt>30*60*1000L)refreshWeather()
            }
            handler.postDelayed(this, 1000)
        }
    }
    override fun attachBaseContext(base:Context){super.attachBaseContext(chineseContext(base))}
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); store=Store(this);weather=WeatherRepository(this)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        openInitialPage(); lastInput=SystemClock.elapsedRealtime(); lastRotation=lastInput; handler.post(ticker)
        val filter=IntentFilter().apply { addAction(Intent.ACTION_PACKAGE_ADDED);addAction(Intent.ACTION_PACKAGE_REMOVED);addDataScheme("package") }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver,filter,Context.RECEIVER_EXPORTED) else registerReceiver(packageReceiver,filter)
        registered=true; incoming(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent);lastInput=SystemClock.elapsedRealtime(); openInitialPage(); incoming(intent) }
    private fun incoming(i: Intent) {
        val url = i.getStringExtra("source_url") ?: return
        i.removeExtra("source_url")
        val name=i.getStringExtra("source_name") ?: "Mac 导入的源"
        val kind=i.getStringExtra("source_kind") ?: "direct"
        if (kind !in listOf("direct","m3u","cms","tvbox")) { message("不支持的源类型");return }
        try { CatalogParser.networkUrl(url) } catch(e:Exception){message(e.message.orEmpty());return}
        val source = Source(UUID.randomUUID().toString(), name, url, kind)
        dialog("导入播放源", "$name\n类型：${sourceKindName(source)}\n$url\n\n是否保存到本机？", "保存") {
            store.saveSources(store.sources() + source)
            if (portalKind == "player") {
                if (source.onDemand) sources() else liveChannels()
            } else manageSources()
        }
    }
    override fun onResume() { super.onResume(); detailFavorite?.invoke(); foreground=true;lastInput=SystemClock.elapsedRealtime();if(page=="home")updateWallpaper(); root.findViewWithTag<View>(lastFocus)?.requestFocus() }
    override fun onConfigurationChanged(newConfig:android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if(::root.isInitialized && page=="home")home()
    }
    override fun onPause() { searchDialog?.dismiss(); if(page=="home")lastFocus=currentFocus?.tag?.toString() ?: lastFocus;foreground=false;stopWallpaper();hideSaver(); super.onPause() }
    override fun onDestroy() { if (cinemaPosters.isInitialized()) cinemaPosters.value.close(); handler.removeCallbacksAndMessages(null);worker.shutdownNow();weatherWorker.shutdownNow();stopWallpaper();if(registered)unregisterReceiver(packageReceiver);super.onDestroy() }
    override fun onUserInteraction() { super.onUserInteraction();lastInput=SystemClock.elapsedRealtime() }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (screenSaver!=null) { if(event.action==KeyEvent.ACTION_DOWN){ hideSaver(); lastInput=SystemClock.elapsedRealtime() };return true }
        return super.dispatchKeyEvent(event)
    }
    private fun openInitialPage(){when(portalKind){"player"->sources();"weather"->settings("时间与天气");"wallpaper_options"->wallpapers();"settings"->settings();else->home()}}
    @Deprecated("TV remote back") override fun onBackPressed() {
        if (page == "media_detail") { detailBack?.invoke(); return }
        if(portalKind!=null){when(page){
            "browse" -> liveChannels()
            "vod","live","search","history","favorites","manage_sources","import" -> sources()
            "components" -> settings()
            else -> finish()
        };return}
        if(page!="home")home()else content.findViewWithTag<View>("builtin:play")?.requestFocus()
    }
    private fun gutter() = if(resources.displayMetrics.widthPixels/resources.displayMetrics.density<700)24 else 40
    private fun columns() = ((resources.displayMetrics.widthPixels/resources.displayMetrics.density-2*gutter())/140).toInt().coerceIn(3,6)
    private fun begin(name: String, title: String, subtitle: String = ""): LinearLayout {
        if(::root.isInitialized && page=="home")lastFocus=currentFocus?.tag?.toString() ?: lastFocus
        generation++; page=name; activeDialog?.dismiss();activeDialog=null;stopWallpaper();screenSaver=null
        searchDialog?.dismiss()
        detailFavorite = null
        detailBack = null
        val cinema = isCinemaPage()
        root=FrameLayout(this);root.setBackgroundColor(Color.rgb(8,18,35));setContentView(root)
        if (isSettingsPage()) SettingsAppearance.decorate(root)
        if (cinema) root.background = cinemaBackdrop()
        if(name=="home") { root.addView(LandscapeView(this), FrameLayout.LayoutParams(-1,-1)) }
        val scroll=ScrollView(this).apply { isFillViewport=true;clipChildren=false;clipToPadding=false }
        content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(gutter()),dp(28),dp(gutter()),dp(36));clipChildren=false;clipToPadding=false}
        scroll.addView(content);root.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        val head=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        head.addView(text(title,if (isSettingsPage() || cinema) 33f else 26f).apply {typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)},LinearLayout.LayoutParams(0,dp(44),1f))
        if (!isSettingsPage()) head.addView(text(clockText(),22f).apply{tag="clock"})
        content.addView(head)
        if(name=="home")content.removeView(head)
        if(subtitle.isNotBlank())content.addView(text(subtitle,14f,Color.rgb(202,215,231)).apply{setPadding(0,dp(6),0,dp(14))})
        return content
    }
    private fun row(parent: LinearLayout = content): LinearLayout = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;clipChildren=false;clipToPadding=false;parent.addView(this) }
    private fun isCinemaPage() = page in setOf("sources", "vod", "manage_sources", "live", "search",
        "media_detail", "history", "favorites", "browse")
    private fun isSettingsPage() = page in setOf("settings", "wallpapers", "components")

    private fun action(label: String, run: () -> Unit) {
        val item = button(label, run).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            if (isSettingsPage()) {
                textSize = 16f
                tag = "settings-action:" + label.substringBefore('：')
                SettingsAppearance.row(this)
            }
        }
        content.addView(item, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
    }
    private fun note(s:String) { content.addView(text(s,15f,Color.rgb(203,216,232)).apply{setPadding(0,dp(10),0,dp(12))}) }
    private fun message(s:String) { dialog("客厅桌面",s) }
    private fun showDialog(d:AlertDialog) {
        activeDialog=d
        d.setOnDismissListener { if(activeDialog===d)activeDialog=null;lastInput=SystemClock.elapsedRealtime() }
        d.show()
        if (isSettingsPage() || isCinemaPage()) SettingsAppearance.dialog(d)
    }
    private fun dialog(title:String, body:String, positive:String="知道了", run:(()->Unit)?=null) {
        activeDialog=AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(positive){_,_->run?.invoke()}.apply{if(run!=null)setNegativeButton("取消",null)}.create()
        showDialog(activeDialog!!)
    }
    private fun choices(title:String, items:List<String>, selected:(Int)->Unit) {
        activeDialog=AlertDialog.Builder(this).setTitle(title).setItems(items.toTypedArray()){_,i->selected(i)}.setNegativeButton("取消",null).create()
        showDialog(activeDialog!!)
    }
    private fun input(title:String, hint:String="", initial:String="", done:(String)->Unit) {
        val field=EditText(this).apply{setSingleLine(true);setText(initial);this.hint=hint;setPadding(dp(20),dp(16),dp(20),dp(16));selectAll()}
        activeDialog=AlertDialog.Builder(this).setTitle(title).setView(field).setPositiveButton("确定",null).setNegativeButton("取消",null).create()
        showDialog(activeDialog!!)
        val inputDialog=activeDialog!!
        inputDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            try { done(field.text.toString().trim()); inputDialog.dismiss() } catch(e:Exception){field.error=e.message ?: "输入无效"}
        };field.requestFocus()
    }
    private fun <T> background(label:String, task:()->T, done:(T)->Unit) {
        val token=generation
        val progress=ProgressDialog(this).apply{setMessage(label);setCancelable(true);show()}
        var cancelled=false
        progress.setOnCancelListener{cancelled=true}
        worker.execute {
            try { val value=task();runOnUiThread{progress.dismiss();if(!cancelled && !isFinishing && generation==token)done(value)} }
            catch(e:Exception){runOnUiThread{progress.dismiss();if(!cancelled && !isFinishing && generation==token)message("${e.message ?: "操作失败"}\n可重试、换源，或检查网络和地址。")}}
        }
    }
    private data class App(val pkg:String,val label:String,val icon:Drawable,val intent:Intent)
    private fun apps():List<App> {
        val found=linkedMapOf<String,ResolveInfo>()
        listOf(Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER).forEach{category->
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category),0).forEach{r->if(r.activityInfo.packageName!=packageName)found.getOrPut(r.activityInfo.packageName){r}}
        }
        return found.values.map{r-> val p=r.activityInfo.packageName;App(p,when(p){"com.android.tv.settings"->"电视原厂设置";"com.google.android.tv.remote.service"->"遥控服务";"com.android.vending"->"应用商店";else->r.loadLabel(packageManager).toString()},r.loadIcon(packageManager),Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(p,r.activityInfo.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.sortedBy{it.label}
    }
    private fun launch(i:Intent) { try{startActivity(i)}catch(_:Exception){message("此设备无法打开该入口，请使用系统设置或原厂桌面。") } }
    private fun tile(parent:LinearLayout,label:String,key:String,kind:String?=null,icon:Drawable?=null,fill:Int=Color.rgb(36,66,92),run:()->Unit):LinearLayout {
        val available=resources.displayMetrics.widthPixels-dp(2*gutter())-parent.paddingLeft-parent.paddingRight
        val w=(available/columns()-dp(12)).coerceAtLeast(dp(85));val h=(w*.60f).toInt()
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;tag=key;contentDescription=label;isFocusable=true;isFocusableInTouchMode=true;isClickable=true;clipChildren=false;clipToPadding=false}
        parent.addView(box,LinearLayout.LayoutParams(w,h+dp(30)).apply{setMargins(dp(6),dp(10),dp(6),dp(10))})
        val surface=FrameLayout(this).apply{background=rounded(fill,dp(13).toFloat(),0x28FFFFFF);clipToOutline=true;elevation=dp(5).toFloat();cameraDistance=dp(1200).toFloat()}
        box.addView(surface,LinearLayout.LayoutParams(-1,h))
        val image=if(icon!=null)ImageView(this).apply{setImageDrawable(icon);scaleType=ImageView.ScaleType.FIT_CENTER;setPadding(dp(16),dp(10),dp(16),dp(10))}else HomeIconView(this,kind ?: "settings")
        image.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        surface.addView(image,FrameLayout.LayoutParams(-1,-1))
        val caption=text(label,14f).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;gravity=Gravity.CENTER;alpha=.78f;setPadding(0,dp(6),0,0);setShadowLayer(dp(3).toFloat(),0f,1f,Color.BLACK)}
        box.addView(caption,LinearLayout.LayoutParams(-1,dp(30)))
        box.setOnFocusChangeListener{_,focused->
            val reduced=Settings.Global.getFloat(contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f
            surface.animate().scaleX(if(focused)1.10f else 1f).scaleY(if(focused)1.10f else 1f).rotationX(if(focused && !reduced)-4f else 0f).rotationY(if(focused && !reduced)5f else 0f).setDuration(if(reduced)0 else 190).start()
            surface.elevation=dp(if(focused)22 else 5).toFloat()
            surface.foreground=rounded(Color.TRANSPARENT,dp(13).toFloat(),if(focused)0xB0FFFFFF.toInt()else 0x20FFFFFF)
            caption.alpha=if(focused)1f else .78f;caption.typeface=android.graphics.Typeface.create("sans-serif",if(focused)android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        box.setOnTouchListener{v,event->if(event.action==android.view.MotionEvent.ACTION_DOWN)v.requestFocus();false}
        box.setOnClickListener{run()};return box
    }
    private fun clockText()=chineseTime(if(store.string("clock_format","24")=="12")"a h:mm"else"HH:mm")
    private fun weatherHero(){
        val hero=row();hero.gravity=Gravity.CENTER_VERTICAL
        val compact=resources.displayMetrics.heightPixels/resources.displayMetrics.density<400
        val left=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL}
        hero.addView(left,LinearLayout.LayoutParams(0,dp(if(compact)100 else 148),1f))
        left.addView(text(clockText(),if(compact)48f else 78f).apply{tag="clock";typeface=android.graphics.Typeface.create("sans-serif-thin",android.graphics.Typeface.NORMAL);letterSpacing=-.035f;setShadowLayer(dp(5).toFloat(),0f,2f,0x50000000)})
        left.addView(text(chineseTime("M月d日  EEEE"),if(compact)14f else 19f,0xFFDCE7FA.toInt()).apply{tag="home-date";setPadding(dp(3),dp(4),0,0)})
        val right=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.END or Gravity.CENTER_VERTICAL;isFocusable=true;isClickable=true;contentDescription="天气与城市设置";setPadding(dp(12),dp(8),dp(12),dp(8));tag="weather-widget"}
        hero.addView(right,LinearLayout.LayoutParams(dp(if(compact)180 else 300),-2))
        val tempRow=LinearLayout(this).apply{gravity=Gravity.END or Gravity.CENTER_VERTICAL}
        tempRow.addView(WeatherGlyph(this).apply{tag="weather-glyph"},LinearLayout.LayoutParams(dp(if(compact)34 else 54),dp(if(compact)34 else 54)))
        tempRow.addView(text("--°",if(compact)29f else 42f).apply{tag="weather-temperature";setPadding(dp(12),0,0,0)})
        right.addView(tempRow)
        right.addView(text("正在识别网络城市…",if(compact)14f else 18f).apply{tag="weather-city";gravity=Gravity.END;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
        right.addView(text("",if(compact)12f else 14f,0xFFD1DDF1.toInt()).apply{tag="weather-range";gravity=Gravity.END;setPadding(0,dp(5),0,0)})
        right.addView(text("",11f,0xFFB9C9E3.toInt()).apply{tag="weather-updated";gravity=Gravity.END;setPadding(0,dp(4),0,0)})
        right.setOnFocusChangeListener{v,f->v.background=if(f)rounded(0x20FFFFFF,dp(14).toFloat(),0x70FFFFFF)else null}
        right.setOnClickListener{settings("时间与天气")}
        showWeather();refreshWeather()
    }
    private fun showWeather(){
        if(!::root.isInitialized)return
        val temp=root.findViewWithTag<TextView>("weather-temperature") ?: return
        val city=root.findViewWithTag<TextView>("weather-city");val range=root.findViewWithTag<TextView>("weather-range");val updated=root.findViewWithTag<TextView>("weather-updated")
        val cached=weather.cached();val usable=WeatherLogic.usable(cached)
        if(!weather.enabled()){temp.text="";city.text="天气已关闭";range.text="点此开启";updated.text="";return}
        if(usable && cached!=null){
            temp.text=cached.degrees;city.text="${cached.city.name} · ${WeatherLogic.description(cached.code)}";range.text=cached.range
            val old=System.currentTimeMillis()-cached.fetchedAt>60*60*1000L
            updated.text=(if(cached.city.automatic)"网络城市" else "手动城市")+" · "+(if(weatherError.isNotBlank()||old)"上次数据 "else "更新 ")+cached.observed.takeLast(5)
            root.findViewWithTag<WeatherGlyph>("weather-glyph")?.let{it.code=cached.code;it.day=cached.day;it.invalidate()}
        }else{temp.text="--°";city.text=if(weatherError.isNotBlank())"天气暂不可用"else"正在识别网络城市…";range.text=if(weatherError.isNotBlank())"点此重试或更正城市"else"按网络出口自动识别";updated.text=""}
    }
    private fun refreshWeather(force:Boolean=false){
        if(!weather.enabled()||weatherLoading)return
        val now=SystemClock.elapsedRealtime();if(!force && now-lastWeatherAttempt<60000 && lastWeatherAttempt!=0L)return
        lastWeatherAttempt=now;weatherLoading=true
        weatherWorker.execute{try{weather.refresh(force);runOnUiThread{if(!isDestroyed){weatherLoading=false;weatherError="";showWeather()}}}catch(_:Exception){runOnUiThread{if(!isDestroyed){weatherLoading=false;weatherError="天气获取失败";showWeather()}}}}
    }
    private fun home() {
        if(portalKind!=null){finish();return}
        begin("home","", "")
        weatherHero()
        content.addView(Space(this),LinearLayout.LayoutParams(1,dp(if(resources.displayMetrics.heightPixels/resources.displayMetrics.density<400)8 else 30)))
        var main=row().apply{setPadding(dp(8),dp(2),dp(8),dp(2));background=rounded(0x482B344B,dp(28).toFloat(),0x35FFFFFF)};var slot=0
        fun nextSlot():LinearLayout {if(slot>0 && slot%columns()==0)main=row().apply{setPadding(dp(8),dp(2),dp(8),dp(2));background=rounded(0x482B344B,dp(28).toFloat(),0x35FFFFFF)};slot++;return main}
        tile(nextSlot(),"播放器","builtin:play","play"){sources()}
        tile(nextSlot(),"设置","builtin:settings","settings"){settings()}
        val all=apps();val favs=store.order("favorites");val hidden=store.set("hidden")
        val preferred=(favs.mapNotNull{p->all.find{it.pkg==p}}+all.filter{it.pkg!="com.android.tv.settings" && !it.pkg.contains("remote.service")}).distinctBy{it.pkg}.filter{it.pkg !in hidden && (store.string("folder:${it.pkg}").isBlank() || store.string("folder:${it.pkg}") !in store.order("folders"))}.take(2)
        preferred.forEach{a->appTile(nextSlot(),a)}
        val visible=all.filter{it.pkg !in hidden && it.pkg !in preferred.map{a->a.pkg}}
        val folders=store.order("folders")
        val entries=mutableListOf<Pair<String,Any>>()
        folders.forEach{f-> if(visible.any{store.string("folder:${it.pkg}")==f})entries+=f to f }
        visible.filter{store.string("folder:${it.pkg}").isBlank() || store.string("folder:${it.pkg}") !in folders}.forEach{entries+=it.pkg to it}
        val saved=store.order("appOrder");val sorted=entries.sortedBy{val index=saved.indexOf(it.first);if(index<0)Int.MAX_VALUE else index}
        if(sorted.isNotEmpty())content.addView(text("全部应用",15f,Color.rgb(222,231,242)).apply{setPadding(dp(6),dp(24),0,dp(4))})
        sorted.chunked(columns()).forEach{group->val line=row();group.forEach{(_,value)->
            if(value is App)appTile(line,value) else {val f=value.toString();val v=tile(line,f,"folder:$f","folder",fill=Color.rgb(61,75,104)){openFolder(f)};v.setOnLongClickListener{folderMenu(f);true}}
        }}
        if(all.isEmpty())note("安装的电视应用会显示在这里。可先进入播放器添加播放源。")
        updateWallpaper();handler.post{root.findViewWithTag<View>(lastFocus)?.requestFocus() ?: root.findViewWithTag<View>("builtin:play")?.requestFocus()}
    }
    private fun appTile(line:LinearLayout,a:App) {
        tile(line,a.label,a.pkg,icon=a.icon,fill=Color.rgb(40,49,66)){launch(a.intent)}.setOnLongClickListener{appMenu(a);true}
    }
    private fun appMenu(a:App) {
        choices(a.label,listOf(if(a.pkg in store.order("favorites"))"从常用移除" else "加入常用顶排","向前移动","向后移动","放入文件夹","移出文件夹","从桌面隐藏","系统应用信息")){n->
            when(n){
                0->{val v=store.order("favorites").toMutableList();if(!v.remove(a.pkg))v.add(0,a.pkg);store.saveOrder("favorites",v);home()}
                1,2->{moveApp(a.pkg,if(n==1)-1 else 1);home()}
                3->{val folders=store.order("folders");choices("选择文件夹",folders+"新建文件夹"){i->if(i==folders.size)input("新建文件夹","例如：影音"){name->require(name.isNotBlank());store.saveOrder("folders",(folders+name).distinct());store.put("folder:${a.pkg}",name);handler.post{home()}}else{store.put("folder:${a.pkg}",folders[i]);home()}}}
                4->{store.put("folder:${a.pkg}","");home()}
                5->{store.toggle("hidden",a.pkg);home()}
                6->launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${a.pkg}")))
            }
        }
    }
    private fun moveApp(pkg:String,direction:Int){
        val favorites=store.order("favorites").toMutableList()
        if(pkg in favorites){val idx=favorites.indexOf(pkg);Collections.swap(favorites,idx,(idx+direction).coerceIn(0,favorites.lastIndex));store.saveOrder("favorites",favorites);return}
        val all=apps().filter{it.pkg !in store.set("hidden")}
        val folders=store.order("folders").filter{f->all.any{store.string("folder:${it.pkg}")==f}}
        val membership=store.string("folder:$pkg")
        val actual=if(membership.isNotBlank() && pkg !in folders)all.filter{store.string("folder:${it.pkg}")==membership}.map{it.pkg}
            else (folders+all.filter{store.string("folder:${it.pkg}").isBlank()}.map{it.pkg}).distinct()
        val saved=store.order("appOrder");val list=actual.sortedBy{saved.indexOf(it).let{n->if(n<0)Int.MAX_VALUE else n}}.toMutableList()
        val idx=list.indexOf(pkg);if(idx>=0){val next=(idx+direction).coerceIn(0,list.lastIndex);Collections.swap(list,idx,next);store.saveOrder("appOrder",list+saved.filter{it !in list})}
    }
    private fun openFolder(name:String){begin("folder",name,"长按应用可移出文件夹");apps().filter{store.string("folder:${it.pkg}")==name && it.pkg !in store.set("hidden")}.sortedBy{store.order("appOrder").indexOf(it.pkg).let{i->if(i<0)Int.MAX_VALUE else i}}.chunked(columns()).forEach{g->val r=row();g.forEach{appTile(r,it)}};action("返回桌面"){home()}}
    private fun folderMenu(name:String){choices(name,listOf("向前移动","向后移动","重命名","解散文件夹")){i->when(i){0,1->{moveApp(name,if(i==0)-1 else 1);home()};2->input("重命名",initial=name){n->require(n.isNotBlank());val fs=store.order("folders");require(n==name||n !in fs){"文件夹已存在"};apps().filter{store.string("folder:${it.pkg}")==name}.forEach{store.put("folder:${it.pkg}",n)};store.saveOrder("folders",fs.map{if(it==name)n else it});store.saveOrder("appOrder",store.order("appOrder").map{if(it==name)n else it});handler.post{home()}};3->{apps().filter{store.string("folder:${it.pkg}")==name}.forEach{store.put("folder:${it.pkg}","")};store.saveOrder("folders",store.order("folders")-name);home()}}}}
    private fun settings(section:String="通用"){
        val previous = currentFocus?.tag
        val opening = page != "settings"
        if (opening) {
            begin("settings", "更多设置")
            val heading = content.getChildAt(0)
            content.removeView(heading)
            val body = row()
            body.gravity = Gravity.TOP
            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            body.addView(left, LinearLayout.LayoutParams(dp(if (columns() < 5) 150 else 208), -2).apply {
                marginEnd = dp(26)
            })
            left.addView(heading)
            left.addView(text("客厅桌面", 14f, 0xCCDFEAF1.toInt()).apply {
                setPadding(0, dp(4), 0, dp(18))
            })
            settingsNav = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            left.addView(settingsNav)
            settingsSections.forEach { name ->
                val tab = button(name) { settings(name) }.apply {
                    id = View.generateViewId()
                    tag = "settings-nav:$name"
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    textSize = 16f
                    SettingsAppearance.row(this)
                }
                settingsNav.addView(tab, LinearLayout.LayoutParams(-1, dp(43)).apply { bottomMargin = dp(7) })
            }
            settingsScroll = ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                clipToOutline = true
                background = SettingsAppearance.glass(this@MainActivity)
            }
            settingsPanel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                setPadding(dp(24), dp(22), dp(24), dp(24))
            }
            settingsScroll.addView(settingsPanel)
            val height = (resources.displayMetrics.heightPixels / resources.displayMetrics.density - 64).toInt()
            body.addView(settingsScroll, LinearLayout.LayoutParams(0, dp(height.coerceAtLeast(180)), 1f))
        } else {
            generation++
            activeDialog?.dismiss()
            activeDialog = null
        }
        val outer = content
        content = settingsPanel
        content.removeAllViews()
        content.addView(text(section, 25f).apply {
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(0, 0, 0, dp(12))
        })
        when(section){
            "通用"->{
                note("语言：简体中文")
                action("外观与壁纸"){settings("外观与壁纸")}
                action("时间与天气"){settings("时间与天气")}
                action("应用排列与隐藏"){settings("应用管理")}
                action("电视原厂设置"){launch(Intent().setComponent(ComponentName(packageName,"com.gathin.tvhome.FactorySettingsActivity")))}
                val home=packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)?.activityInfo?.packageName
                note("默认桌面：${if(home==packageName)"客厅桌面"else"由电视系统管理"}")
            }
            "外观与壁纸"->{action("壁纸图库"){startActivity(Intent(this, WallpaperPortalActivity::class.java))};note("""动态壁纸在进入播放器或其他应用后自动暂停。
聚焦图标显示立体层次；减弱动态效果可在电视系统中设置。""")}
            "时间与天气"->{
                action("时间格式（桌面设置）"){launch(Intent().setComponent(ComponentName(packageName,"com.gathin.tvhome.LauncherSettingsActivity")))}
                note("""时区：北京时间
日期与界面语言：简体中文""")
                action("显示天气：${if(weather.enabled())"已开启"else"已关闭"}"){store.put("weather_enabled",(!weather.enabled()).toString());settings(section)}
                action("城市：${weather.manual()?.name ?: "按网络自动识别"}"){choices("天气城市",listOf("按网络自动识别","手动选择城市")){i->if(i==0){weather.setManual(null);refreshWeather(true);settings(section)}else input("选择城市","请输入城市名，例如重庆"){q->require(q.isNotBlank());handler.post{background("正在查找城市…",{weather.search(q)}){cities->if(cities.isEmpty())message("没有找到该城市，请尝试完整城市名。")else choices("选择城市",cities.map{it.name}){n->weather.setManual(cities[n]);refreshWeather(true);settings(section)}}}}}}
                action("立即更新天气"){refreshWeather(true);Toast.makeText(this,"正在更新天气",Toast.LENGTH_SHORT).show()}
                val cached=weather.cached();if(cached!=null)note("""${cached.city.name} · ${WeatherLogic.description(cached.code)} ${cached.degrees}
${cached.range}
数据时间：${cached.observed.replace('T',' ')}""")
                note("""网络城市按网络出口识别，使用代理或VPN时可能显示出口城市。无需GPS权限。
天气数据：Open-Meteo；网络城市：IPwhois。""")
            }
            "应用管理"->{
                note("在新桌面长按应用图标可调整排列；应用显示、隐藏和布局由 CleanYourTV 桌面设置管理。")
                action("打开桌面设置"){launch(Intent().setComponent(ComponentName(packageName,"com.gathin.tvhome.LauncherSettingsActivity")))}
                action("备份播放源与壁纸设置"){createDocument()}
                action("导入播放源与壁纸设置"){pick("*/*",22)}
                action("厂商组件管理"){components()}
            }
            "系统与遥控器"->{
                action("电视原厂设置"){launch(Intent().setComponent(ComponentName(packageName,"com.gathin.tvhome.FactorySettingsActivity")))}
                action("设置默认桌面"){launch(Intent(Settings.ACTION_HOME_SETTINGS))}
                action("打开原厂桌面"){
                    val list=packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0).filter{it.activityInfo.packageName!=packageName}
                    if(list.isEmpty())message("系统未提供原厂桌面入口，请通过电视系统设置切换。")else choices("选择原厂桌面",list.map{it.loadLabel(packageManager).toString()}){i->val r=list[i];launch(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(r.activityInfo.packageName,r.activityInfo.name)))}
                }
                note("""图像、声音、网络、输入源和遥控器配对等设置由原厂系统提供，本桌面不会替换或禁用这些功能。
原厂设置的语言跟随电视系统语言。""")
            }
            else->{note("""客厅桌面 · CleanYourTV 中国版 3.3-cn.11
语言：简体中文
${Build.MANUFACTURER} · ${Build.MODEL}
系统版本：Android ${Build.VERSION.RELEASE}""");action("兼容性说明"){message("""桌面与播放器可独立使用。默认桌面、Home键和厂商组件管理需要逐机型验证。
本版本不包含苹果账号服务、Siri或AirPlay。""")}}
        }
        content = outer
        val index = settingsSections.indexOf(section)
        val active = settingsNav.getChildAt(index)
        val actions = ArrayList<View>()
        settingsPanel.addFocusables(actions, View.FOCUS_RIGHT)
        actions.firstOrNull()?.let { first ->
            if (first.id == View.NO_ID) first.id = View.generateViewId()
            for (i in 0 until settingsNav.childCount) settingsNav.getChildAt(i).nextFocusRightId = first.id
            actions.forEach { it.nextFocusLeftId = active.id }
        }
        for (i in 0 until settingsNav.childCount) settingsNav.getChildAt(i).isSelected = i == index
        settingsScroll.scrollTo(0, 0)
        val changed = opening || settingsSection != section
        if (changed) {
            val direction = if (index >= settingsSections.indexOf(settingsSection)) 1 else -1
            SettingsAppearance.reveal(if (opening) settingsScroll else settingsPanel, direction)
        }
        settingsSection = section
        val focused = if (!changed && previous is String && previous.startsWith("settings-action:")) {
            settingsPanel.findViewWithTag<View>(previous)
        } else null
        (focused ?: active).requestFocus()
    }
    private fun components(){
        begin("components","组件管理","系统操作由配套 Mac 工具通过 ADB 执行")
        note("设备：${Build.MANUFACTURER} / ${Build.MODEL}\n固件：${Build.FINGERPRINT}\n\n适配状态：未提供该实机的验证规则。\n当前仅支持扫描与查看，不自动禁用未知组件。")
        action("如何连接 Mac") { message("1. 在电视系统设置中开启开发者选项和 ADB 调试。\n2. Mac 打开交付包中的「客厅管家」。\n3. 输入电视调试地址，确认电视上的授权提示。\n4. 扫描设备，预览匹配的组件规则后执行。\n\n授权失效时需要重新连接。不要把调试端口开放到互联网。") }
        action("查看禁用与恢复规则"){message("仅允许匹配已验证型号、固件、包名的可选组件。\n每项操作保存原状态，执行后核对结果。恢复只撤销管家已做的变更。\n\n设置、遥控器、输入源、网络、安装器和原厂桌面受到保护。开机广告与其他应用的视频广告不在此功能范围内。")}
        action("打开电视系统设置"){launch(Intent().setComponent(ComponentName(packageName,"com.gathin.tvhome.FactorySettingsActivity")))};action("返回桌面"){home()}
    }
    private fun sources() {
        store.installStarterSources()
        playerTab = "点播"
        begin("sources", "客厅影院")
        val navigation = row()
        playerButton(navigation, "首页", true) { sources() }.apply { requestFocus() }
        vodCategories.forEach { name ->
            playerButton(navigation, name) { vodBrowse(null, VodFilter(category = name)) }
        }
        val shortcuts = row()
        cinemaShortcut(shortcuts, "历史", ::history)
        cinemaShortcut(shortcuts, "直播", ::liveChannels)
        cinemaShortcut(shortcuts, "搜索", { playerSearch() })
        cinemaShortcut(shortcuts, "扫码搜索", { playerSearch(); showSearchQr() })
        cinemaShortcut(shortcuts, "收藏", ::favorites)
        cinemaShortcut(shortcuts, "设置", ::manageSources)
        val entries = localCatalog()
        val domestic = entries.filter { CatalogParser.matchesRegion(it.area, "国产") }
        val movies = domestic.filter { it.category == "电影" }
        val series = domestic.filter { it.category == "连续剧" }
        val others = entries - domestic.toSet()
        if (movies.isNotEmpty()) cinemaShelf("国产电影", movies, VodFilter(category = "电影", region = "国产"))
        if (series.isNotEmpty()) cinemaShelf("国产剧集", series, VodFilter(category = "连续剧", region = "国产"))
        if (others.isNotEmpty()) cinemaShelf("海外与经典", others, VodFilter(region = "海外"))
        if (entries.isEmpty()) note("在「设置」中启用或添加点播源。")
        val providers = store.sources().filter { it.enabled && it.kind == "cms" }
        if (providers.isNotEmpty()) {
            cinemaHeading("点播源", "打开站点浏览完整片库")
            providers.chunked(3).forEach { group ->
                val line = row()
                group.forEach { source -> playerButton(line, source.name) { vodBrowse(source) } }
            }
        }
    }

    private fun localCatalog(): List<MediaEntry> = store.sources().filter { it.enabled && it.onDemand }
        .flatMap { source ->
            if (source.kind == "cms") runCatching { CatalogParser.collection(source.inline) }.getOrDefault(emptyList())
            else MediaNetwork.load(source)
        }
        .distinctBy { it.category + ":" + it.name.replace(Regex("[\\p{Cf}\\s]"), "") }

    private fun cinemaShelf(title: String, entries: List<MediaEntry>, filter: VodFilter) {
        val heading = row().apply { setPadding(0, dp(15), 0, dp(9)) }
        heading.addView(text(title, 24f).apply {
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val all = button("查看全部") { vodBrowse(null, filter) }.apply {
            contentDescription = "查看全部$title"
            SettingsAppearance.row(this)
        }
        heading.addView(all, LinearLayout.LayoutParams(dp(110), dp(43)).apply { marginEnd = dp(12) })
        posterGrid(entries.take(posterColumns()), ::sources)
    }

    private fun cinemaHeading(label: String, hint: String = "") {
        val header = row()
        header.setPadding(0, dp(17), 0, dp(11))
        header.addView(text(label, 23f).apply {
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (hint.isNotBlank()) header.addView(text(hint, 13f, 0xBBD8E5F5.toInt()))
    }

    private fun cinemaShortcut(parent: LinearLayout, title: String, open: () -> Unit) {
        val tile = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_DOWN) view.requestFocus()
                false
            }
            contentDescription = title
            background = SettingsAppearance.glass(this@MainActivity)
            setOnClickListener { open() }
            setOnFocusChangeListener { view, focused -> posterFocus(view, focused, 28) }
            posterFocus(this, false, 28)
        }
        tile.addView(CinemaGlyph(this, title).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(21), dp(21)).apply { marginEnd = dp(7) })
        tile.addView(text(title, 15f).apply {
            isSingleLine = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        parent.addView(tile, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            topMargin = dp(10)
            marginEnd = dp(8)
        })
    }

    private fun liveChannels() {
        playerTab = "直播"
        begin("live", "直播", "选择电视频道直接播放，长按可收藏")
        val shortcuts = row()
        playerButton(shortcuts, "返回首页") { sources() }
        playerButton(shortcuts, "播放源管理") { manageSources() }
        val liveSources = store.sources().filter { it.enabled && !it.onDemand && it.kind != "tvbox" }
        if (liveSources.isEmpty()) note("可在设置中添加直播直链或 M3U 列表。")
        val queue = liveSources.filter { it.kind == "direct" }.map { Episode(it.name, it.url) }
        liveSources.forEach { source ->
            val item = button(source.name) {
                if (source.kind == "direct") play(queue, queue.indexOfFirst { it.url == source.url }, true)
                else browse(source)
            }
            SettingsAppearance.row(item)
            content.addView(item, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
            if (source.kind == "direct") item.setOnLongClickListener {
                toggleFavorite(Episode(source.name, source.url)); true
            }
        }
    }

    private fun posterImage(entry: MediaEntry): FrameLayout = FrameLayout(this).apply {
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF30486E.toInt(), 0xFF19253B.toInt(), 0xFF101827.toInt())
        ).apply { cornerRadius = dp(15).toFloat() }
        clipToOutline = true
        val placeholder = text(entry.name.substringBefore(' '), 22f, 0xFFD9E8F9.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            maxLines = 3
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(placeholder, FrameLayout.LayoutParams(-1, -1))
        val photo = ImageView(this@MainActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(photo, FrameLayout.LayoutParams(-1, -1))
        cinemaPosters.value.load(entry, photo)
    }

    private fun posterColumns(): Int {
        val width = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return ((width - 2 * gutter()) / 147).toInt().coerceIn(3, 6)
    }

    private fun posterGrid(entries: List<MediaEntry>, returnTo: () -> Unit) {
        val width = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val count = posterColumns()
        val height = (((width - 2 * gutter()) / count - 13) * 1.42f).toInt().coerceIn(125, 286)
        entries.chunked(count).forEach { group ->
            val strip = row()
            strip.gravity = Gravity.TOP
            group.forEach { entry ->
                val frame = posterImage(entry).apply {
                    tag = entry.episodes.first().url
                    contentDescription = entry.name
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnTouchListener { view, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) view.requestFocus()
                        false
                    }
                    setOnClickListener { mediaDetail(entry, returnTo) }
                    setOnLongClickListener { saveVod(entry); if (page == "favorites") favorites(); true }
                    setOnFocusChangeListener { view, focused -> posterFocus(view, focused) }
                    posterFocus(this, false)
                }
                val title = text(entry.name, 15f).apply {
                    gravity = Gravity.BOTTOM or Gravity.START
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    maxLines = 2
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setPadding(dp(9), dp(24), dp(9), dp(11))
                    background = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(Color.TRANSPARENT, 0xEF090E18.toInt())
                    )
                }
                frame.addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                if (entry.year.isNotBlank()) {
                    val year = text(entry.year, 11f).apply {
                        setPadding(dp(7), dp(4), dp(7), dp(4))
                        background = rounded(0xB5111927.toInt(), dp(8).toFloat())
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    frame.addView(year, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                        topMargin = dp(8)
                        marginEnd = dp(8)
                    })
                }
                strip.addView(frame, LinearLayout.LayoutParams(0, dp(height), 1f).apply {
                    marginEnd = dp(12)
                    bottomMargin = dp(14)
                })
                if (frame.tag == restorePoster) {
                    restorePoster = null
                    frame.post { if (frame.isAttachedToWindow) frame.requestFocus() }
                }
            }
            repeat(count - group.size) { strip.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = dp(12) }) }
        }
    }

    private fun mediaDetail(entry: MediaEntry, returnTo: () -> Unit) {
        begin("media_detail", entry.category.ifBlank { "点播" })
        detailBack = {
            restorePoster = entry.episodes.first().url
            returnTo()
        }
        val body = row().apply { gravity = Gravity.TOP; setPadding(0, dp(9), 0, 0) }
        body.addView(posterImage(entry), LinearLayout.LayoutParams(dp(205), dp(304)).apply { marginEnd = dp(25) })
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SettingsAppearance.glass(this@MainActivity)
            setPadding(dp(22), dp(19), dp(22), dp(22))
        }
        body.addView(panel, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(text(entry.name, 28f))
        val metadata = listOf(entry.category, entry.year, entry.area, entry.genres.joinToString(" / "), entry.provider).filter(String::isNotBlank)
        if (metadata.isNotEmpty()) panel.addView(text(metadata.joinToString("  ·  "), 14f, 0xC6DDE9F7.toInt()).apply {
            setPadding(0, dp(12), 0, dp(12))
        })
        if (entry.synopsis.isNotBlank()) panel.addView(text(entry.synopsis, 15f, 0xD9E2EDFF.toInt()).apply {
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(12))
        })
        val controls = row(panel)
        playerButton(controls, "播放") { playVod(entry, 0) }.requestFocus()
        val saved = playerButton(controls, if (store.isSaved(entry)) "取消收藏" else "加入收藏") { saveVod(entry) }
        detailFavorite = { saved.text = if (store.isSaved(entry)) "取消收藏" else "加入收藏" }
        if (entry.episodes.size > 1) {
            panel.addView(text("选集", 17f).apply { setPadding(0, dp(18), 0, dp(3)) })
            entry.episodes.forEachIndexed { index, episode ->
                playerButton(row(panel), episode.name) { playVod(entry, index) }
            }
        }
    }

    private fun saveVod(entry: MediaEntry) {
        val added = store.toggleSaved(entry)
        detailFavorite?.invoke()
        Toast.makeText(this, if (added) "已收藏 ${entry.name}" else "已取消收藏 ${entry.name}", Toast.LENGTH_SHORT).show()
    }

    private fun playVod(entry: MediaEntry, index: Int) {
        val queue = entry.episodes.map {
            it.copy(name = if (entry.episodes.size == 1) entry.name else "${entry.name} · ${it.name}")
        }
        play(queue, index, media = entry)
    }

    private fun playerSearch(query: String = "") {
        begin("search", "搜索", "搜索已启用的点播片库")
        val controls = row()
        playerButton(controls, "遥控器输入") {
            input("搜索片名", "片名或关键词", query) { value ->
                require(value.isNotBlank() && value.length <= 120) { "请输入 1 至 120 个字符" }
                handler.post { playerSearch(value) }
            }
        }.requestFocus()
        playerButton(controls, "手机扫码输入") { showSearchQr() }
        playerButton(controls, "返回首页") { sources() }
        if (query.isBlank()) {
            cinemaHeading("输入片名或关键词", "支持中文和英文")
            note("用遥控器输入，或让手机和电视连上同一局域网后扫码输入。")
            return
        }
        val token = generation
        val local = localCatalog().filter { it.name.contains(query, true) }
        val remote = store.sources().filter { it.enabled && it.kind == "cms" }
        val status = text("", 15f, 0xDDE5F0FA.toInt()).apply { setPadding(0, dp(13), 0, dp(12)) }
        cinemaHeading("“$query”")
        content.addView(status)
        posterGrid(local) { playerSearch(query) }
        if (remote.isEmpty()) {
            status.text = if (local.isEmpty()) "没有匹配的节目，可换个关键词。" else "找到 ${local.size} 项"
            return
        }
        status.text = "正在搜索 ${remote.size} 个点播源…"
        worker.execute {
            val pool = Executors.newFixedThreadPool(minOf(8, remote.size))
            val responses = try {
                pool.invokeAll(remote.map { source ->
                    java.util.concurrent.Callable { MediaNetwork.load(source, query) }
                }, 24, java.util.concurrent.TimeUnit.SECONDS).map { task -> runCatching { task.get() }.getOrNull() }
            } catch (_: InterruptedException) {
                return@execute
            } finally {
                pool.shutdownNow()
            }
            val seen = local.mapTo(mutableSetOf()) { it.episodes.first().url }
            val matches = responses.filterNotNull().flatten().filter { seen.add(it.episodes.first().url) }
            val failed = responses.count { it == null }
            handler.post {
                if (token != generation || isDestroyed || isFinishing) return@post
                val size = local.size + matches.size
                status.text = if (size == 0) "没有匹配的节目，可换个关键词。" else "找到 $size 项"
                if (failed > 0) status.append(" · $failed 个源未响应")
                posterGrid(matches) { playerSearch(query) }
            }
        }
    }

    private fun showSearchQr() {
        if (searchDialog != null) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(8), dp(22), dp(12))
        }
        val image = ImageView(this).apply { contentDescription = "手机搜索二维码" }
        box.addView(image, LinearLayout.LayoutParams(dp(210), dp(210)))
        val status = text("正在生成二维码…", 15f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(7))
        }
        val address = text("", 12f, 0xBFD7E9FF.toInt()).apply { gravity = Gravity.CENTER }
        box.addView(status, LinearLayout.LayoutParams(-1, -2))
        box.addView(address, LinearLayout.LayoutParams(-1, -2))
        val dialog = AlertDialog.Builder(this).setTitle("用手机搜索").setView(box)
            .setPositiveButton("关闭", null).create()
        val server = LanInputServer(applicationContext, onReady = { url ->
            val bitmap = qrBitmap(url)
            handler.post {
                if (searchDialog === dialog) {
                    image.setImageBitmap(bitmap)
                    status.text = "同一局域网内用手机相机扫码，输入后电视自动搜索"
                    address.text = url
                }
            }
        }, onSearch = { query -> handler.post {
            if (searchDialog === dialog) {
                dialog.dismiss()
                playerSearch(query)
            }
        } }, onError = { message -> handler.post {
            if (searchDialog === dialog) {
                status.text = message
                address.text = ""
                image.setImageDrawable(null)
            }
        } })
        searchDialog = dialog
        dialog.setOnDismissListener {
            server.close()
            if (searchDialog === dialog) searchDialog = null
        }
        dialog.show()
        SettingsAppearance.dialog(dialog)
        server.start()
    }

    private fun playerButton(parent: LinearLayout, label: String, selected: Boolean = false, run: () -> Unit): Button {
        val view = button(label, run).apply {
            isSelected = selected
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            SettingsAppearance.row(this)
        }
        parent.addView(view, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginEnd = dp(8)
            topMargin = dp(8)
        })
        return view
    }

    private fun manageSources() {
        begin("manage_sources", "播放器设置", "播放源管理 · 编辑、检测、停用或删除")
        val top = row()
        playerButton(top, "返回首页") { sources() }
        playerButton(top, "添加源") { sourceEditor() }
        playerButton(top, "导入配置文件") { pick("*/*", 22) }
        store.sources().forEach { source ->
            val label = "${source.name}  ·  ${sourceKindName(source)}  ·  ${if (source.enabled) "已启用" else "已停用"}"
            val item = button(label) { sourceMenu(source) }
            item.setOnLongClickListener { sourceMenu(source); true }
            content.addView(item, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        }
        action("返回播放器") { sources() }
    }

    private fun sourceKindName(source: Source) = when (source.kind) {
        "direct" -> if (source.onDemand) "点播直链" else "直播直链"
        "m3u" -> "直播列表"
        "cms" -> "苹果 CMS 点播"
        "collection" -> "公开点播片库"
        "tvbox" -> "TVBox 配置"
        else -> "播放源"
    }

    private fun vodBrowse(source: Source?, filter: VodFilter = VodFilter(), pageNumber: Int = 1) {
        val cms = source?.kind == "cms"
        val heading = if (filter.region == "国产" && filter.category.isNotBlank()) "国产${filter.category}"
            else filter.category.ifBlank { "全部点播" }
        begin("vod", source?.name ?: heading,
            if (cms) "按片源分类浏览 · 地区和年份在当前页筛选" else "选择海报进入详情或选集")
        val token = generation
        val available = if (source == null) localCatalog() else if (!cms) MediaNetwork.load(source) else emptyList()
        val categories = row()
        (listOf("全部") + vodCategories).forEach { label ->
            val value = label.takeUnless { it == "全部" }.orEmpty()
            playerButton(categories, label, filter.category == value) {
                vodBrowse(source, filter.copy(category = value, genre = "", year = ""))
            }.apply { if (filter.category == value) requestFocus() }
        }
        val tools = row()
        val providers = store.sources().filter { it.enabled && it.kind == "cms" }
        if (providers.isNotEmpty()) playerButton(tools, "选片源") {
            choices("点播源", listOf("首页片库") + providers.map { it.name }) { index ->
                vodBrowse(providers.getOrNull(index - 1), filter)
            }
        }
        playerButton(tools, "地区 · ${filter.region.ifBlank { "全部" }}") {
            val regions = listOf("全部", "国产", "中国大陆", "中国香港", "中国台湾", "海外")
            choices("选择地区", regions) { index ->
                vodBrowse(source, filter.copy(region = regions[index].takeUnless { index == 0 }.orEmpty()))
            }
        }
        playerButton(tools, if (filter.query.isBlank()) "搜索片名" else "搜索 · ${filter.query}") {
            input("搜索点播", "输入片名", filter.query) { query ->
                handler.post { vodBrowse(source, filter.copy(query = query.trim())) }
            }
        }
        if (filter.category == "电影") {
            val genres = if (cms) listOf("喜剧", "恐怖", "动作", "爱情", "科幻", "剧情", "纪录")
                else available.filter { it.category == "电影" }.flatMap { it.genres }.distinct().sorted()
            playerButton(tools, "类型 · ${filter.genre.ifBlank { "全部" }}") {
                choices("电影类型", listOf("全部") + genres) { index ->
                    vodBrowse(source, filter.copy(genre = genres.getOrNull(index - 1).orEmpty()))
                }
            }
            val years = if (cms) {
                val current = Calendar.getInstance().get(Calendar.YEAR)
                (current downTo 1900).map(Int::toString)
            } else available.filter { it.category == "电影" }.map { it.year }
                .filter(String::isNotBlank).distinct().sortedDescending()
            playerButton(tools, "年份 · ${filter.year.ifBlank { "全部" }}") {
                choices("上映年份", listOf("全部") + years) { index ->
                    vodBrowse(source, filter.copy(year = years.getOrNull(index - 1).orEmpty()))
                }
            }
        }
        val result = text(if (cms) "正在读取片库…" else "", 15f, 0xDDE5F0FA.toInt()).apply {
            setPadding(0, dp(14), 0, dp(4))
        }
        content.addView(result)
        fun render(items: VodPage) {
            if (token != generation) return
            result.text = if (items.entries.isEmpty()) "没有符合条件的节目，可调整筛选或继续翻页。"
                else "找到 ${items.entries.size} 项"
            posterGrid(items.entries) { vodBrowse(source, filter, pageNumber) }
            if (items.pages > 1 || items.page > 1) {
                note("第 ${items.page} 页 / 共 ${items.pages} 页")
                val navigation = row()
                if (items.page > 1) playerButton(navigation, "上一页") { vodBrowse(source, filter, items.page - 1) }
                if (items.page < items.pages) playerButton(navigation, "下一页") { vodBrowse(source, filter, items.page + 1) }
            }
            action("返回点播") { sources() }
        }
        if (cms && source != null) {
            action("重新加载 / 重试") { vodBrowse(source, filter, pageNumber) }
            background("读取点播源…", { MediaNetwork.loadVod(source, filter, pageNumber) }, ::render)
        } else render(VodPage(available.filter(filter::matches), 1, 1))
    }
    private fun sourceEditor(existing:Source?=null){
        val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(8),dp(22),dp(8))}
        val name=EditText(this).apply{hint="源名称";setSingleLine();setText(existing?.name.orEmpty())}
        val url=EditText(this).apply{hint="http:// 或 https://";setSingleLine();inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI;setText(existing?.url.orEmpty())}
        val kinds = if (existing?.kind == "collection") listOf("collection") else listOf("direct", "direct", "m3u", "cms", "tvbox")
        val labels = if (existing?.kind == "collection") listOf("公开点播片库")
            else listOf("直播直链 / HLS", "点播直链 / HLS 或 MP4", "M3U 直播列表", "苹果 CMS JSON 接口", "TVBox JSON 配置")
        if (existing?.kind == "collection") url.isEnabled = false
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            val current = if (existing?.kind == "direct" && existing.onDemand) 1
                else if (existing == null && playerTab == "点播") 1 else kinds.indexOf(existing?.kind).coerceAtLeast(0)
            setSelection(current)
        }
        form.addView(text("名称",14f));form.addView(name);form.addView(text("源地址",14f));form.addView(url);form.addView(text("源类型",14f));form.addView(spinner)
        activeDialog=AlertDialog.Builder(this).setTitle(if(existing==null)"添加播放源" else "编辑播放源").setView(form).setPositiveButton("保存",null).setNegativeButton("取消",null).create()
        showDialog(activeDialog!!)
        activeDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener{
            try { val address=CatalogParser.networkUrl(url.text.toString());require(name.text.isNotBlank()){"请填写源名称"}
                val kind = kinds[spinner.selectedItemPosition]
                val inline = existing?.takeIf { it.url == address && it.kind == kind }?.inline.orEmpty()
                val section = if (kind == "direct") if (spinner.selectedItemPosition == 1) "vod" else "live" else ""
                val source = Source(existing?.id ?: UUID.randomUUID().toString(), name.text.toString().trim(),
                    address, kind, existing?.enabled ?: true, inline, section)
                val list = store.sources().toMutableList()
                val index = list.indexOfFirst { it.id == source.id }
                if (index < 0) list.add(source) else list[index] = source
                store.saveSources(list)
                activeDialog?.dismiss()
                manageSources()
            }catch(e:Exception){url.error=e.message}
        }
    }
    private fun sourceMenu(source: Source) {
        val options = listOf("编辑地址与类型", if (source.kind == "tvbox") "导入配置" else "检测源",
            if (source.enabled) "停用" else "启用", "删除")
        choices(source.name, options) { index ->
            when (index) {
                0 -> sourceEditor(source)
                1 -> if (source.kind == "tvbox") importRemote(source)
                    else background("正在检测…", { MediaNetwork.probe(source) }) { message(it) }
                2 -> {
                    store.saveSources(store.sources().map { if (it.id == source.id) it.copy(enabled = !it.enabled) else it })
                    manageSources()
                }
                3 -> dialog("删除源", "删除「${source.name}」？", "删除") {
                    store.saveSources(store.sources().filter { it.id != source.id })
                    manageSources()
                }
            }
        }
    }
    private fun browse(s:Source,query:String=""){
        begin("browse",s.name,if(s.kind=="cms")"影视点播 · 支持关键词搜索与分线路选集" else "直播与视频 · 长按条目可收藏")
        val token=generation
        action("搜索 / 筛选"){input("搜索","输入片名或频道名",query){q->handler.post{browse(s,q)}}}
        action("重新加载 / 重试"){browse(s,query)}
        note("正在加载…")
        background("读取源内容…",{MediaNetwork.load(s,query)}){items->
            if(generation!=token)return@background
            (content.getChildAt(content.childCount-1) as? TextView)?.text=if(items.isEmpty())"没有可播放内容。可尝试搜索，或检查是否为JSON CMS接口、媒体直链。" else "${items.size} 项内容"
            items.forEach{entry->
                val b=button(entry.name+(if(entry.group.isNotBlank())"  ·  ${entry.group}" else "")){
                    if(entry.episodes.size==1)play(entry.episodes,0,s.kind=="m3u",items.flatMap{it.episodes}) else episodes(entry)
                }
                content.addView(b,LinearLayout.LayoutParams(-1,dp(52)).apply{topMargin=dp(8)})
                b.setOnLongClickListener{val e=entry.episodes.first();toggleFavorite(e);true}
            }
            action("返回直播"){liveChannels()}
        }
    }
    private fun episodes(entry: MediaEntry) {
        choices(entry.name, entry.episodes.map { it.name }) { index ->
            play(entry.episodes.map { it.copy(name = "${entry.name} · ${it.name}") }, index)
        }
    }
    private fun play(eps:List<Episode>,index:Int,live:Boolean=false,channels:List<Episode> = eps, media: MediaEntry? = null){
        val list=if(live)channels else eps;val selected=if(live)list.indexOfFirst{it.url==eps[index].url}.coerceAtLeast(0) else index
        // Store large channel lists locally instead of exceeding Binder's transaction limit.
        store.prefs.edit()
            .putString("queue", org.json.JSONArray().also { a -> list.forEach {
                a.put(JSONObject().put("name", it.name).put("url", it.url))
            } }.toString())
            .putString("queue_entry", org.json.JSONArray().also { a -> media?.let { a.put(it.json()) } }.toString())
            .putString("queue_live", live.toString()).apply()
        startActivity(Intent(this,PlayerActivity::class.java).putExtra("index",selected).putExtra("live",live))
    }
    private fun toggleFavorite(e:Episode){val key="favorite:${e.url}";store.put(key,e.name);store.toggle("channels",e.url);Toast.makeText(this,if(e.url in store.set("channels"))"已收藏 ${e.name}" else "已取消收藏",Toast.LENGTH_SHORT).show()}
    private fun history() {
        begin("history", "播放历史", "选择记录继续观看")
        val items = store.history()
        if (items.isEmpty()) note("播放后，节目会显示在这里。")
        val actions = row()
        playerButton(actions, "返回首页") { sources() }
        if (items.isNotEmpty()) playerButton(actions, "清空历史") {
            dialog("清空播放历史", "将清除播放记录和续播进度。", "清空") {
                val editor = store.prefs.edit().putString("history", "[]")
                store.history().forEach { editor.remove("position:${it.url}").remove("live:${it.url}") }
                editor.apply()
                history()
            }
        }
        items.forEach { episode -> action(episode.name) { play(listOf(episode), 0, store.wasLive(episode.url)) } }
    }

    private fun favorites() {
        begin("favorites", "我的收藏", "在海报上长按确认，或从详情页添加收藏")
        val entries = store.savedVod()
        val channels = store.set("channels").map { Episode(store.string("favorite:$it", "收藏频道"), it) }
        if (entries.isEmpty() && channels.isEmpty()) note("收藏夹还没有内容。")
        if (entries.isNotEmpty()) {
            cinemaHeading("点播收藏")
            posterGrid(entries, ::favorites)
        }
        if (channels.isNotEmpty()) cinemaHeading("频道收藏")
        channels.forEachIndexed { index, episode ->
            val item = button(episode.name) { play(channels, index, true) }
            SettingsAppearance.row(item)
            content.addView(item, LinearLayout.LayoutParams(-1, dp(51)).apply { topMargin = dp(8) })
            item.setOnLongClickListener { toggleFavorite(episode); favorites(); true }
        }
        action("返回首页") { sources() }
    }
    private fun importRemote(s:Source){background("读取 TVBox 配置…",{CatalogParser.config(MediaNetwork.fetch(s.url))}){applyImport(it)}}
    private fun applyImport(result:ImportResult){
        val existing=store.sources();val additions=result.sources.filter{n->existing.none{it.url==n.url && it.kind==n.kind}}
        dialog("配置预览","可新增 ${additions.size} 个源。\n${additions.joinToString("\n"){it.name+" · "+it.kind}}\n\n${result.warnings.joinToString("\n")}","导入") {store.saveSources(existing+additions);sources()}
    }
    private fun pick(mime:String,code:Int){try{startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),code)}catch(_:Exception){message("此电视没有文件选择器。可安装支持 Storage Access Framework 的文件管理器，或使用 Mac 工具导入播放源。")}}
    private fun createDocument(){try{startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"客厅桌面备份.json"),24)}catch(_:Exception){message("系统没有文件保存器。")}}
    @Deprecated("SAF result") override fun onActivityResult(request:Int,result:Int,data:Intent?){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK)return;val uri=data?.data ?: return
        when(request){
            22->background("读取配置文件…",{contentResolver.openInputStream(uri)?.use{it.readBytesLimited(5*1024*1024).toString(Charsets.UTF_8)} ?: error("无法读取文件")}){body->
                try{if(body.trimStart().startsWith("#EXTM3U")){
                    val channels=CatalogParser.m3u(body,"https://local.invalid/")
                    require(channels.isNotEmpty() && channels.none{it.episodes.first().url.startsWith("https://local.invalid/")}){"本地 M3U 需要绝对 HTTP/HTTPS 频道地址"}
                    val source=Source(UUID.randomUUID().toString(),"导入的直播列表","https://local.invalid/imported.m3u","m3u",true,body)
                    store.saveSources(store.sources()+source);browse(source)
                }else{
                    val j=JSONObject(body)
                    if(j.optString("format")=="livingtv-backup-v1")restoreBackup(j) else applyImport(CatalogParser.config(body))
                }}catch(e:Exception){message("配置无效：${e.message}")}
            }
            24->{try{val backup=JSONObject().put("format","livingtv-backup-v1").put("sources",org.json.JSONArray().also{a->store.sources().forEach{a.put(it.json())}}).put("favorites",org.json.JSONArray(store.order("favorites"))).put("hidden",org.json.JSONArray(store.set("hidden"))).put("appOrder",org.json.JSONArray(store.order("appOrder"))).put("folders",org.json.JSONArray(store.order("folders")))
                val memberships=JSONObject();apps().forEach{a->val f=store.string("folder:${a.pkg}");if(f.isNotBlank())memberships.put(a.pkg,f)};backup.put("memberships",memberships)
                contentResolver.openOutputStream(uri)?.use{it.write(backup.toString(2).toByteArray())} ?: error("无法写入");message("备份已保存。播放源可能包含私人地址，请自行保管。")
            }catch(e:Exception){message("备份失败：${e.message}")}}
        }
    }
    private fun restoreBackup(j:JSONObject){val imported=CatalogParser.config(j.toString());dialog("恢复桌面备份","将替换当前源、桌面排序、文件夹与隐藏设置。壁纸文件需重新选择。","恢复"){
        store.saveSources(imported.sources)
        listOf("favorites","appOrder","folders").forEach{k->val a=j.optJSONArray(k) ?: org.json.JSONArray();store.saveOrder(k,(0 until a.length()).map{a.getString(it)})}
        val hidden=j.optJSONArray("hidden") ?: org.json.JSONArray();store.saveSet("hidden",(0 until hidden.length()).map{hidden.getString(it)}.toSet())
        apps().forEach{store.put("folder:${it.pkg}","")};val m=j.optJSONObject("memberships");m?.keys()?.forEach{k->store.put("folder:$k",m.optString(k))};home()
    }}
    private fun wallpapers(){
        begin("wallpapers", "轮换与屏保", "从壁纸图库选择壁纸后，可在这里设置轮换和屏保时间")
        action("壁纸轮换：${store.string("rotate","0")} 分钟（0为关闭）"){val values=listOf("0","1","5","15","30");choices("轮换间隔（分钟）",values){i->store.put("rotate",values[i]);lastRotation=SystemClock.elapsedRealtime();wallpapers()}}
        action("空闲屏保：${store.string("idle","5")} 分钟（0为关闭）"){val values=listOf("0","1","3","5","10","15");choices("屏保等待时间（分钟）",values){i->store.put("idle",values[i]);wallpapers()}}
        action("立即预览屏保") {
            launch(Intent().setComponent(ComponentName(packageName, "com.gathin.tvhome.ClockActivity")))
        }
        action("移除已导入壁纸") {
            val list = store.order("wallpapers")
            if (list.isEmpty()) {
                message("没有已导入的壁纸")
            } else {
                val names = list.mapIndexed { index, address ->
                    store.string("wallpaper-name:$address").ifBlank { "本地壁纸 ${index + 1}" }
                }
                choices("选择要移除的壁纸", names) { index ->
                    val address = list[index]
                    val uri = Uri.parse(address)
                    store.saveOrder("wallpapers", list - address)
                    store.put("wallpaper-name:$address", "")
                    if (store.string("wallpaper") == address) store.put("wallpaper", "")
                    if (uri.scheme == "content") {
                        runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    } else if (uri.scheme == "file") {
                        runCatching {
                            val copy = File(uri.path.orEmpty()).canonicalFile
                            if (copy.parentFile == File(filesDir, "uploaded_wallpapers").canonicalFile) copy.delete()
                        }
                    }
                    wallpapers()
                }
            }
        }
        action("返回图库"){finish()}
    }
    private fun rotateWallpaper() {
        val selected = store.string("wallpaper")
        val list = PapXWallpapers.rotation(this, selected, store.order("wallpapers"))
        if (list.size < 2) return
        store.put("wallpaper", list[(list.indexOf(selected) + 1) % list.size])
        wallpaperFailed = false
        updateWallpaper()
    }
    private fun stopWallpaper(){wallpaperPlayer?.release();wallpaperPlayer=null;wallpaperUri="";if(::root.isInitialized)root.findViewWithTag<View>("wallpaper")?.let{root.removeView(it)}}
    private fun updateWallpaper(){
        if(page!="home")return
        (root.getChildAt(0) as? LandscapeView)?.let{it.variant=store.string("landscape","0").toIntOrNull() ?: 0;it.invalidate()}
        val address=store.string("wallpaper");if(address==wallpaperUri){wallpaperPlayer?.playWhenReady=foreground;return};if(address.isNotBlank() && wallpaperFailed)return
        stopWallpaper();if(address.isBlank())return
        wallpaperUri=address;val uri=Uri.parse(address)
        try{
            val mime = contentResolver.getType(uri) ?: if (address.endsWith(".mp4")) "video/mp4" else ""
            if(mime.startsWith("video/")){
                val player=ExoPlayer.Builder(this).build();wallpaperPlayer=player
                val pv=PlayerView(this).apply{useController=false;this.player=player;isFocusable=false;tag="wallpaper";importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;resizeMode=androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM}
                root.addView(pv,1,FrameLayout.LayoutParams(-1,-1));player.volume=0f;player.repeatMode=Player.REPEAT_MODE_ONE
                player.addListener(object:Player.Listener{override fun onPlayerError(error:PlaybackException){wallpaperFallback("动态壁纸无法播放，已切换内置背景")}})
                player.setMediaItem(MediaItem.fromUri(uri));player.prepare();player.playWhenReady=foreground
            }else{
                val opts=BitmapFactory.Options().apply{inJustDecodeBounds=true};PapXWallpapers.openImage(this, uri)?.use{BitmapFactory.decodeStream(it,null,opts)}
                require(opts.outWidth>0 && opts.outHeight>0){"图片无法读取"}
                val sample=BitmapFactory.Options().apply{inSampleSize=1;while(opts.outWidth/inSampleSize>2560 || opts.outHeight/inSampleSize>1440)inSampleSize*=2}
                val bitmap=PapXWallpapers.openImage(this, uri)?.use{BitmapFactory.decodeStream(it,null,sample)} ?: error("无法读取图片")
                val image=ImageView(this).apply{tag="wallpaper";setImageBitmap(bitmap);scaleType=ImageView.ScaleType.CENTER_CROP;alpha=.65f;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
                root.addView(image,1,FrameLayout.LayoutParams(-1,-1))
            }
        }catch(_:Exception){wallpaperFallback("壁纸文件不可用，已切换内置背景")}
    }
    private fun wallpaperFallback(reason:String){stopWallpaper();wallpaperFailed=true;Toast.makeText(this,reason,Toast.LENGTH_LONG).show()}
    private fun showSaver(){if(screenSaver!=null)return;val frame=FrameLayout(this);frame.setBackgroundColor(Color.argb(45,0,0,0));val clock=text(SimpleDateFormat("HH:mm",Locale.CHINA).format(Date()),64f).apply{tag="clock";gravity=Gravity.CENTER;setShadowLayer(8f,0f,2f,Color.BLACK)};frame.addView(clock,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));frame.addView(text("按任意键返回桌面",14f).apply{gravity=Gravity.CENTER;setPadding(0,0,0,dp(25))},FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));root.getChildAt(root.childCount-1).visibility=View.INVISIBLE;root.addView(frame,FrameLayout.LayoutParams(-1,-1));screenSaver=frame}
    private fun hideSaver(){val saver=screenSaver ?: return;root.removeView(saver);screenSaver=null;root.getChildAt(root.childCount-1)?.visibility=View.VISIBLE;lastInput=SystemClock.elapsedRealtime()}
}
