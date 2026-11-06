package cn.living.tv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/** Configuration is data only. No script, spider, JAR or HTML execution. */
data class Source(
    val id: String, val name: String, val url: String, val kind: String,
    val enabled: Boolean = true, val inline: String = "", val section: String = ""
) {
    val onDemand: Boolean get() = when (kind) {
        "cms", "collection" -> true
        "direct" -> section == "vod" || (section.isBlank() &&
            !url.substringBefore('?').substringBefore('#').endsWith(".m3u8", true))
        else -> false
    }

    fun json() = JSONObject().put("id", id).put("name", name).put("url", url)
        .put("kind", kind).put("enabled", enabled).put("inline", inline).put("section", section)

    companion object {
        fun from(j: JSONObject) = Source(j.getString("id"), j.getString("name"), j.getString("url"),
            j.getString("kind"), j.optBoolean("enabled", true), j.optString("inline"), j.optString("section"))
    }
}

data class Episode(val name: String, val url: String)
data class MediaEntry(
    val name: String, val episodes: List<Episode>, val group: String = "",
    val category: String = "", val genres: List<String> = emptyList(), val year: String = "",
    val poster: String = "", val area: String = "", val synopsis: String = "", val provider: String = ""
) {
    fun json() = JSONObject().put("name", name).put("category", category).put("genres", genres.joinToString("、"))
        .put("year", year).put("poster", poster).put("area", area).put("synopsis", synopsis).put("provider", provider)
        .put("episodes", JSONArray().also { list ->
            episodes.forEach { list.put(JSONObject().put("name", it.name).put("url", it.url)) }
        })
}

data class VodFilter(val category: String = "", val genre: String = "", val year: String = "", val query: String = "",
                     val region: String = "") {
    fun matches(entry: MediaEntry) =
        (category.isBlank() || entry.category == category) &&
            (genre.isBlank() || entry.genres.any { it.contains(genre) }) &&
            (year.isBlank() || entry.year == year) && CatalogParser.matchesRegion(entry.area, region) &&
            (query.isBlank() || entry.name.contains(query, true))
}

data class VodPage(val entries: List<MediaEntry>, val page: Int, val pages: Int)
data class ImportResult(val sources: List<Source>, val warnings: List<String>)

object CatalogParser {
    fun networkUrl(raw: String): String {
        val value = raw.trim()
        val uri = URI(value)
        require(uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank()) { "请输入有效的 HTTP/HTTPS 地址" }
        require(uri.userInfo == null && !value.contains('\n') && !value.contains('\r')) { "地址格式不受支持" }
        return value
    }
    fun playable(raw: String): Boolean = try {
        val u = URI(networkUrl(raw)); val p = u.path.orEmpty().lowercase()
        listOf(".m3u8", ".mp4", ".mpd", ".ts", ".mkv", ".webm", ".mov").any { p.endsWith(it) }
    } catch (_: Exception) { false }
    fun m3u(text: String, base: String): List<MediaEntry> {
        var title = ""; var group = ""; val out = mutableListOf<MediaEntry>()
        text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXTINF", true) -> {
                    title = line.substringAfter(',', "频道 ${out.size + 1}").trim()
                    group = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1).orEmpty()
                }
                line.isNotBlank() && !line.startsWith('#') -> {
                    val url = try { URI(base).resolve(line).toString().let(::networkUrl) } catch (_: Exception) { "" }
                    if (url.isNotEmpty()) out += MediaEntry(title.ifBlank { "频道 ${out.size + 1}" }, listOf(Episode(title.ifBlank { "播放" }, url)), group)
                    title = ""; group = ""
                }
            }
        }
        return out
    }
    fun cms(text: String): List<MediaEntry> = cmsEntries(JSONObject(text.removePrefix("\uFEFF")))

    fun matchesRegion(area: String, region: String): Boolean {
        if (region.isBlank()) return true
        val mainland = listOf("中国大陆", "大陆", "内地", "中國大陸", "大陸", "China").any { area.contains(it, true) } ||
            area.trim() in listOf("中国", "中國")
        val hongKong = area.contains("香港")
        val taiwan = area.contains("台湾") || area.contains("臺灣") || area.contains("台灣")
        val domestic = mainland || hongKong || taiwan || area.contains("澳门") || area.contains("澳門")
        return when (region) {
            "国产" -> domestic
            "海外" -> !domestic
            "中国大陆" -> mainland
            "中国香港" -> hongKong
            "中国台湾" -> taiwan
            else -> false
        }
    }

    fun category(raw: String): String = when {
        listOf("动漫", "动画", "番剧").any(raw::contains) -> "动画"
        listOf("综艺", "真人秀", "脱口秀", "节目", "访谈").any(raw::contains) -> "综艺"
        listOf("剧集", "连续剧", "电视剧", "短剧", "国产剧", "内地剧", "香港剧", "韩国剧", "马泰剧",
            "日本剧", "欧美剧", "海外剧", "台湾剧", "泰国剧", "港剧", "日剧", "韩剧", "美剧").any(raw::contains) -> "连续剧"
        listOf("电影", "片", "纪录", "喜剧", "恐怖").any(raw::contains) -> "电影"
        else -> ""
    }

    fun cmsEntries(root: JSONObject, types: Map<String, String> = emptyMap()): List<MediaEntry> {
        val list = root.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val j = list.getJSONObject(i); val lines = j.optString("vod_play_url").split("$$$")
            val labels = j.optString("vod_play_from").split("$$$")
            val eps = lines.flatMapIndexed { li, line -> line.split('#').mapNotNull { part ->
                val url = part.substringAfter('$', part).trim()
                if (playable(url)) Episode("${labels.getOrNull(li).orEmpty()} · ${part.substringBefore('$', "播放")}".trim(' ', '·'), url) else null
            } }
            val group = j.optString("type_name").ifBlank { types[j.optString("type_id")].orEmpty() }
            val genres = (j.optString("vod_class") + "," + group).split(',', '，', '/', '|', '、')
                .map(String::trim).filter(String::isNotBlank).distinct()
            val year = j.optString("vod_year").takeIf { it.matches(Regex("[0-9]{4}")) }.orEmpty()
            if (eps.isEmpty()) null else MediaEntry(j.optString("vod_name", "未命名影片"), eps,
                group, category(group).ifBlank { category(j.optString("vod_class")) }, genres, year,
                j.optString("vod_pic"), j.optString("vod_area"), j.optString("vod_blurb").ifBlank {
                    j.optString("vod_content").replace(Regex("<[^>]{1,160}>"), "").take(400)
                })
        }
    }
    fun collection(text: String, savedPlayback: Boolean = false): List<MediaEntry> {
        val list = JSONArray(text)
        return List(list.length()) { list.getJSONObject(it) }.mapNotNull { item ->
            val episodes = item.getJSONArray("episodes")
            val playable = List(episodes.length()) { episodes.getJSONObject(it) }.mapNotNull { episode ->
                val url = episode.getString("url")
                val valid = if (savedPlayback) runCatching { networkUrl(url) }.isSuccess else playable(url)
                if (valid) Episode(episode.getString("name"), url) else null
            }
            if (playable.isEmpty()) null else MediaEntry(
                item.getString("name"), playable, category = item.getString("category"),
                genres = item.optString("genres").split('、').filter(String::isNotBlank),
                year = item.optString("year"), poster = item.optString("poster"), area = item.optString("area"),
                synopsis = item.optString("synopsis"), provider = item.optString("provider")
            )
        }
    }

    fun config(text: String): ImportResult {
        val root = JSONObject(text.removePrefix("\uFEFF")); val results = mutableListOf<Source>(); val warnings = mutableListOf<String>()
        if (root.has("sources")) {
            val arr = root.getJSONArray("sources")
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                try {
                    val kind = j.getString("kind")
                    require(kind in listOf("direct", "m3u", "cms", "tvbox", "collection")) { "不支持的源类型 $kind" }
                    val inline = j.optString("inline")
                    if (kind == "collection") require(collection(inline).isNotEmpty()) { "点播片库没有媒体直链" }
                    results += Source("import-$i-${System.nanoTime()}", j.optString("name", "源 ${i+1}"),
                        networkUrl(j.getString("url")), kind, j.optBoolean("enabled", true), inline, j.optString("section"))
                } catch (e: Exception) { warnings += "第 ${i+1} 项：${e.message}" }
            }
        } else {
            val sites = root.optJSONArray("sites") ?: JSONArray()
            for (i in 0 until sites.length()) {
                val j = sites.getJSONObject(i); val label = j.optString("name", "站点 ${i+1}")
                try {
                    require(j.optInt("type", -1) == 1) { "仅支持 type=1 的 JSON 苹果CMS接口" }
                    results += Source("site-$i-${System.nanoTime()}", label, networkUrl(j.getString("api")), "cms")
                } catch (e: Exception) { warnings += "$label：${e.message}" }
            }
            val lives = root.optJSONArray("lives") ?: JSONArray()
            for (i in 0 until lives.length()) {
                val j = lives.getJSONObject(i)
                try { results += Source("live-$i-${System.nanoTime()}", j.optString("name", "直播 ${i+1}"), networkUrl(j.getString("url")), "m3u") }
                catch (e: Exception) { warnings += "直播 ${i+1}：${e.message}" }
            }
            if (root.has("spider") || root.has("parses")) warnings += "已忽略 JAR / Spider / 网页解析器；不执行远程代码"
        }
        require(root.has("sources") || root.has("sites") || root.has("lives")) { "配置不含可识别的 sources、sites 或 lives" }
        return ImportResult(results, warnings)
    }
}

object MediaNetwork {
    fun probe(source: Source): String {
        if (source.kind == "collection") {
            val items = load(source)
            val sample = items.firstOrNull()?.episodes?.firstOrNull() ?: error("片库没有可播放条目")
            probe(Source(source.id, sample.name, sample.url, "direct"))
            return "片库包含 ${items.size} 项，已读取第一项的媒体响应。"
        }
        if(source.kind!="direct")return "可读取 ${load(source).size} 项内容。实际播放仍取决于媒体地址和设备解码能力。"
        val conn=URL(CatalogParser.networkUrl(source.url)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout=10000;conn.readTimeout=10000
            conn.setRequestProperty("Range","bytes=0-1023")
            require(conn.responseCode in 200..299){"服务器返回 HTTP ${conn.responseCode}"}
            require(!conn.contentType.orEmpty().contains("text/html",true)){"地址返回网页，需要媒体直链"}
            conn.inputStream.use { require(it.read()>=0){"服务器返回空内容"} }
            return "服务器可达，已读取媒体响应。请播放以确认设备解码支持。"
        }finally{conn.disconnect()}
    }

    fun fetch(address: String): String {
        var current = CatalogParser.networkUrl(address)
        repeat(6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10000; conn.readTimeout = 15000; conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "LivingTV/1.0")
                val status = conn.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    current = CatalogParser.networkUrl(URI(current).resolve(conn.getHeaderField("Location") ?: error("重定向缺少地址")).toString())
                } else {
                    require(status in 200..299) { "服务器返回 HTTP $status" }
                    require((conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L) <= 5 * 1024 * 1024) { "配置超过 5 MB" }
                    val bytes = conn.inputStream.use { it.readBytesLimited(5 * 1024 * 1024) }
                    return bytes.toString(Charsets.UTF_8)
                }
            } finally { conn.disconnect() }
        }
        error("重定向次数过多")
    }
    private fun fromProvider(source: Source, entry: MediaEntry): MediaEntry {
        val address = if (entry.poster.isBlank()) "" else runCatching {
            CatalogParser.networkUrl(URI(source.url).resolve(entry.poster).toString())
        }.getOrDefault("")
        return entry.copy(poster = address, provider = source.name)
    }

    fun loadVod(source: Source, filter: VodFilter, page: Int): VodPage {
        if (source.kind != "cms") return VodPage(load(source).filter(filter::matches), 1, 1)
        val sep = if (source.url.contains('?')) "&" else "?"
        val base = source.url + sep
        val types = runCatching {
            val classes = JSONObject(fetch(base + "ac=list").removePrefix("\uFEFF")).optJSONArray("class")
            if (classes == null) emptyMap() else (0 until classes.length())
                .map { classes.getJSONObject(it) }.associate { it.optString("type_id") to it.optString("type_name") }
        }.getOrDefault(emptyMap())
        val matching = types.filterValues { filter.category.isNotBlank() && CatalogParser.category(it) == filter.category }
        val categoryNames = when (filter.category) {
            "电影" -> setOf("电影", "电影片")
            "连续剧" -> setOf("连续剧", "电视剧", "剧集")
            "综艺" -> setOf("综艺", "综艺片")
            "动画" -> setOf("动漫", "动画", "动画片")
            else -> emptySet()
        }
        val genreId = filter.genre.takeIf(String::isNotBlank)?.let { genre ->
            matching.entries.firstOrNull { it.value.contains(genre) }?.key
        }
        val rootId = matching.entries.firstOrNull { it.value in categoryNames }?.key
        val regionalIds = if (filter.category == "连续剧") when (filter.region) {
            "国产" -> matching.filterValues { it in setOf("国产剧", "内地剧", "香港剧", "台湾剧", "港台剧") }.keys.toList()
            "中国大陆" -> matching.filterValues { it in setOf("国产剧", "内地剧") }.keys.toList()
            "中国香港" -> matching.filterValues { it in setOf("香港剧", "港剧") }.keys.toList()
            "中国台湾" -> matching.filterValues { it in setOf("台湾剧", "台剧") }.keys.toList()
            else -> emptyList()
        } else emptyList()
        val ids: List<String?> = when {
            genreId != null -> listOf(genreId)
            regionalIds.isNotEmpty() -> regionalIds
            filter.category == "电影" -> {
                val subgenres = listOf("动作", "喜剧", "剧情", "恐怖")
                val children = subgenres.mapNotNull { genre ->
                    matching.entries.firstOrNull { it.value == genre || it.value == "${genre}片" }?.key
                }
                (listOfNotNull(rootId) + children).distinct().ifEmpty { listOf(null) }
            }
            rootId != null -> listOf(rootId)
            else -> matching.keys.take(4).ifEmpty { listOf(null) }
        }
        fun fetchPage(id: String?): VodPage {
            val url = buildString {
                append(base).append("ac=detail&pg=").append(page)
                if (!id.isNullOrBlank()) append("&t=").append(URLEncoder.encode(id, "UTF-8"))
                if (filter.query.isNotBlank()) append("&wd=").append(URLEncoder.encode(filter.query, "UTF-8"))
            }
            val response = JSONObject(fetch(url).removePrefix("\uFEFF"))
            val totalPages = response.optInt("pagecount", 1).coerceAtLeast(1)
            val entries = if (page > totalPages) emptyList() else CatalogParser.cmsEntries(response, types)
                .map { fromProvider(source, it) }
                .map { if (it.category.isBlank() && id != null) it.copy(category = filter.category) else it }
                .filter(filter.copy(query = "")::matches)
            return VodPage(entries, page, totalPages)
        }
        if (ids.size == 1) return fetchPage(ids.single())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(ids.size.coerceAtMost(5))
        val pages = try {
            pool.invokeAll(ids.map { id -> java.util.concurrent.Callable { fetchPage(id) } },
                12, java.util.concurrent.TimeUnit.SECONDS).mapNotNull { result ->
                    runCatching { result.get() }.getOrNull()
                }
        } finally {
            pool.shutdownNow()
        }
        require(pages.isNotEmpty()) { "片源分类未响应" }
        val entries = pages.flatMap { it.entries }.distinctBy { it.episodes.first().url }
        return VodPage(entries, page, pages.maxOf { it.pages })
    }

    fun load(source: Source, query: String = ""): List<MediaEntry> = when (source.kind) {
        "collection" -> CatalogParser.collection(source.inline)
        "direct" -> listOf(MediaEntry(source.name, listOf(Episode(source.name, CatalogParser.networkUrl(source.url)))))
        "m3u" -> {
            val body = source.inline.ifBlank { fetch(source.url) }
            if (body.contains("#EXT-X-TARGETDURATION") || body.contains("#EXT-X-STREAM-INF")) listOf(MediaEntry(source.name, listOf(Episode(source.name, source.url))))
            else CatalogParser.m3u(body, source.url).filter { query.isBlank() || it.name.contains(query, true) }
        }
        "cms" -> {
            val sep = if (source.url.contains('?')) "&" else "?"
            CatalogParser.cms(fetch(source.url + sep + "ac=detail&wd=" + URLEncoder.encode(query, "UTF-8")))
                .map { fromProvider(source, it) }
        }
        else -> error("TVBox 配置请先检测并导入站点")
    }
}
fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break; require(out.size()+n <= max) { "文件超过 ${max/1024/1024} MB" }; out.write(buffer, 0, n) }
    return out.toByteArray()
}
