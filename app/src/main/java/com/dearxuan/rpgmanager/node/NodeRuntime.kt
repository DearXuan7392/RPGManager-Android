package com.dearxuan.rpgmanager.node

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/**
 * 内嵌 Node 运行时的生命周期管理。
 *
 * 桌面版用 nw.exe 加载游戏；安卓版在这里把 nodejs-mobile 的 libnode.so 拉起来，跑
 * assets/nodehost/main.js —— 一个本地 HTTP 服务 + 真·fs 通道。WebView 只是显示层，
 * 游戏发起的 require('fs') 都由这个 Node 进程真正落盘（存档因此和桌面端完全一致）。
 *
 * 一个进程只能调一次 node::Start，所以这里的实例贯穿整个应用生命周期。
 */
object NodeRuntime {

    private const val TAG = "NodeRuntime"
    private const val PREFERRED_PORT = 18666
    private const val PORT_TRIES = 12
    private const val READY_TIMEOUT_MS = 25_000L
    private const val ASSET_DIR = "nodehost"

    @Volatile private var threadStarted = false
    @Volatile private var port = 0
    @Volatile private var lastError: String? = null
    @Volatile private var abi: String? = null

    val baseUrl: String get() = "http://127.0.0.1:$port"
    fun lastError(): String? = lastError
    fun abiName(): String? = abi

    fun logFile(context: Context): File = File(context.filesDir, "node-runtime.log")

    /**
     * 确保 Node 已启动并可用，返回端口；失败返回 0（原因见 [lastError]）。
     */
    @Synchronized
    fun ensureStarted(context: Context): Int {
        if (port != 0) return port

        val dir = extractAssets(context)
        lastError = null
        val chosen = pickPort()
        val ready = try {
            startThread(context.applicationContext, dir, chosen)
            waitUntilReady(chosen, READY_TIMEOUT_MS)
        } catch (e: Throwable) {
            lastError = "启动 Node 失败: ${e.message}"
            Log.e(TAG, lastError, e)
            false
        }

        if (!ready) {
            if (lastError == null) {
                lastError = "Node 运行时启动超时，请查看日志：${logFile(context).absolutePath}"
            }
            return 0
        }
        port = chosen
        lastError = null
        return port
    }

    private fun startThread(context: Context, dir: File, chosenPort: Int) {
        if (threadStarted) return
        threadStarted = true

        val mainJs = File(dir, "main.js").absolutePath
        val logPath = logFile(context).absolutePath
        val config = JSONObject().apply {
            put("port", chosenPort)
            put("gamesRoot", "")
            put("logFile", logPath)
            put("tmpDir", context.cacheDir.absolutePath)
        }.toString()

        thread(name = "rpg-node", isDaemon = true) {
            try {
                System.loadLibrary("c++_shared")
                System.loadLibrary("node")
                System.loadLibrary("nodejni")
                val launcher = NodeLauncher()
                abi = try { launcher.abiTag() } catch (e: Throwable) { null }
                Log.i(TAG, "启动 Node 运行时: $mainJs (ABI=$abi)")
                val code = launcher.startNodeWithArguments(arrayOf("node", mainJs, config))
                Log.w(TAG, "Node 运行时已退出，返回码 $code")
                lastError = "Node 运行时已退出（返回码 $code）"
                port = 0
            } catch (e: Throwable) {
                lastError = "加载 Node 运行时失败: ${e.message}"
                Log.e(TAG, "加载 Node 运行时失败", e)
                port = 0
            }
        }
    }

    private fun waitUntilReady(chosenPort: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ping(chosenPort)) return true
            // native 库加载失败之类的问题不会自愈，直接放弃，别让用户干等 25 秒
            if (lastError != null && port == 0) return false
            try {
                Thread.sleep(150)
            } catch (e: InterruptedException) {
                return false
            }
        }
        return false
    }

    /** 用最原始的 socket 打一次 /__rpg/ping，避免拉起一整套 HTTP 客户端 */
    private fun ping(chosenPort: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), chosenPort), 300)
                socket.soTimeout = 800
                val req = "GET /__rpg/ping HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n"
                socket.getOutputStream().write(req.toByteArray())
                socket.getOutputStream().flush()
                val buf = ByteArray(256)
                val n = socket.getInputStream().read(buf)
                n > 0 && String(buf, 0, n).contains("200")
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 告诉 Node 当前的游戏根目录（用户换文件夹时也要调一次） */
    fun setGamesRoot(path: String): Boolean =
        postJson("/__rpg/root", JSONObject().put("path", path), "游戏根目录")

    /** 告诉 Node 是否显示游戏内的作弊悬浮球（只影响界面，虚拟按键不受影响） */
    fun setCheatUi(enabled: Boolean): Boolean =
        postJson("/__rpg/flags", JSONObject().put("cheatUi", enabled), "游戏内开关")

    private fun postJson(path: String, body: JSONObject, what: String): Boolean {
        val chosenPort = port
        if (chosenPort == 0) return false
        return try {
            val conn = URL("http://127.0.0.1:$chosenPort$path").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
            conn.disconnect()
            val ok = code in 200..299 && text?.contains("\"ok\":true") == true
            if (!ok) Log.w(TAG, "设置$what 失败: $code $text")
            ok
        } catch (e: IOException) {
            Log.w(TAG, "设置$what 异常", e)
            false
        }
    }

    fun readLog(context: Context, maxBytes: Int = 32 * 1024): String {
        val file = logFile(context)
        if (!file.exists()) return ""
        return try {
            val bytes = file.readBytes()
            val from = maxOf(0, bytes.size - maxBytes)
            String(bytes, from, bytes.size - from)
        } catch (e: Exception) {
            ""
        }
    }

    // =====================================================================
    // assets 解压
    // =====================================================================

    /**
     * node 的 require 需要真实文件，所以把 assets/nodehost 整棵树解压到 filesDir/nodehost。
     * 应用升级后（版本号变化）重新解压一次。
     */
    private fun extractAssets(context: Context): File {
        val root = File(context.filesDir, ASSET_DIR)
        val stamp = File(root, ".stamp")
        val want = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionCode}-${info.lastUpdateTime}"
        } catch (e: Exception) {
            "0"
        }
        if (root.isDirectory && stamp.isFile && stamp.readText() == want) return root

        root.deleteRecursively()
        root.mkdirs()
        copyAssetDir(context, ASSET_DIR, root)
        stamp.writeText(want)
        Log.i(TAG, "已解压 Node 端脚本到 ${root.absolutePath}")
        return root
    }

    private fun copyAssetDir(context: Context, assetPath: String, dest: File) {
        val assets = context.assets
        val children = assets.list(assetPath) ?: return
        dest.mkdirs()
        for (name in children) {
            val childAsset = "$assetPath/$name"
            val childDest = File(dest, name)
            val grandChildren = assets.list(childAsset).orEmpty()
            if (grandChildren.isNotEmpty()) {
                copyAssetDir(context, childAsset, childDest)
            } else {
                try {
                    assets.open(childAsset).use { input ->
                        childDest.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解压 $childAsset 失败", e)
                }
            }
        }
    }

    // =====================================================================
    // 端口
    // =====================================================================

    private fun pickPort(): Int {
        val loopback = InetAddress.getByName("127.0.0.1")
        for (i in 0 until PORT_TRIES) {
            val candidate = PREFERRED_PORT + i
            try {
                ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(loopback, candidate), 1)
                }
                return candidate
            } catch (e: Exception) {
            }
        }
        return ServerSocket(0, 1, loopback).use { it.localPort }
    }
}
