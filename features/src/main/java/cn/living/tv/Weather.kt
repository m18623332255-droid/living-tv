package cn.living.tv

import android.content.Context
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

data class WeatherCity(val name:String,val latitude:Double,val longitude:Double,val automatic:Boolean=true) {
    fun json()=JSONObject().put("name",name).put("latitude",latitude).put("longitude",longitude).put("automatic",automatic)
    companion object {fun from(j:JSONObject)=WeatherCity(j.getString("name"),j.getDouble("latitude"),j.getDouble("longitude"),j.optBoolean("automatic",true))}
}
data class WeatherSnapshot(val city:WeatherCity,val temperature:Double,val low:Double,val high:Double,val code:Int,val day:Boolean,val observed:String,val fetchedAt:Long) {
    fun json()=JSONObject().put("city",city.json()).put("temperature",temperature).put("low",low).put("high",high).put("code",code).put("day",day).put("observed",observed).put("fetchedAt",fetchedAt)
    val degrees get()="${temperature.roundToInt()}°"
    val range get()="最高 ${high.roundToInt()}°  最低 ${low.roundToInt()}°"
    companion object {fun from(j:JSONObject)=WeatherSnapshot(WeatherCity.from(j.getJSONObject("city")),j.getDouble("temperature"),j.getDouble("low"),j.getDouble("high"),j.getInt("code"),j.getBoolean("day"),j.getString("observed"),j.getLong("fetchedAt"))}
}
object WeatherLogic {
    fun description(code:Int)=when(code){0->"晴";1->"晴间多云";2->"多云";3->"阴";45,48->"雾";51,53,55->"毛毛雨";56,57,66,67->"冻雨";61->"小雨";63->"中雨";65->"大雨";71->"小雪";73->"中雪";75,77->"大雪";80,81,82->"阵雨";85,86->"阵雪";95,96,99->"雷阵雨";else->"天气信息"}
    fun city(text:String):WeatherCity {
        val j=JSONObject(text);require(j.optBoolean("success")){"暂时无法识别网络城市"}
        val lat=j.getDouble("latitude");val lon=j.getDouble("longitude")
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0){"网络城市数据无效"}
        val name=j.optString("city").replace("首爾","首尔").replace("東京","东京").replace("臺","台")
        require(name.isNotBlank()){"暂时无法识别网络城市"}
        return WeatherCity(name,lat,lon)
    }
    fun snapshot(text:String,city:WeatherCity,now:Long=System.currentTimeMillis()):WeatherSnapshot {
        val j=JSONObject(text);val c=j.getJSONObject("current");val d=j.getJSONObject("daily")
        val temperature=c.getDouble("temperature_2m");val low=d.getJSONArray("temperature_2m_min").getDouble(0);val high=d.getJSONArray("temperature_2m_max").getDouble(0)
        require(listOf(temperature,low,high).all{it.isFinite() && it in -100.0..80.0} && low<=high){"天气温度数据无效"}
        val observed=c.getString("time")
        val format=SimpleDateFormat("yyyy-MM-dd'T'HH:mm",Locale.CHINA).apply{timeZone=TimeZone.getTimeZone("Asia/Shanghai");isLenient=false}
        val time=format.parse(observed)?.time ?: error("天气时间无效")
        require(now-time in -30*60*1000L..3*60*60*1000L){"天气源数据暂未更新"}
        return WeatherSnapshot(city,temperature,low,high,c.getInt("weather_code"),c.optInt("is_day",1)==1,observed,now)
    }
    fun usable(s:WeatherSnapshot?,now:Long=System.currentTimeMillis())=s!=null && now-s.fetchedAt in 0..6*60*60*1000L
}
class WeatherRepository(context:Context) {
    private val store=Store(context)
    fun enabled()=store.string("weather_enabled","true")=="true"
    fun cached():WeatherSnapshot?=try{WeatherSnapshot.from(JSONObject(store.string("weather_snapshot")))}catch(_:Exception){null}
    fun manual():WeatherCity?=try{WeatherCity.from(JSONObject(store.string("weather_manual_city")))}catch(_:Exception){null}
    fun setManual(city:WeatherCity?){store.put("weather_manual_city",city?.copy(automatic=false)?.json()?.toString().orEmpty());store.put("weather_snapshot","")}
    fun refresh(force:Boolean=false):WeatherSnapshot {
        check(enabled()){"天气已关闭"}
        val now=System.currentTimeMillis()
        val current=cached()
        if(!force && current!=null && now-current.fetchedAt in 0..30*60*1000L)return current
        val city=manual() ?: run{
            val cachedCity=try{WeatherCity.from(JSONObject(store.string("weather_network_city")))}catch(_:Exception){null}
            val age=now-store.prefs.getLong("weather_city_time",0)
            if(!force && cachedCity!=null && age in 0..60*60*1000L)cachedCity else {
                val resolved=WeatherLogic.city(MediaNetwork.fetch("https://ipwho.is/?lang=zh-CN&fields=success,message,country_code,city,latitude,longitude"))
                store.put("weather_network_city",resolved.json().toString());store.prefs.edit().putLong("weather_city_time",now).apply();resolved
            }
        }
        val url="https://api.open-meteo.com/v1/forecast?latitude=${city.latitude}&longitude=${city.longitude}&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&temperature_unit=celsius&timezone=Asia%2FShanghai&forecast_days=1"
        return WeatherLogic.snapshot(MediaNetwork.fetch(url),city,now).also{store.put("weather_snapshot",it.json().toString())}
    }
    fun search(name:String):List<WeatherCity>{
        val text=MediaNetwork.fetch("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(name,"UTF-8")}&count=8&language=zh&format=json")
        val a=JSONObject(text).optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).map{i->val j=a.getJSONObject(i);WeatherCity(j.getString("name")+j.optString("admin1").let{if(it.isBlank()||it==j.optString("name"))"" else " · $it"},j.getDouble("latitude"),j.getDouble("longitude"),false)}
    }
}
fun chineseTime(format:String,now:Date=Date())=SimpleDateFormat(format,Locale.SIMPLIFIED_CHINESE).apply{timeZone=TimeZone.getTimeZone("Asia/Shanghai")}.format(now)
fun chineseContext(base:Context):Context {
    val config=android.content.res.Configuration(base.resources.configuration)
    config.setLocale(Locale.SIMPLIFIED_CHINESE);config.setLayoutDirection(Locale.SIMPLIFIED_CHINESE)
    return base.createConfigurationContext(config)
}
