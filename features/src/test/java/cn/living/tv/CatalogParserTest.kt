package cn.living.tv
import org.junit.Assert.*
import org.junit.Test

class CatalogParserTest {
    @Test fun m3uHandlesNamesGroupsAndRelativeUris(){val entries=CatalogParser.m3u("#EXTM3U\n#EXTINF:-1 group-title=\"新闻\",频道一\nvideo/live.m3u8\n#EXTINF:-1,频道二\nhttps://example.org/two", "https://example.org/base/list.m3u");assertEquals(2,entries.size);assertEquals("新闻",entries[0].group);assertEquals("https://example.org/base/video/live.m3u8",entries[0].episodes[0].url)}
    @Test fun cmsFiltersWebParsersAndPreservesLines(){val text="""{"list":[{"vod_name":"测试","vod_play_from":"线路一$$${'$'}线路二","vod_play_url":"第一集${'$'}https://example.org/1.m3u8#网页${'$'}https://example.org/player.html$$${'$'}第二集${'$'}https://example.org/2.mp4"}]}""".replace("$$$$","$$$");val list=CatalogParser.cms(text);assertEquals(1,list.size);assertEquals(2,list[0].episodes.size);assertTrue(list[0].episodes[0].name.contains("第一集"))}
    @Test fun movieFiltersUseCmsGenreAndYear() {
        val body = """{"list":[
            {"vod_name":"A","type_id":"3","vod_class":"黑色喜剧，恐怖","vod_year":1960,
                "vod_play_url":"https://example.org/a.mp4"},
            {"vod_name":"B","type_name":"国产剧","vod_class":"喜剧","vod_year":"1960",
                "vod_play_url":"https://example.org/b.mp4"},
            {"vod_name":"C","type_name":"恐怖片","vod_year":"1968","vod_play_url":"https://example.org/c.mp4"}
        ]}"""
        val entries = CatalogParser.cmsEntries(org.json.JSONObject(body), mapOf("3" to "电影"))
        assertEquals(listOf("A"), entries.filter(VodFilter("电影", "恐怖", "1960")::matches).map { it.name })
        assertEquals(listOf("B"), entries.filter(VodFilter("连续剧")::matches).map { it.name })
    }

    @Test fun cmsMovieFilterRequestsSourceGenreAndPage() {
        java.net.ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val requests = mutableListOf<String>()
            val thread = Thread {
                val bodies = listOf(
                    """{"class":[{"type_id":1,"type_name":"电影片"},{"type_id":3,"type_name":"恐怖片"}]}""",
                    """{"pagecount":4,"list":[{"vod_name":"片名","type_id":3,"vod_year":1968,
                        "vod_play_url":"https://example.org/film.mp4"}]}"""
                )
                for (body in bodies) server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    requests += reader.readLine()
                    while (!reader.readLine().isNullOrEmpty()) {}
                    val bytes = body.toByteArray()
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(bytes)
                }
            }.apply { isDaemon = true; start() }
            val source = Source("cms", "测试", "http://127.0.0.1:${server.localPort}/cms", "cms")
            val result = MediaNetwork.loadVod(source, VodFilter("电影", "恐怖", "1968", "片名"), 2)
            thread.join(5000)
            assertTrue(requests[0].contains("ac=list"))
            assertTrue(requests[1].contains("ac=detail&pg=2&t=3&wd="))
            assertEquals(4, result.pages)
            assertEquals(listOf("片名"), result.entries.map { it.name })
        }
    }

    @Test fun regionFiltersUseProviderMetadataAndKeepItInFavorites() {
        val payload = """{"list":[{"vod_name":"国产影片","vod_area":"中国香港","type_name":"喜剧片","vod_year":"2004",
            "vod_pic":"https://example.org/art.jpg","vod_play_url":"https://example.org/film.mp4"}]}"""
        val entry = CatalogParser.cms(payload).single()
        assertTrue(VodFilter("电影", "喜剧", "2004", region = "国产").matches(entry))
        assertFalse(VodFilter(region = "中国大陆").matches(entry))
        assertFalse(VodFilter(region = "海外").matches(entry))
        val saved = CatalogParser.collection(org.json.JSONArray().put(entry.json()).toString(), savedPlayback = true).single()
        assertEquals(entry.area, saved.area)
        assertEquals(entry.poster, saved.poster)
    }

    @Test fun directSourcesSupportExplicitHlsVodAndLegacyLive() {
        val hls = Source("hls", "HLS", "https://example.org/main.m3u8?token=1", "direct")
        assertFalse(hls.onDemand)
        assertTrue(Source.from(hls.copy(section = "vod").json()).onDemand)
        assertTrue(hls.copy(url = "https://example.org/movie.mp4").onDemand)
    }

    @Test fun tvboxRejectsExecutableSites(){val r=CatalogParser.config("""{"spider":"evil.jar","sites":[{"name":"脚本","type":3,"api":"csp_X"},{"name":"CMS","type":1,"api":"https://example.org/api.php"}]}""");assertEquals(1,r.sources.size);assertEquals("cms",r.sources[0].kind);assertEquals(2,r.warnings.size)}
    @Test fun rejectsUnsafeSchemes(){listOf("javascript:alert(1)","file:///etc/passwd","http://user:pass@example.org/a","not a url").forEach{raw->try{CatalogParser.networkUrl(raw);fail(raw)}catch(_:Exception){}}}
    @Test fun supportsExplicitSourceConfig(){val r=CatalogParser.config("""{"sources":[{"name":"频道","kind":"m3u","url":"https://example.org/a.m3u"},{"name":"坏源","kind":"script","url":"https://example.org/x"}]}""");assertEquals(1,r.sources.size);assertEquals(1,r.warnings.size)}
    @Test fun limitsDownloadedConfig(){try{java.io.ByteArrayInputStream(ByteArray(2049)).readBytesLimited(2048);fail()}catch(_:IllegalArgumentException){}}
    @Test fun emptyBackupAndInlinePlaylistsRoundTrip(){
        assertTrue(CatalogParser.config("{\"sources\":[]}").sources.isEmpty())
        val source=Source("local","Local","https://local.invalid/list.m3u","m3u",true,"#EXTM3U\n#EXTINF:-1,Test\nhttps://example.org/stream.m3u8")
        val config=org.json.JSONObject().put("sources",org.json.JSONArray().put(source.json())).toString()
        val restored=CatalogParser.config(config).sources.single()
        assertEquals(source.inline,restored.inline)
        assertEquals(1,MediaNetwork.load(restored).size)
    }
    @Test fun detectsMediaHttpFailureAndHtml(){
        val server=java.net.ServerSocket(0,2,java.net.InetAddress.getByName("127.0.0.1"))
        server.soTimeout=5000
        val thread=Thread {
            repeat(2){server.accept().use{socket->
                val reader=socket.getInputStream().bufferedReader();val request=reader.readLine();while(!reader.readLine().isNullOrEmpty()){}
                val response=if(request.contains("/html"))"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 6\r\nConnection: close\r\n\r\n<html>" else "HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(response.toByteArray())
            }}
        }.apply{isDaemon=true;start()}
        try{for(path in listOf("html","bad")){
            try{MediaNetwork.probe(Source(path,path,"http://127.0.0.1:${server.localPort}/$path","direct"));fail("must reject $path")}catch(_:IllegalArgumentException){}
        }}finally{thread.join(5000);server.close()}

    }

}
