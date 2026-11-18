package com.dearxuan.rpgmanager.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONObject

/**
 * 读取游戏封面（桌面端的「悬停背景图」）。
 *
 * 与桌面端一致：从 data/System.json 取 title1Name，到 img/titles1/ 下找同名文件，
 * 若加密则先解密再解码；找不到标题图就退回 icon/icon.png。
 */
class CoverLoader(private val repo: SafRepo) {

    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun cached(game: GameEntry): Bitmap? = synchronized(cache) { cache.get(game.docId) }

    @Synchronized
    fun load(game: GameEntry, targetWidth: Int = 480): Bitmap? {
        cache.get(game.docId)?.let { return it }
        val bitmap = loadInternal(game, targetWidth) ?: return null
        cache.put(game.docId, bitmap)
        return bitmap
    }

    private fun loadInternal(game: GameEntry, targetWidth: Int): Bitmap? {
        val (titleName, key) = readSystem(game)

        val titlesDir = repo.child(game.docId, "img")
            ?.takeIf { it.isDir }
            ?.let { repo.child(it.docId, "titles1") }
            ?.takeIf { it.isDir }

        if (titlesDir != null) {
            val exts = listOf(".rpgmvp", ".rmmzmvp", ".png__", ".png_", ".png", ".jpg", ".jpeg")
            var file: DocInfo? = null
            if (titleName != null) {
                for (ext in exts) {
                    val doc = repo.child(titlesDir.docId, titleName + ext)
                    if (doc != null && !doc.isDir) { file = doc; break }
                }
            }
            if (file == null) {
                file = repo.children(titlesDir.docId).values.firstOrNull { doc ->
                    !doc.isDir && exts.any { doc.name.lowercase().endsWith(it) }
                }
            }
            if (file != null) {
                val bytes = repo.readBytes(file.docId)
                if (bytes != null) {
                    val raw = if (RpgCrypto.isEncryptedImage(file.name)) RpgCrypto.decrypt(bytes, key) else bytes
                    decode(raw, targetWidth)?.let { return it }
                }
            }
        }

        // 退回游戏图标
        val icon = repo.child(game.docId, "icon")
            ?.takeIf { it.isDir }
            ?.let { repo.child(it.docId, "icon.png") }
        if (icon != null) {
            val bytes = repo.readBytes(icon.docId)
            if (bytes != null) decode(bytes, 256)?.let { return it }
        }
        return null
    }

    private fun readSystem(game: GameEntry): Pair<String?, String?> {
        val dataDir = repo.child(game.docId, "data")?.takeIf { it.isDir } ?: return null to null
        val sys = repo.child(dataDir.docId, "System.json") ?: return null to null
        val text = repo.readText(sys.docId) ?: return null to null
        return try {
            val json = JSONObject(text)
            val title = json.optString("title1Name", "").ifEmpty { null }
            val key = json.optString("encryptionKey", "").ifEmpty { null }
            title to key
        } catch (e: Exception) {
            null to null
        }
    }

    private fun decode(bytes: ByteArray, targetWidth: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Throwable) {
            null
        }
    }
}
