package com.dearxuan.rpgmanager.core

import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * 两个来自桌面端的批量功能：
 *   1. 只解密 img 图片资产 → <游戏>/decrypt_img/
 *   2. 克隆一份完全解密、可直接游玩的副本 → <游戏名>-解密/
 *
 * 安卓走 SAF，每个文件都要单独创建 document，速度比桌面端慢，所以全程汇报进度。
 */
class DecryptTasks(private val repo: SafRepo) {

    data class Progress(
        val title: String,
        val detail: String,
        val current: Int,
        val total: Int
    ) {
        val fraction: Float
            get() = if (total <= 0) 0f else (current.toFloat() / total).coerceIn(0f, 1f)
    }

    private data class Entry(val doc: DocInfo, val rel: String)

    private fun walk(dirDocId: String, prefix: String, out: MutableList<Entry>) {
        for (doc in repo.children(dirDocId).values) {
            val rel = if (prefix.isEmpty()) doc.name else "$prefix/${doc.name}"
            if (doc.isDir) walk(doc.docId, rel, out) else out += Entry(doc, rel)
        }
    }

    private fun readKey(game: GameEntry): String? {
        val dataDir = repo.child(game.docId, "data")?.takeIf { it.isDir } ?: return null
        val sys = repo.child(dataDir.docId, "System.json") ?: return null
        val text = repo.readText(sys.docId) ?: return null
        return try {
            val json = JSONObject(text)
            json.optString("encryptionKey", "").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun ensureOutDir(
        rootDocId: String,
        dirRel: String,
        cache: MutableMap<String, String>
    ): String? {
        if (dirRel.isEmpty()) return rootDocId
        cache[dirRel]?.let { return it }
        val parentRel = dirRel.substringBeforeLast('/', "")
        val name = dirRel.substringAfterLast('/')
        val parent = ensureOutDir(rootDocId, parentRel, cache) ?: return null
        val id = repo.ensureDir(parent, name) ?: return null
        cache[dirRel] = id
        return id
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "json" -> "application/json"
        "js" -> "application/javascript"
        "html" -> "text/html"
        "css" -> "text/css"
        else -> "application/octet-stream"
    }

    /** 只解密图片资产 */
    suspend fun decryptImages(game: GameEntry, onProgress: (Progress) -> Unit): String {
        val imgDir = repo.child(game.docId, "img")?.takeIf { it.isDir }
            ?: return "未找到该游戏的 img 资源文件夹"

        val files = ArrayList<Entry>()
        walk(imgDir.docId, "", files)
        if (files.isEmpty()) return "img 文件夹为空"

        if (files.none { RpgCrypto.isEncrypted(it.rel) }) {
            return "img 资源未加密，无需解密"
        }

        val key = readKey(game)
        val outRoot = repo.ensureDir(game.docId, "decrypt_img")
            ?: return "无法在游戏目录下创建 decrypt_img"

        val dirCache = HashMap<String, String>()
        var done = 0
        var failed = 0

        for (entry in files) {
            coroutineContext.ensureActive()
            done++

            val outName = RpgCrypto.decryptedName(entry.rel.substringAfterLast('/'))
            val outRelDir = entry.rel.substringBeforeLast('/', "")
            val outDirId = ensureOutDir(outRoot, outRelDir, dirCache)

            if (outDirId != null) {
                val bytes = repo.readBytes(entry.doc.docId)
                if (bytes != null) {
                    val data = if (RpgCrypto.isEncrypted(entry.doc.name)) {
                        val decrypted = RpgCrypto.decrypt(bytes, key)
                        if (RpgCrypto.isEncryptedAudio(entry.doc.name)) RpgCrypto.stripAudioHeader(decrypted) else decrypted
                    } else {
                        bytes
                    }
                    if (repo.writeFile(outDirId, outName, mimeFor(outName), data) == null) failed++
                } else {
                    failed++
                }
            } else {
                failed++
            }

            if (done % 5 == 0 || done == files.size) {
                onProgress(Progress("正在解密图片资产", entry.rel, done, files.size))
            }
        }

        repo.invalidate(game.docId)
        return if (failed == 0) "解密完成，共 ${files.size} 个文件 → decrypt_img/"
        else "解密结束，${files.size - failed} 个成功，$failed 个失败"
    }

    /** 克隆完整解密副本 */
    suspend fun cloneDecryptedGame(
        game: GameEntry,
        gamesRootDocId: String,
        onProgress: (Progress) -> Unit
    ): String {
        val files = ArrayList<Entry>()
        walk(game.docId, "", files)
        if (files.isEmpty()) return "游戏目录为空"

        val outName = game.name + "-解密"
        val outRoot = if (repo.child(gamesRootDocId, outName) != null) {
            repo.child(gamesRootDocId, outName)?.docId
        } else {
            repo.ensureDir(gamesRootDocId, outName)
        } ?: return "无法创建输出目录 $outName"

        val key = readKey(game)
        val dirCache = HashMap<String, String>()
        var done = 0
        var failed = 0

        for (entry in files) {
            coroutineContext.ensureActive()
            done++

            val parentRel = entry.rel.substringBeforeLast('/', "")
            val outDirId = ensureOutDir(outRoot, parentRel, dirCache)
            val rawName = entry.rel.substringAfterLast('/')
            val outNameFile = RpgCrypto.decryptedName(rawName)

            if (outDirId != null) {
                val bytes = repo.readBytes(entry.doc.docId)
                if (bytes != null) {
                    val isSystemJson = entry.rel == "data/System.json"
                    val data = when {
                        isSystemJson -> patchSystemJson(bytes)
                        RpgCrypto.isEncrypted(rawName) -> {
                            val decrypted = RpgCrypto.decrypt(bytes, key)
                            if (RpgCrypto.isEncryptedAudio(rawName)) RpgCrypto.stripAudioHeader(decrypted) else decrypted
                        }
                        else -> bytes
                    }
                    if (repo.writeFile(outDirId, outNameFile, mimeFor(outNameFile), data) == null) failed++
                } else {
                    failed++
                }
            } else {
                failed++
            }

            if (done % 5 == 0 || done == files.size) {
                onProgress(Progress("正在克隆解密版游戏", entry.rel, done, files.size))
            }
        }

        repo.invalidate(gamesRootDocId)
        return if (failed == 0) "解密副本已生成：$outName（${files.size} 个文件）"
        else "解密副本已生成：$outName（${files.size - failed} 成功，$failed 失败）"
    }

    /** 关掉副本里的加密标记，游戏才会按明文加载 */
    private fun patchSystemJson(bytes: ByteArray): ByteArray {
        return try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            json.put("hasEncryptedImages", false)
            json.put("hasEncryptedAudio", false)
            json.toString(4).toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            bytes
        }
    }
}
