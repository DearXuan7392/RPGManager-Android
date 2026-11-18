package com.dearxuan.rpgmanager.core

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * 暴露给游戏页面的原生文件桥（window.RPGNative）。
 *
 * 为什么需要它：RPG Maker MV 在浏览器模式下把存档塞进 localStorage，
 * 桌面端则是游戏目录下 save 文件夹里的 rpgsave 文件。这里让页面里的 StorageManager 补丁调用本桥，
 * 直接读写游戏目录，做到与桌面端存档完全一致（可互相拷贝）。
 *
 * 注意：@JavascriptInterface 方法运行在 WebView 的 JS 线程，不是主线程，
 * 因此可以安全地做同步 IO（StorageManager 的接口本身要求同步返回）。
 */
class NativeBridge(
    private val repo: SafRepo,
    private val gameRootDocId: String
) {
    companion object {
        private const val TAG = "NativeBridge"
    }

    private fun parentDocId(path: String): String? {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        var current = gameRootDocId
        for (i in 0 until segments.size - 1) {
            current = repo.ensureDir(current, segments[i]) ?: return null
        }
        return current
    }

    @JavascriptInterface
    fun exists(path: String): Boolean {
        return try {
            repo.resolve(gameRootDocId, path) != null
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun readText(path: String): String? {
        return try {
            val doc = repo.resolve(gameRootDocId, path) ?: return null
            if (doc.isDir) null else repo.readText(doc.docId)
        } catch (e: Exception) {
            Log.w(TAG, "readText 失败: $path", e)
            null
        }
    }

    @JavascriptInterface
    fun writeText(path: String, text: String): Boolean {
        return try {
            val parent = parentDocId(path) ?: return false
            val name = path.substringAfterLast('/')
            repo.writeFile(parent, name, "application/octet-stream", text.toByteArray(Charsets.UTF_8)) != null
        } catch (e: Exception) {
            Log.w(TAG, "writeText 失败: $path", e)
            false
        }
    }

    @JavascriptInterface
    fun removeFile(path: String): Boolean {
        return try {
            val doc = repo.resolve(gameRootDocId, path) ?: return false
            repo.delete(doc.docId)
        } catch (e: Exception) {
            false
        }
    }

    /** 供页面调试时确认桥已就绪 */
    @JavascriptInterface
    fun ping(): String = "rpg-native"

    @JavascriptInterface
    fun log(message: String) {
        Log.i("RPGPage", message)
    }
}
