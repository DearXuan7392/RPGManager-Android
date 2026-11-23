package com.dearxuan.rpgmanager.server

import android.content.Context
import android.util.Log
import com.dearxuan.rpgmanager.core.DocInfo
import com.dearxuan.rpgmanager.core.RpgCrypto
import com.dearxuan.rpgmanager.core.SafRepo
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 「WebView 模式」的资源服务：把 SAF 目录里的游戏以 http://127.0.0.1:port/g/ 暴露给 WebView。
 *
 * 这是不启用内嵌 Node 时走的路径：游戏跑成「网页版」，没有 require/process，
 * 存档进 IndexedDB，与桌面端不互通 —— 换来的是更轻的启动和更强的兼容性兜底
 * （个别游戏的检测逻辑只认浏览器环境）。
 *
 * 资源仍然需要一个 http 源：MV/MZ 大量用 XMLHttpRequest 读 data/json、img、audio，
 * file:// 和 content:// 都会被同源策略或解码限制挡住。
 *
 * 端口固定，保证 origin 稳定 —— 否则 localStorage / IndexedDB（网页版存档）会随端口变化而丢失。
 */
class GameServer(private val context: Context, private val repo: SafRepo) {

    companion object {
        private const val TAG = "GameServer"
        // 网页版存档按 origin（协议+主机+端口）隔离，端口必须固定。
        // 18666 专门留给本模式；内嵌 Node 从 18667 起，见 NodeRuntime。
        const val PREFERRED_PORT = 18666
        private const val PORT_TRIES = 1
        private const val INJECT_PATH = "/__rpg/cheat.js"
    }

    @Volatile private var serverSocket: ServerSocket? = null
    private var pool: ExecutorService? = null
    private var acceptThread: Thread? = null

    @Volatile
    var port: Int = 0
        private set

    /** 当前要服务的游戏根目录 document id */
    @Volatile
    var gameDocId: String? = null

    /** 是否显示游戏内的作弊悬浮球（关掉也仍然注入脚本，虚拟按键依赖它） */
    @Volatile
    var cheatUiEnabled: Boolean = true

    @Volatile private var injectJs: ByteArray? = null

    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun start(): Boolean {
        if (serverSocket != null) return true

        val loopback = InetAddress.getByName("127.0.0.1")
        var socket: ServerSocket? = null
        var boundPort = 0

        for (i in 0 until PORT_TRIES) {
            val candidate = PREFERRED_PORT + i
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(loopback, candidate), 64)
                socket = s
                boundPort = candidate
                break
            } catch (e: Exception) {
                Log.w(TAG, "端口 $candidate 不可用: ${e.message}")
            }
        }
        if (socket == null) {
            try {
                val s = ServerSocket(0, 64, loopback)
                socket = s
                boundPort = s.localPort
            } catch (e: Exception) {
                Log.e(TAG, "无法启动本地服务", e)
                return false
            }
        }

        serverSocket = socket
        port = boundPort
        // keep-alive 的连接会长期占住工作线程，浏览器默认会开 6 条连接，
        // 这里用可缓存线程池，避免固定线程数被占满后出现「请求卡住」。
        val executor = Executors.newCachedThreadPool { r ->
            Thread(r, "rpg-http-worker").apply { isDaemon = true }
        }
        pool = executor

        acceptThread = thread(name = "rpg-http-accept", isDaemon = true) {
            while (true) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                try {
                    executor.execute { handle(client) }
                } catch (e: Exception) {
                    try { client.close() } catch (ignored: Exception) { }
                }
            }
        }

        Log.i(TAG, "本地服务已启动: $baseUrl")
        return true
    }

    fun stop() {
        try { serverSocket?.close() } catch (e: Exception) { }
        serverSocket = null
        pool?.shutdownNow()
        pool = null
        acceptThread = null
    }

    // =====================================================================
    // 连接处理
    // =====================================================================

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

            while (true) {
                val requestLine = readLine(input) ?: return
                if (requestLine.isEmpty()) continue

                val firstSpace = requestLine.indexOf(' ')
                if (firstSpace <= 0) return
                val method = requestLine.substring(0, firstSpace)
                val rest = requestLine.substring(firstSpace + 1)
                val secondSpace = rest.indexOf(' ')
                val target = if (secondSpace > 0) rest.substring(0, secondSpace) else rest

                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                }

                val keepAlive = headers["connection"]?.contains("close", true) != true
                serve(method, target, headers, output)
                output.flush()
                if (!keepAlive) return
            }
        } catch (e: Exception) {
            // 客户端断开等常见情况，忽略
        } finally {
            try { socket.close() } catch (e: Exception) { }
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
            if (buffer.size() > 16 * 1024) return null
        }
        return buffer.toString("ISO-8859-1")
    }

    private fun serve(
        method: String,
        target: String,
        headers: Map<String, String>,
        out: OutputStream
    ) {
        val head = method.equals("HEAD", true)
        val rawPath = target.substringBefore('?')
        val path = try {
            // URLDecoder 会把 '+' 当成空格，路径里的加号需要先转义
            URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            rawPath
        }

        when {
            path == INJECT_PATH -> {
                writeBytes(out, "application/javascript; charset=utf-8", loadInjectJs(), head)
            }

            path == "/__rpg/ping" -> {
                writeBytes(out, "text/plain; charset=utf-8", "ok".toByteArray(), head)
            }

            !path.startsWith("/g/") -> {
                writeStatus(out, 404, "text/plain; charset=utf-8", head)
            }

            else -> {
                var rel = path.removePrefix("/g/")
                if (rel.isEmpty() || rel.endsWith("/")) rel += "index.html"
                serveGameFile(out, rel, headers["range"], head)
            }
        }
    }

    private fun serveGameFile(out: OutputStream, relPath: String, range: String?, head: Boolean) {
        val root = gameDocId
        if (root == null) {
            writeStatus(out, 404, "text/plain; charset=utf-8", head)
            return
        }

        var doc = resolveWithFallback(root, relPath)
        if (doc == null) {
            // 大小写/目录索引兜底
            doc = resolveWithFallback(root, relPath.trimEnd('/').replace("//", "/"))
        }
        if (doc == null) {
            Log.w(TAG, "找不到资源: $relPath")
            writeStatus(out, 404, "text/plain; charset=utf-8", head)
            return
        }

        if (doc.isDir) {
            val index = repo.child(doc.docId, "index.html")
            if (index == null) {
                writeStatus(out, 403, "text/plain; charset=utf-8", head)
                return
            }
            doc = index
        }

        val mime = mimeOf(doc.name)

        if (doc.name.endsWith(".html", true) || doc.name.endsWith(".htm", true)) {
            val raw = repo.readBytes(doc.docId, 32L * 1024 * 1024)
            if (raw == null) {
                writeStatus(out, 500, "text/plain; charset=utf-8", head)
                return
            }
            val html = injectHtml(decodeHtml(raw))
            writeBytes(out, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8), head)
            return
        }

        // 请求的是明文名（MV 被切成明文模式），说明页面不会自己解密 → 服务端解密后下发；
        // 请求本身带加密后缀（MZ 的原生行为），说明页面会自行 XOR 解密 → 原样给密文，
        // 只在后缀对不上时（例如移动端要 .m4a_ 而文件是 .ogg_）用上面的 fallback 找到同名文件。
        val requestedEncrypted = RpgCrypto.isEncryptedPath(relPath)
        if (!requestedEncrypted && RpgCrypto.isEncrypted(doc.name)) {
            serveDecrypted(out, doc, root, head)
            return
        }

        streamFile(out, doc, mime, range, head)
    }

    /** 按扩展名互补查找：MV 移动端会去要 .m4a，而游戏往往只带了 .ogg/.rpgmvo */
    private fun resolveWithFallback(root: String, relPath: String): DocInfo? {
        repo.resolve(root, relPath)?.let { return it }

        val slash = relPath.lastIndexOf('/')
        val dot = relPath.lastIndexOf('.')
        if (dot <= slash) return null

        val base = relPath.substring(0, dot)
        val ext = relPath.substring(dot + 1).lowercase()
        for (alt in altExtensions(ext)) {
            repo.resolve(root, "$base.$alt")?.let { return it }
        }
        return null
    }

    private fun altExtensions(ext: String): List<String> = when (ext) {
        "png" -> listOf("rpgmvp", "png_", "rmmzmvp", "jpg", "jpeg", "webp")
        "jpg", "jpeg" -> listOf("png", "rpgmvp", "png_", "webp")
        "webp" -> listOf("png", "rpgmvp")
        "rpgmvp", "rmmzmvp", "png_" -> listOf("png", "jpg", "rpgmvp", "png_", "rmmzmvp")
        "ogg" -> listOf("rpgmvo", "ogg_", "m4a", "rpgmvm", "m4a_", "mp3", "wav")
        "m4a" -> listOf("rpgmvm", "m4a_", "ogg", "rpgmvo", "ogg_", "mp3")
        "mp3" -> listOf("ogg", "rpgmvo", "m4a", "rpgmvm")
        "wav" -> listOf("ogg", "m4a")
        "rpgmvo", "ogg_" -> listOf("ogg", "m4a", "rpgmvo", "ogg_", "rpgmvm", "m4a_")
        "rpgmvm", "m4a_" -> listOf("m4a", "ogg", "rpgmvm", "m4a_", "rpgmvo", "ogg_")
        else -> emptyList()
    }

    /** 解密后整包下发（加密文件不支持 Range，体积都是几 MB 级的资源，本地直传足够快） */
    private fun serveDecrypted(out: OutputStream, doc: DocInfo, root: String, head: Boolean) {
        val raw = repo.readBytes(doc.docId, 128L * 1024 * 1024)
        if (raw == null) {
            writeStatus(out, 500, "text/plain; charset=utf-8", head)
            return
        }
        val key = encryptionKeyFor(root)
        val data = try {
            RpgCrypto.decrypt(raw, key)
        } catch (e: Exception) {
            Log.w(TAG, "解密失败: ${doc.name}", e)
            raw
        }
        val mime = RpgCrypto.sniffMime(data) ?: mimeOf(doc.name)
        writeBytes(out, mime, data, head)
    }

    @Volatile private var cachedKeyDocId: String? = null
    @Volatile private var cachedKey: String? = null

    /** data/System.json 里的 encryptionKey（每个游戏只读一次） */
    private fun encryptionKeyFor(rootDocId: String): String? {
        if (cachedKeyDocId == rootDocId) return cachedKey
        val key = try {
            val dataDir = repo.child(rootDocId, "data")
            val systemJson = dataDir?.let { repo.child(it.docId, "System.json") }
            val text = systemJson?.let { repo.readText(it.docId) }
            if (text == null) null
            else org.json.JSONObject(text).optString("encryptionKey", "").ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "读取 encryptionKey 失败", e)
            null
        }
        cachedKeyDocId = rootDocId
        cachedKey = key
        return key
    }

    private fun streamFile(out: OutputStream, doc: DocInfo, mime: String, range: String?, head: Boolean) {
        val total = doc.size
        if (total <= 0) {
            // 极少数 provider 不报告大小，整体读入内存
            val bytes = repo.readBytes(doc.docId, 256L * 1024 * 1024)
            if (bytes == null) {
                writeStatus(out, 500, "text/plain; charset=utf-8", head)
                return
            }
            writeBytes(out, mime, bytes, head)
            return
        }

        var start = 0L
        var end = total - 1
        var partial = false

        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',')
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val startStr = spec.substring(0, dash).trim()
                val endStr = spec.substring(dash + 1).trim()
                try {
                    if (startStr.isNotEmpty()) {
                        start = startStr.toLong()
                        if (endStr.isNotEmpty()) end = endStr.toLong()
                    } else if (endStr.isNotEmpty()) {
                        // bytes=-N 表示最后 N 字节
                        start = (total - endStr.toLong()).coerceAtLeast(0)
                    }
                    if (start in 0 until total) {
                        if (end >= total) end = total - 1
                        if (end >= start) partial = true
                    }
                } catch (e: NumberFormatException) {
                    partial = false
                }
            }
        }

        val length = if (partial) end - start + 1 else total

        val builder = StringBuilder()
        builder.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        builder.append("Content-Type: ").append(mime).append("\r\n")
        builder.append("Content-Length: ").append(length).append("\r\n")
        builder.append("Accept-Ranges: bytes\r\n")
        if (partial) builder.append("Content-Range: bytes ").append(start).append('-').append(end)
            .append('/').append(total).append("\r\n")
        builder.append("Cache-Control: no-cache\r\n")
        builder.append("Connection: keep-alive\r\n\r\n")
        out.write(builder.toString().toByteArray(Charsets.ISO_8859_1))

        if (head) return

        val stream = repo.openStream(doc.docId)
        if (stream == null) {
            Log.w(TAG, "读取失败: ${doc.name}")
            return
        }
        stream.use {
            if (start > 0) skipFully(it, start)
            copyN(it, out, length)
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(16 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) return
            remaining -= n
        }
    }

    private fun copyN(input: InputStream, out: OutputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) return
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun writeBytes(out: OutputStream, mime: String, bytes: ByteArray, head: Boolean) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $mime\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Accept-Ranges: bytes\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: keep-alive\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        if (!head) out.write(bytes)
    }

    private fun writeStatus(out: OutputStream, code: Int, mime: String, head: Boolean) {
        val reason = when (code) {
            403 -> "Forbidden"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val body = "$code $reason".toByteArray()
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $mime\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: keep-alive\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        if (!head) out.write(body)
    }

    // =====================================================================
    // HTML 注入
    // =====================================================================

    /** 少数国产魔改游戏的 index.html 是 GBK 编码：先按 UTF-8 严格解码，失败再退回 GBK */
    private fun decodeHtml(raw: ByteArray): String {
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString()
        } catch (e: Exception) {
            try {
                String(raw, charset("GBK"))
            } catch (e2: Exception) {
                String(raw, Charsets.UTF_8)
            }
        }
    }

    /** 统一 viewport，并在 <head> 最前面插入注入脚本（保证早于游戏脚本执行） */
    private fun injectHtml(html: String): String {
        var result = html
        try {
            val viewport = "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1," +
                "maximum-scale=1,user-scalable=no,viewport-fit=cover\">"
            result = Regex("(?is)<meta\\s+[^>]*name\\s*=\\s*[\"']viewport[\"'][^>]*>").replace(result, "")
            // 关闭作弊界面时只留一个标记，cheat.js 仍要加载（window.__rpgKey 由它提供）
            val flags = if (cheatUiEnabled) "" else "<script>window.__RPG_CHEAT_UI__=false;</script>"
            val snippet = viewport + flags +
                "<script src=\"$INJECT_PATH\" charset=\"utf-8\" data-rpg-cheat=\"1\"></script>"

            val lower = result.lowercase()
            var insertAt = -1
            val headIdx = lower.indexOf("<head")
            if (headIdx >= 0) {
                val gt = result.indexOf('>', headIdx)
                if (gt > 0) insertAt = gt + 1
            }
            if (insertAt < 0) {
                val htmlIdx = lower.indexOf("<html")
                if (htmlIdx >= 0) {
                    val gt = result.indexOf('>', htmlIdx)
                    if (gt > 0) insertAt = gt + 1
                }
            }
            if (insertAt < 0) insertAt = 0
            result = result.substring(0, insertAt) + snippet + result.substring(insertAt)
        } catch (e: Exception) {
            Log.w(TAG, "HTML 注入失败", e)
        }
        return result
    }

    private fun loadInjectJs(): ByteArray {
        injectJs?.let { return it }
        val bytes = try {
            // 作弊载荷来自 Core 子模块，构建时由 copySharedCore 拷进 assets
            context.assets.open("nodehost/core/cheat/android.js").use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "读取注入脚本失败", e)
            "console.error('[RPG] cheat.js 缺失');".toByteArray()
        }
        injectJs = bytes
        return bytes
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "txt" -> "text/plain; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "wasm" -> "application/wasm"
            // 加密资源按解密后的真实类型给 MIME：浏览器播放 <audio> 时会参考它
            "rpgmvp", "rmmzmvp", "png__", "png_" -> "image/png"
            "rpgmvo", "ogg_" -> "audio/ogg"
            "rpgmvm", "m4a_" -> "audio/mp4"
            "rpgsave", "rmmzsave" -> "application/octet-stream"
            else -> "application/octet-stream"
        }
    }
}
