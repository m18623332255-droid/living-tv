package cn.living.tv

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.UUID

/** A short-lived HTTP receiver for uploads or a single search keyword. */
internal class LanInputServer(
    private val context: Context,
    private val onReady: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onFile: ((Uri, String) -> Unit)? = null,
    private val onSearch: ((String) -> Unit)? = null
) : AutoCloseable {
    init { require((onFile == null) != (onSearch == null)) }
    private val kind = if (onSearch != null) "search" else "wallpaper"
    private val path = "/$kind/" + ByteArray(16).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    @Volatile private var closed = false
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var client: Socket? = null

    fun start() {
        Thread(::serve, "lan-input").apply { isDaemon = true }.start()
    }

    override fun close() {
        closed = true
        runCatching { listener?.close() }
        runCatching { client?.close() }
    }

    private fun serve() {
        try {
            val emulator = Build.HARDWARE in setOf("ranchu", "goldfish")
            val address = if (emulator) InetAddress.getByName("127.0.0.1") else lanAddress()
                ?: error("请先把电视连接到手机所在的局域网")
            ServerSocket().use { server ->
                listener = server
                server.reuseAddress = true
                server.bind(InetSocketAddress(address, if (emulator) 18766 else 0), 4)
                server.soTimeout = 1000
                if (closed) return
                val endpoint = if (emulator) emulatorEndpoint() else "${address.hostAddress}:${server.localPort}"
                onReady("http://$endpoint$path")
                val deadline = SystemClock.elapsedRealtime() + if (onSearch == null) 10 * 60_000 else 5 * 60_000
                while (!closed && SystemClock.elapsedRealtime() < deadline) {
                    val socket = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                    client = socket
                    socket.use {
                        if (!closed && SystemClock.elapsedRealtime() < deadline) runCatching { handle(it) }
                    }
                    client = null
                }
                if (!closed) onError("二维码已过期，请关闭后重新打开")
            }
        } catch (error: Exception) {
            if (!closed) onError(error.message ?: "暂时无法创建扫码链接")
        } finally {
            close()
        }
    }

    private fun lanAddress(): Inet4Address? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.asSequence().filter { network ->
            val caps = manager.getNetworkCapabilities(network)
            caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }.flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
            .mapNotNull { it.address as? Inet4Address }
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    }

    private fun emulatorEndpoint(): String {
        val connection = URL("http://10.0.2.2:18767/address?session=${path.substringAfterLast('/')}&kind=$kind").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 2000
            connection.readTimeout = 3000
            val endpoint = connection.inputStream.bufferedReader().use { it.readLine().orEmpty() }
            require(endpoint.matches(Regex("[0-9.]+:[0-9]{1,5}")))
            endpoint
        } catch (_: Exception) {
            error("请从 Mac 打开「电视体验」并连接同一局域网后重试")
        } finally {
            connection.disconnect()
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 8000
        val input = BufferedInputStream(socket.getInputStream())
        val request = line(input).split(' ')
        if (request.size != 3) return respond(socket, "400 Bad Request", "请求无效")
        if (request[1] != path) return respond(socket, "404 Not Found", "二维码链接无效")
        val headers = mutableMapOf<String, String>()
        var headerSize = 0
        while (true) {
            val header = line(input)
            if (header.isEmpty()) break
            headerSize += header.length
            if (headerSize > 8192) return respond(socket, "431 Request Header Fields Too Large", "请求过长")
            val separator = header.indexOf(':')
            if (separator > 0) headers[header.substring(0, separator).lowercase()] = header.substring(separator + 1).trim()
        }
        when (request[0]) {
            "GET" -> respond(socket, "200 OK", if (onSearch == null) PAGE else SEARCH_PAGE, "text/html")
            "POST" -> if (onSearch == null) receive(socket, input, headers) else receiveSearch(socket, input, headers)
            else -> respond(socket, "405 Method Not Allowed", "请求方式不支持")
        }
    }

    private fun receiveSearch(socket: Socket, input: BufferedInputStream, headers: Map<String, String>) {
        val size = headers["content-length"]?.toIntOrNull()
        if (headers["x-livingtv-search"] != "1" || headers["content-type"]?.substringBefore(';') != "text/plain" ||
            size == null || size !in 1..480 || "transfer-encoding" in headers) {
            return respond(socket, "400 Bad Request", "请输入 1 至 120 个字符")
        }
        val bytes = ByteArray(size)
        java.io.DataInputStream(input).readFully(bytes)
        val keyword = bytes.toString(Charsets.UTF_8).trim()
        if (keyword.isEmpty() || keyword.length > 120 || keyword.any(Char::isISOControl)) {
            return respond(socket, "422 Unprocessable Content", "请输入有效片名或关键词")
        }
        if (closed) return
        respond(socket, "200 OK", "已发送，电视正在搜索")
        close()
        checkNotNull(onSearch).invoke(keyword)
    }

    private fun receive(socket: Socket, input: BufferedInputStream, headers: Map<String, String>) {
        val extension = when (headers["content-type"]?.substringBefore(';')?.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "video/mp4" -> "mp4"
            else -> return respond(socket, "415 Unsupported Media Type", "请选择 JPG、PNG、WebP 或 MP4 文件")
        }
        val size = headers["content-length"]?.toLongOrNull()
        val limit = if (extension == "mp4") 150L * 1024 * 1024 else 40L * 1024 * 1024
        if (size == null || size <= 0 || size > limit || headers.containsKey("transfer-encoding")) {
            return respond(socket, "413 Content Too Large", "图片最大 40 MB，视频最大 150 MB")
        }
        val folder = File(context.filesDir, "uploaded_wallpapers")
        if (!folder.isDirectory && !folder.mkdirs()) return respond(socket, "500 Internal Server Error", "无法创建壁纸目录")
        val name = UUID.randomUUID().toString()
        val temporary = File(folder, "$name.part")
        val file = File(folder, "$name.$extension")
        try {
            temporary.outputStream().use { output ->
                var remaining = size
                val buffer = ByteArray(64 * 1024)
                val deadline = SystemClock.elapsedRealtime() + 120_000
                while (remaining > 0) {
                    if (closed || SystemClock.elapsedRealtime() > deadline) throw IOException("上传已中断")
                    val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (count < 0) throw IOException("文件未传输完整")
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
            require(validMedia(temporary, extension)) { "电视无法读取这个文件" }
            if (closed || !temporary.renameTo(file)) throw IOException("保存失败")
            val label = runCatching {
                java.net.URLDecoder.decode(headers["x-wallpaper-name"].orEmpty(), "UTF-8")
                    .replace(Regex("[\\p{Cntrl}]"), " ").trim().take(80)
            }.getOrDefault("")
            checkNotNull(onFile).invoke(Uri.fromFile(file), label)
            respond(socket, "201 Created", "已加入电视壁纸图库")
        } catch (error: Exception) {
            respond(socket, "422 Unprocessable Content", error.message ?: "上传失败，请重试")
        } finally {
            temporary.delete()
        }
    }

    private fun validMedia(file: File, extension: String): Boolean {
        if (extension != "mp4") {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val mime = mapOf("jpg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp")
            return bounds.outMimeType == mime[extension] && bounds.outWidth in 1..16000 && bounds.outHeight in 1..16000
        }
        val metadata = MediaMetadataRetriever()
        return try {
            metadata.setDataSource(file.path)
            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) == "video/mp4" &&
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        } finally {
            metadata.release()
        }
    }

    private fun line(input: BufferedInputStream): String {
        val line = StringBuilder()
        while (line.length < 4096) {
            val character = input.read()
            if (character == -1) throw IOException("连接已关闭")
            if (character == 10) return line.toString().trimEnd('\r')
            line.append(character.toChar())
        }
        throw IOException("请求过长")
    }

    private fun respond(socket: Socket, status: String, body: String, type: String = "text/plain") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status\r\nContent-Type: $type; charset=utf-8\r\n" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }

    private companion object {
        val SEARCH_PAGE = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>在电视上搜索</title><style>
            body{font:17px system-ui,sans-serif;background:#0c1627;color:#f3f7ff;margin:0;padding:28px}main{max-width:460px;margin:12vh auto}
            p{color:#bdcadd;line-height:1.6}input,button{font:inherit;box-sizing:border-box;border:0;border-radius:14px;padding:14px;width:100%}
            input{background:#f5f8ff;color:#102035;margin:10px 0 16px}button{background:#cce3ff;color:#102035;font-weight:700}
            button:disabled{opacity:.55}</style><main><h1>在电视上搜索</h1><p>输入片名或关键词，结果会出现在电视上。</p>
            <form><label for="keyword">片名或关键词</label><input id="keyword" maxlength="120" enterkeyhint="search" required><button>发送到电视</button></form>
            <p id="status" role="status"></p><script>
            const form=document.querySelector('form'),btn=form.querySelector('button'),msg=document.getElementById('status');
            form.onsubmit=async e=>{e.preventDefault();const q=document.getElementById('keyword').value.trim();if(!q)return;
            btn.disabled=true;msg.textContent='正在发送…';try{const r=await fetch(location.pathname,{method:'POST',
            headers:{'Content-Type':'text/plain; charset=utf-8','X-LivingTV-Search':'1'},body:q});msg.textContent=await r.text();if(!r.ok)btn.disabled=false}
            catch(e){msg.textContent='连接中断，请在电视上重新打开扫码搜索。';btn.disabled=false}};</script></main></html>""".trimIndent()

        val PAGE = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>上传壁纸 · 客厅桌面</title><style>
            body{font-family:system-ui,sans-serif;background:#101624;color:#f2f5fa;padding:28px;margin:0}main{max-width:480px;margin:10vh auto}h1{font-size:32px}p{color:#bac6da;line-height:1.6}button,input{font:inherit}input{width:100%;margin:18px 0}button{border:0;border-radius:16px;padding:14px 22px;background:#ebf3ff;color:#13233b;font-weight:700}button:disabled{opacity:.5}
            </style><main><h1>上传到电视</h1><p>JPG、PNG、WebP 图片不超过 40 MB，MP4 视频不超过 150 MB。上传完成后可在电视图库中预览。</p>
            <form id="form"><input id="file" type="file" accept="image/jpeg,image/png,image/webp,video/mp4" required><button id="send">上传壁纸</button></form><p id="status" role="status"></p>
            <script>const form=document.getElementById('form'),send=document.getElementById('send'),file=document.getElementById('file'),status=document.getElementById('status');
            form.onsubmit=async e=>{e.preventDefault();const f=file.files[0];if(!f)return;send.disabled=true;status.textContent='正在上传，请保持电视上的二维码页面打开…';
            try{const type=f.type||({'jpg':'image/jpeg','jpeg':'image/jpeg','png':'image/png','webp':'image/webp','mp4':'video/mp4'})[f.name.split('.').pop().toLowerCase()]||'';
            const r=await fetch(location.pathname,{method:'POST',headers:{'Content-Type':type,'X-Wallpaper-Name':encodeURIComponent(f.name)},body:f});status.textContent=await r.text();if(r.ok)form.reset();}catch(e){status.textContent='连接中断，请确认手机和电视在同一局域网后重试。';}finally{send.disabled=false;}};</script></main></html>""".trimIndent()
    }
}
