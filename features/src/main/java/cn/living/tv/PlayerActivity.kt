package cn.living.tv

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.*
import android.widget.*
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlayerActivity : Activity() {
    private lateinit var store: Store
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private var player: ExoPlayer? = null
    private var queue = emptyList<Episode>()
    private var index = 0
    private var live = false
    private var subtitle: MediaItem.SubtitleConfiguration? = null
    private var savedPosition = 0L
    private val handler=Handler(Looper.getMainLooper())
    private val checkpoint=object:Runnable{override fun run(){save();handler.postDelayed(this,10000)}}
    override fun attachBaseContext(base:android.content.Context){super.attachBaseContext(chineseContext(base))}
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);store=Store(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        live=intent.getBooleanExtra("live",false);index=state?.getInt("index") ?: intent.getIntExtra("index",0)
        try{val a=JSONArray(store.string("queue","[]"));queue=(0 until a.length()).map{val j=a.getJSONObject(it);Episode(j.getString("name"),j.getString("url"))}}catch(_:Exception){}
        if(queue.isEmpty()){finish();return};index=index.coerceIn(0,queue.lastIndex)
        val root=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)};setContentView(root)
        playerView=PlayerView(this).apply{setShowNextButton(false);setShowPreviousButton(false);controllerAutoShow=true;controllerShowTimeoutMs=5000}
        root.addView(playerView,FrameLayout.LayoutParams(-1,-1).apply{bottomMargin=dp(64)})
        status=text("",16f).apply{setPadding(dp(18),dp(10),dp(18),dp(10));setBackgroundColor(0xAA000000.toInt());setShadowLayer(3f,0f,1f,Color.BLACK)}
        root.addView(status,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        val bar=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(6),dp(12),dp(6));setBackgroundColor(Color.rgb(13,23,37))}
        val toolbar=HorizontalScrollView(this).apply{isFillViewport=true;isHorizontalScrollBarEnabled=false}
        toolbar.addView(bar,ViewGroup.LayoutParams(-2,-1))
        root.addView(toolbar,FrameLayout.LayoutParams(-1,dp(64),Gravity.BOTTOM))
        val buttonWidth=maxOf(100,((resources.displayMetrics.widthPixels/resources.displayMetrics.density-24)/7-8).toInt())
        fun add(label:String,run:()->Unit){bar.addView(button(label,run),LinearLayout.LayoutParams(dp(buttonWidth),-1).apply{leftMargin=dp(4);rightMargin=dp(4)})}
        add("返回"){finish()};add(if(live)"上台" else "上集"){switch(-1)};add(if(live)"下台" else "下集"){switch(1)}
        add("收藏") {
            val episode = queue[index]
            val added = if (live) {
                store.put("favorite:${episode.url}", episode.name)
                store.toggle("channels", episode.url)
                episode.url in store.set("channels")
            } else {
                val entry = store.queueEntry()?.takeIf { it.episodes.any { part -> part.url == episode.url } }
                    ?: MediaEntry(episode.name, listOf(episode))
                store.toggleSaved(entry)
            }
            Toast.makeText(this, if (added) "已收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
        }
        add("音轨"){tracks(C.TRACK_TYPE_AUDIO)};add("字幕"){subtitleMenu()};add("重试"){savedPosition=player?.currentPosition ?: savedPosition;release();open()}
        playerView.requestFocus();handler.post(checkpoint)
    }
    override fun onResume(){super.onResume();if(queue.isNotEmpty()&&player==null)open()}
    override fun onPause(){save();release();super.onPause()}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);release();super.onDestroy()}
    override fun onSaveInstanceState(out:Bundle){out.putInt("index",index);super.onSaveInstanceState(out)}
    private fun open(){
        val e=queue[index];store.put("last_index",index.toString());status.text="${e.name}  ·  正在连接…"
        val p=ExoPlayer.Builder(this).build();player=p;playerView.player=p
        p.addListener(object:Player.Listener{
            override fun onPlaybackStateChanged(state:Int){when(state){Player.STATE_READY->{status.text=e.name;handler.postDelayed({if(player===p)status.visibility=View.GONE},3500)};Player.STATE_BUFFERING->{status.visibility=View.VISIBLE;status.text="${e.name}  ·  缓冲中…"};Player.STATE_ENDED->{status.visibility=View.VISIBLE;status.text="${e.name}  ·  播放结束"}}}
            override fun onPlayerError(error:PlaybackException){status.visibility=View.VISIBLE;status.text="播放失败：${error.errorCodeName}\n请选择「重试」、切换条目或返回换源。";Log.w("LivingTVPlayback","Playback error: ${error.errorCodeName}")}
            override fun onRenderedFirstFrame(){Log.i("LivingTVAcceptance","Rendered first video frame")}
        })
        val item=MediaItem.Builder().setUri(e.url).apply{subtitle?.let{setSubtitleConfigurations(listOf(it))}}.build()
        p.setMediaItem(item)
        if(!live){val pos=if(savedPosition>0)savedPosition else store.prefs.getLong("position:${e.url}",0);if(pos>0)p.seekTo(pos)}
        p.prepare();p.playWhenReady=true;status.visibility=View.VISIBLE
    }
    private fun save(){if(queue.isEmpty())return;val p=player ?: return;savedPosition=if(live)0 else p.currentPosition.coerceAtLeast(0);store.record(queue[index],savedPosition,live)}
    private fun release(){playerView.player=null;player?.release();player=null}
    private fun switch(delta:Int){if(queue.size<2){Toast.makeText(this,"没有其他条目",Toast.LENGTH_SHORT).show();return};save();release();index=if(live)(index+delta+queue.size)%queue.size else (index+delta).coerceIn(0,queue.lastIndex);savedPosition=0;subtitle=null;open()}
    override fun dispatchKeyEvent(event:KeyEvent):Boolean{
        if(event.action==KeyEvent.ACTION_DOWN){when(event.keyCode){
            KeyEvent.KEYCODE_CHANNEL_UP->{switch(1);return true};KeyEvent.KEYCODE_CHANNEL_DOWN->{switch(-1);return true}
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE->{player?.let{it.playWhenReady=!it.playWhenReady};return true}
            KeyEvent.KEYCODE_MEDIA_PLAY->{player?.play();return true};KeyEvent.KEYCODE_MEDIA_PAUSE->{player?.pause();return true}
            KeyEvent.KEYCODE_MEDIA_NEXT->{switch(1);return true};KeyEvent.KEYCODE_MEDIA_PREVIOUS->{switch(-1);return true}
        }}
        return super.dispatchKeyEvent(event)
    }
    private fun subtitleMenu(){AlertDialog.Builder(this).setTitle("字幕").setItems(arrayOf("选择内嵌 / 外挂字幕轨","导入 SRT / VTT / ASS 字幕","关闭字幕")){_,i->when(i){0->tracks(C.TRACK_TYPE_TEXT);1->try{startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),31)}catch(_:Exception){Toast.makeText(this,"系统没有文件选择器",Toast.LENGTH_LONG).show()};2->player?.let{it.trackSelectionParameters=it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()}}}.setNegativeButton("取消",null).show()}
    private fun tracks(type:Int){
        val p=player ?: return;val choices=mutableListOf<Pair<Tracks.Group,Int>>();val labels=mutableListOf("自动选择")
        p.currentTracks.groups.filter{it.type==type}.forEach{g->for(i in 0 until g.length){if(g.isTrackSupported(i)){val f=g.getTrackFormat(i);choices+=g to i;labels+="${f.label ?: f.language ?: "轨道"} · ${f.sampleMimeType.orEmpty()} ${if(g.isTrackSelected(i))"（当前）" else ""}"}}}
        if(choices.isEmpty()){Toast.makeText(this,"当前媒体没有可选轨道",Toast.LENGTH_LONG).show();return}
        AlertDialog.Builder(this).setTitle(if(type==C.TRACK_TYPE_AUDIO)"音轨" else "字幕轨").setItems(labels.toTypedArray()){_,i->val builder=p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type,false).clearOverridesOfType(type);if(i>0){val(g,n)=choices[i-1];builder.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup,n))};p.trackSelectionParameters=builder.build()}.setNegativeButton("取消",null).show()
    }
    @Deprecated("SAF result") override fun onActivityResult(request:Int,result:Int,data:Intent?){super.onActivityResult(request,result,data);if(request!=31||result!=RESULT_OK)return;val uri=data?.data ?: return
        try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Exception){}
        val cursor=contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)
        val name=cursor?.use{if(it.moveToFirst())it.getString(0).lowercase() else ""}.orEmpty()
        val mime=when{ name.endsWith(".vtt")->MimeTypes.TEXT_VTT;name.endsWith(".ass")||name.endsWith(".ssa")->MimeTypes.TEXT_SSA;else->MimeTypes.APPLICATION_SUBRIP }
        subtitle=MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).setLabel("本地字幕").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        savedPosition=player?.currentPosition ?: savedPosition;release();open()
    }
}
