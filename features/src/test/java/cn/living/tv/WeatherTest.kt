package cn.living.tv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*

class WeatherTest {
    private val city=WeatherCity("测试城",30.0,110.0)
    private val now=SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).apply{timeZone=TimeZone.getTimeZone("Asia/Shanghai")}.parse("2026-11-04 19:20")!!.time
    private fun payload(time:String="2026-11-04T19:15",temp:Double=17.7)=JSONObject().put("current",JSONObject().put("time",time).put("temperature_2m",temp).put("weather_code",1).put("is_day",0)).put("daily",JSONObject().put("temperature_2m_min",org.json.JSONArray().put(16.0)).put("temperature_2m_max",org.json.JSONArray().put(19.6))).toString()
    @Test fun networkCityUsesOnlyRequiredFields(){val c=WeatherLogic.city("""{"success":true,"city":"首爾","latitude":37.5,"longitude":127.0} born""".substringBefore(" born"));assertEquals("首尔",c.name);assertTrue(c.automatic)}
    @Test fun networkCityRejectsMissingOrInvalidData(){for(text in listOf("""{"success":false,"message":"rate limit"}""","""{"success":true,"city":"X","latitude":200,"longitude":127}""")){try{WeatherLogic.city(text);fail(text)}catch(_:IllegalArgumentException){}}}
    @Test fun mapsConditionsAndCelsiusForChineseAudience(){val s=WeatherLogic.snapshot(payload(),city,now);assertEquals("18°",s.degrees);assertEquals("最高 20°  最低 16°",s.range);assertEquals("晴间多云",WeatherLogic.description(s.code));assertFalse(s.day);assertEquals("雷阵雨",WeatherLogic.description(95));assertEquals("小雪",WeatherLogic.description(71))}
    @Test fun rejectsStaleOrImpossibleWeather(){for(text in listOf(payload("2020-11-04T19:15"),payload(temp=200.0))){try{WeatherLogic.snapshot(text,city,now);fail()}catch(_:IllegalArgumentException){}}}
    @Test fun cacheDoesNotPresentExpiredOrFutureData(){val s=WeatherLogic.snapshot(payload(),city,now);assertTrue(WeatherLogic.usable(s,now+1000));assertFalse(WeatherLogic.usable(s,now+7*60*60*1000));assertFalse(WeatherLogic.usable(s,now-1000));assertFalse(WeatherLogic.usable(null,now))}
    @Test fun snapshotRoundTripsWithoutIpAddress(){val s=WeatherLogic.snapshot(payload(),city.copy(automatic=false),now);val data=s.json();assertFalse(data.has("ip"));assertEquals(s,WeatherSnapshot.from(data))}
    @Test fun clockUsesBeijingAndChineseRegardlessOfHostLocale(){assertEquals("08:00",chineseTime("HH:mm",Date(0)));assertTrue(chineseTime("EEEE",Date(0)).startsWith("星期"))}
}
