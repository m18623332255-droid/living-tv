package com.gathin.tvhome;
import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
public class ChinaAcceptanceTest extends junit.framework.TestCase {
 final android.app.Instrumentation i=InstrumentationRegistry.getInstrumentation();
 final List<Activity> running=new ArrayList<>();
 List<View> all(View v){List<View> r=new ArrayList<>();r.add(v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int n=0;n<g.getChildCount();n++)r.addAll(all(g.getChildAt(n)));}return r;}
 Activity start(String cls){Activity a=i.startActivitySync(new Intent().setClassName(i.getTargetContext(),cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));running.add(a);i.waitForIdleSync();return a;}
 boolean text(Activity a,String label){for(View v:all(a.getWindow().getDecorView()))if(v instanceof TextView&&((TextView)v).getText().toString().contains(label))return true;return false;}
 protected void setUp(){i.getTargetContext().getSharedPreferences("livingtv",0).edit().clear().putString("weather_enabled","false").commit();}
 protected void tearDown(){for(Activity a:running)i.runOnMainSync(a::finish);i.waitForIdleSync();}
 public void testChineseClockAndRemoteFocus(){
  i.setInTouchMode(false);Activity a=start("com.gathin.tvhome.MainActivity");
  i.runOnMainSync(()->{TextClock clock=a.findViewById(R.id.clock);assertTrue(clock.getTextSize()>=70*a.getResources().getDisplayMetrics().scaledDensity);assertEquals("Asia/Shanghai",clock.getTimeZone());assertEquals("zh",a.getResources().getConfiguration().getLocales().get(0).getLanguage());assertTrue(text(a,"播放器"));assertFalse(text(a,"壁纸与屏保"));assertFalse(text(a,"播放与天气设置"));assertFalse(text(a,"电视原厂设置"));View first=a.findViewById(R.id.card);assertNotNull(first);assertTrue(first.requestFocus());});
  final View[] before=new View[1];i.runOnMainSync(()->before[0]=a.getCurrentFocus());i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);i.waitForIdleSync();i.runOnMainSync(()->{assertNotNull(a.getCurrentFocus());assertNotSame(before[0],a.getCurrentFocus());});
 }
 public void testPlayerTileOpensIntegratedPortal(){
  Activity a=start("com.gathin.tvhome.MainActivity");android.app.Instrumentation.ActivityMonitor monitor=i.addMonitor("cn.living.tv.PlayerPortalActivity",null,false);
  i.runOnMainSync(()->{for(View v:all(a.getWindow().getDecorView()))if(v.getId()==R.id.card&&"播放器".contentEquals(v.getContentDescription())){v.performClick();return;}throw new AssertionError("No player card");});
  Activity portal=i.waitForMonitorWithTimeout(monitor,5000);i.removeMonitor(monitor);assertNotNull(portal);running.add(portal);i.waitForIdleSync();
  i.runOnMainSync(()->{
   assertTrue(text(portal,"客厅影院"));
   for(View v:all(portal.getWindow().getDecorView()))if("设置".equals(String.valueOf(v.getContentDescription()))){v.performClick();return;}
   throw new AssertionError("No player settings shortcut");
  });
  i.waitForIdleSync();i.runOnMainSync(()->assertTrue(text(portal,"添加源")));
  i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);i.waitForIdleSync();
  i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);i.waitForIdleSync();assertTrue(portal.isFinishing());
 }
 public void testWeatherAndFactorySettingsRetained(){
  Activity a=start("cn.living.tv.FeaturesSettingsActivity");i.runOnMainSync(()->{assertTrue(text(a,"电视原厂设置"));assertTrue(text(a,"时间与天气"));});
  assertNotNull(i.getTargetContext().getPackageManager().resolveActivity(new Intent(android.provider.Settings.ACTION_SETTINGS),0));
  Activity weather=start("cn.living.tv.WeatherPortalActivity");i.runOnMainSync(()->assertTrue(text(weather,"网络")));
 }

 public void testControlledMp4AndHlsPlayback() throws Exception {
  for(String path:new String[]{"sample.mp4","stream.m3u8"}){
   i.getTargetContext().getSharedPreferences("livingtv",0).edit().putString("queue","[{\"name\":\"受控测试\",\"url\":\"http://10.0.2.2:18765/"+path+"\"}]").commit();
   Activity a=start("cn.living.tv.PlayerActivity");final boolean[] ready={false};long until=System.currentTimeMillis()+25000;
   while(!ready[0]&&System.currentTimeMillis()<until){i.runOnMainSync(()->{for(View v:all(a.getWindow().getDecorView()))if(v instanceof androidx.media3.ui.PlayerView){androidx.media3.common.Player p=((androidx.media3.ui.PlayerView)v).getPlayer();ready[0]=p!=null&&p.getPlaybackState()==3&&p.getVideoSize().width>0&&p.getCurrentPosition()>500;}});Thread.sleep(100);}
   assertTrue("No decoded video: "+path,ready[0]);i.runOnMainSync(a::finish);i.waitForIdleSync();
  }
 }
 public void testWallpaperDecoderReleasesOutsideHome() throws Exception {
  java.io.File file=new java.io.File(i.getTargetContext().getCacheDir(),"fixture.mp4");
  try(java.io.InputStream in=new java.net.URL("http://10.0.2.2:18765/sample.mp4").openStream();java.io.OutputStream out=new java.io.FileOutputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
  i.getTargetContext().getSharedPreferences("livingtv",0).edit().putString("wallpaper",android.net.Uri.fromFile(file).toString()).commit();
  Activity a=start("com.gathin.tvhome.MainActivity");final boolean[] ready={false};long until=System.currentTimeMillis()+20000;
  while(!ready[0]&&System.currentTimeMillis()<until){i.runOnMainSync(()->{View v=a.getWindow().getDecorView().findViewWithTag("clean-wallpaper");if(v instanceof androidx.media3.ui.PlayerView){androidx.media3.common.Player p=((androidx.media3.ui.PlayerView)v).getPlayer();ready[0]=p!=null&&p.getVideoSize().width>0&&p.getCurrentPosition()>300;}});Thread.sleep(100);}
  assertTrue("Wallpaper never decoded",ready[0]);start("cn.living.tv.WeatherPortalActivity");i.runOnMainSync(()->assertNull(a.getWindow().getDecorView().findViewWithTag("clean-wallpaper")));
 }

 java.util.List<android.view.accessibility.AccessibilityNodeInfo> activeNodes(){
  java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes=new java.util.ArrayList<>();android.view.accessibility.AccessibilityNodeInfo root=i.getUiAutomation().getRootInActiveWindow();if(root!=null)walk(root,nodes);return nodes;
 }
 void walk(android.view.accessibility.AccessibilityNodeInfo n,java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes){nodes.add(n);for(int k=0;k<n.getChildCount();k++){android.view.accessibility.AccessibilityNodeInfo child=n.getChild(k);if(child!=null)walk(child,nodes);}}
 void openControls(Activity a){i.getUiAutomation();i.runOnMainSync(()->{a.findViewById(R.id.btnSettings).requestFocus();a.findViewById(R.id.btnSettings).performClick();});i.waitForIdleSync();try{i.getUiAutomation().waitForIdle(150,5000);}catch(java.util.concurrent.TimeoutException e){throw new AssertionError("Control window did not settle",e);}}
 boolean panelText(String text){for(android.view.accessibility.AccessibilityNodeInfo n:activeNodes())if(n.getText()!=null&&n.getText().toString().contains(text))return true;return false;}
 public void testControlCenterReferenceLayoutAndBack(){
  Activity a=start("com.gathin.tvhome.MainActivity");openControls(a);
  for(String label:new String[]{"控制中心","无线网络","蓝牙","客厅播放器","未在播放","投屏","原厂设置","桌面设置","桌面亮度","媒体音量"})assertTrue("Missing control: "+label,panelText(label));
  i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);i.waitForIdleSync();assertFalse(panelText("控制中心"));
  i.runOnMainSync(()->assertEquals(R.id.btnSettings,a.getCurrentFocus().getId()));
 }
 public void testControlCenterBrightnessAndVolume() throws Exception {
  android.content.SharedPreferences prefs=i.getTargetContext().getSharedPreferences("livingtv",0);prefs.edit().putString("desktop_brightness","80").commit();
  Activity a=start("com.gathin.tvhome.MainActivity");openControls(a);
  android.view.accessibility.AccessibilityNodeInfo brightness=null;for(android.view.accessibility.AccessibilityNodeInfo n:activeNodes())if("桌面亮度".equals(String.valueOf(n.getContentDescription())))brightness=n;
  assertNotNull(brightness);assertTrue(brightness.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS));i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);i.waitForIdleSync();assertEquals("75",prefs.getString("desktop_brightness",""));
  android.media.AudioManager audio=(android.media.AudioManager)i.getTargetContext().getSystemService(android.content.Context.AUDIO_SERVICE);int before=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
  if(!audio.isVolumeFixed()){
   android.view.accessibility.AccessibilityNodeInfo volume=null;for(android.view.accessibility.AccessibilityNodeInfo n:activeNodes())if("媒体音量".equals(String.valueOf(n.getContentDescription())))volume=n;
   assertNotNull(volume);assertTrue(volume.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS));int key=before>0?KeyEvent.KEYCODE_DPAD_LEFT:KeyEvent.KEYCODE_DPAD_RIGHT;i.sendKeyDownUpSync(key);i.waitForIdleSync();assertTrue(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)!=before);audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,before,0);
  }
  i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);i.waitForIdleSync();assertEquals("75",prefs.getString("desktop_brightness",""));
 }

 public void testControlCenterFactoryShortcut() throws Exception {
  Activity a=start("com.gathin.tvhome.MainActivity");openControls(a);android.view.accessibility.AccessibilityNodeInfo target=null;
  for(android.view.accessibility.AccessibilityNodeInfo node:activeNodes())if("原厂设置".equals(String.valueOf(node.getContentDescription())))target=node;
  assertNotNull(target);assertTrue(target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
  boolean opened=false;long until=System.currentTimeMillis()+5000;
  while(!opened&&System.currentTimeMillis()<until){for(android.view.accessibility.AccessibilityNodeInfo node:activeNodes())if("com.android.tv.settings".equals(String.valueOf(node.getPackageName())))opened=true;if(!opened)Thread.sleep(100);}
  assertTrue("Factory settings not opened",opened);assertTrue(i.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));i.waitForIdleSync();
 }
 public void testControlCenterNextStartsRealPlayer() throws Exception {
  android.content.SharedPreferences prefs=i.getTargetContext().getSharedPreferences("livingtv",0);
  prefs.edit().putString("queue","[{\"name\":\"第一项\",\"url\":\"http://10.0.2.2:18765/sample.mp4\"},{\"name\":\"第二项\",\"url\":\"http://10.0.2.2:18765/stream.m3u8\"}]").putString("last_index","0").commit();
  Activity a=start("com.gathin.tvhome.MainActivity");openControls(a);assertTrue(panelText("继续播放"));
  android.app.Instrumentation.ActivityMonitor monitor=i.addMonitor("cn.living.tv.PlayerActivity",null,false);android.view.accessibility.AccessibilityNodeInfo target=null;
  for(android.view.accessibility.AccessibilityNodeInfo node:activeNodes())if("下一项".equals(String.valueOf(node.getContentDescription())))target=node;
  assertNotNull(target);assertTrue(target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));Activity player=i.waitForMonitorWithTimeout(monitor,5000);i.removeMonitor(monitor);assertNotNull(player);running.add(player);i.waitForIdleSync();assertEquals("1",prefs.getString("last_index",""));
 }
}
