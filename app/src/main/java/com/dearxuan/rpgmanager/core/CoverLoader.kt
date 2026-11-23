package com.dearxuan.rpgmanager.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 读取游戏封面（桌面端的「悬停背景图」）。
 *
 * 与桌面端一致：从 data/System.json 取 title1Name，到 img/titles1/ 下找同名文件，
 * 若加密则先解密再解码；找不到标题图就退回 icon/icon.png。
 *
 * 除了内存 LruCache，还把解码后的封面落到应用内部目录：
 * 下次启动直接读缓存，不必再通过 SAF 逐层查询 + 解密。
 * 游戏列表本身不缓存，仍由调用方每次重新扫描。
 */
class CoverLoader(private val context: Context, private val repo: SafRepo) {

    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val diskDir = File(context.filesDir, "cover-cache")

    init {
        diskDir.mkdirs()
    }

    fun cached(game: GameEntry): Bitmap? = synchronized(cache) { cache.get(game.docId) }

    @Synchronized
    fun load(game: GameEntry, targetWidth: Int = 480, force: Boolean = false): Bitmap? {
        if (!force) {
            cache.get(game.docId)?.let { return it }
            readDisk(game)?.let { bmp ->
                cache.put(game.docId, bmp)
                return bmp
            }
        }

        val bitmap = loadInternal(game, targetWidth) ?: return null
        cache.put(game.docId, bitmap)
        writeDisk(game, bitmap)
        return bitmap
    }

    /** 顶部刷新按钮：清掉内存与磁盘缓存，下次显示时重新解码 */
    @Synchronized
    fun clear() {
        cache.evictAll()
        diskDir.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
    }

    /** 扫描完成后调用：删掉已不存在游戏的封面缓存 */
    @Synchronized
    fun prune(validGames: Collection<GameEntry>) {
        val validKeys = validGames.map(::keyOf).toSet()
        val validIds = validGames.map { it.docId }.toSet()
        diskDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.removeSuffix(".png") !in validKeys) file.delete()
        }
        cache.snapshot().keys.forEach { docId ->
            if (docId !in validIds) cache.remove(docId)
        }
    }

    private fun fileOf(game: GameEntry): File = File(diskDir, keyOf(game) + ".png")

    private fun keyOf(game: GameEntry): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(game.docId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readDisk(game: GameEntry): Bitmap? {
        val file = fileOf(game)
        if (!file.isFile) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Throwable) {
            file.delete()
            null
        }
    }

    private fun writeDisk(game: GameEntry, bitmap: Bitmap) {
        try {
            FileOutputStream(fileOf(game)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Throwable) {
            // 缓存写不进去不影响显示，下次再试
        }
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
