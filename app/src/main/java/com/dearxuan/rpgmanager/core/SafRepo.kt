package com.dearxuan.rpgmanager.core

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 一个 SAF 文档（文件或目录）。docId 就是 DocumentsContract 里的 document id，
 * 由它可以直接拼出 content uri，无需遍历目录树。
 */
data class DocInfo(
    val docId: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long
)

/**
 * 通过 SAF 访问用户选定目录下的文件。
 *
 * 桌面端直接读磁盘，安卓端没有真实的文件路径，只能走 ContentResolver，
 * 所以这里把「路径 → 文档」的解析结果按目录缓存起来，避免每次请求都查询一次 provider。
 */
class SafRepo(private val context: Context) {

    companion object {
        private const val TAG = "SafRepo"
        private const val PREF = "rpg_manager"
        private const val KEY_TREE = "tree_uri"
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }

    private val cr: ContentResolver get() = context.contentResolver
    private val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 目录 id -> (文件名 -> 文档) */
    private val listingCache = ConcurrentHashMap<String, Map<String, DocInfo>>()

    var treeUri: Uri? = prefs.getString(KEY_TREE, null)?.let(Uri::parse)
        private set

    fun hasPermission(): Boolean {
        val uri = treeUri ?: return false
        return try {
            cr.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        } catch (e: Exception) {
            false
        }
    }

    /** 记住用户选择的目录（含持久化授权） */
    fun setTree(uri: Uri): Boolean {
        val flags = arrayOf(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        for (flag in flags) {
            try {
                cr.takePersistableUriPermission(uri, flag)
                treeUri = uri
                prefs.edit().putString(KEY_TREE, uri.toString()).apply()
                listingCache.clear()
                return true
            } catch (e: Exception) {
                Log.w(TAG, "takePersistableUriPermission failed: $flag", e)
            }
        }
        return false
    }

    fun clearTree() {
        val uri = treeUri
        if (uri != null) {
            try {
                cr.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // 忽略：可能只持有读权限
            }
        }
        treeUri = null
        prefs.edit().remove(KEY_TREE).apply()
        listingCache.clear()
    }

    fun rootDocId(): String? = treeUri?.let { DocumentsContract.getTreeDocumentId(it) }

    /** 用户选定目录对应的真实路径（供内嵌 Node 使用；网盘类 provider 会返回 null） */
    fun realRootPath(): String? = treeUri?.let { StorageAccess.realPathOf(it) }

    fun documentUri(docId: String): Uri? =
        treeUri?.let { DocumentsContract.buildDocumentUriUsingTree(it, docId) }

    fun invalidate(docId: String? = null) {
        if (docId == null) listingCache.clear() else listingCache.remove(docId)
    }

    /** 列出目录内容（带缓存） */
    fun children(dirDocId: String): Map<String, DocInfo> {
        listingCache[dirDocId]?.let { return it }
        val tree = treeUri ?: return emptyMap()

        val result = LinkedHashMap<String, DocInfo>()
        try {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirDocId)
            cr.query(uri, PROJECTION, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val lastModified = if (c.isNull(4)) 0L else c.getLong(4)
                    result[name] = DocInfo(
                        docId = id,
                        name = name,
                        isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = size,
                        lastModified = lastModified
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "列目录失败: $dirDocId", e)
        }
        listingCache[dirDocId] = result
        return result
    }

    /** 按名字找子项；先精确匹配，再忽略大小写（部分游戏在 Windows 下文件名大小写不严格） */
    fun child(dirDocId: String, name: String): DocInfo? {
        val kids = children(dirDocId)
        kids[name]?.let { return it }
        return kids.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /** 解析 a/b/c 形式的相对路径 */
    fun resolve(dirDocId: String, path: String): DocInfo? {
        var currentDir = dirDocId
        var found: DocInfo? = null
        for (segment in path.split('/')) {
            if (segment.isEmpty() || segment == ".") continue
            if (segment == "..") return null
            val doc = child(currentDir, segment) ?: return null
            found = doc
            if (!doc.isDir) return doc
            currentDir = doc.docId
        }
        return found
    }

    fun openStream(docId: String): InputStream? {
        val uri = documentUri(docId) ?: return null
        return try {
            cr.openInputStream(uri)
        } catch (e: FileNotFoundException) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "打开文件失败: $docId", e)
            null
        }
    }

    fun readBytes(docId: String, limit: Long = Long.MAX_VALUE): ByteArray? {
        val input = openStream(docId) ?: return null
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                total += n
                if (total > limit) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    fun readText(docId: String): String? {
        val bytes = readBytes(docId, 64L * 1024 * 1024) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    /** 目录下取子目录，没有就创建；返回新目录 docId */
    fun ensureDir(parentDocId: String, name: String): String? {
        child(parentDocId, name)?.let { if (it.isDir) return it.docId }
        val parentUri = documentUri(parentDocId) ?: return null
        return try {
            val created = DocumentsContract.createDocument(
                cr, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name
            ) ?: return null
            val docId = DocumentsContract.getDocumentId(created)
            listingCache.remove(parentDocId)
            docId
        } catch (e: Exception) {
            Log.w(TAG, "创建目录失败: $name", e)
            null
        }
    }

    /** 写入文件（存在则覆盖，不存在则创建）；返回文件 docId */
    fun writeFile(parentDocId: String, name: String, mime: String, bytes: ByteArray): String? {
        val existing = child(parentDocId, name)
        if (existing != null && !existing.isDir) {
            return if (overwrite(existing.docId, bytes)) existing.docId else null
        }
        val parentUri = documentUri(parentDocId) ?: return null
        return try {
            val created = DocumentsContract.createDocument(cr, parentUri, mime, name) ?: return null
            val docId = DocumentsContract.getDocumentId(created)
            listingCache.remove(parentDocId)
            if (overwrite(docId, bytes)) docId else null
        } catch (e: Exception) {
            Log.w(TAG, "创建文件失败: $name", e)
            null
        }
    }

    fun overwrite(docId: String, bytes: ByteArray): Boolean {
        val uri = documentUri(docId) ?: return false
        return try {
            cr.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: return false
            true
        } catch (e: Exception) {
            Log.w(TAG, "写入失败: $docId", e)
            false
        }
    }

    fun delete(docId: String): Boolean {
        val uri = documentUri(docId) ?: return false
        return try {
            DocumentsContract.deleteDocument(cr, uri)
        } catch (e: Exception) {
            Log.w(TAG, "删除失败: $docId", e)
            false
        }
    }

    /** 扫描根目录下的全部游戏（含 index.html 的子目录，兼容多套一层 www 的情况） */
    fun scanGames(): List<GameEntry> {
        val root = rootDocId() ?: return emptyList()
        val result = ArrayList<GameEntry>()
        children(root).values
            .filter { it.isDir && !it.name.startsWith(".") }
            .sortedBy { it.name }
            .forEach { dir ->
                val kids = children(dir.docId)
                when {
                    kids.containsKey("index.html") ->
                        result += GameEntry(dir.name, dir.docId, dir.name)

                    else -> {
                        val www = kids["www"]
                        if (www != null && www.isDir && children(www.docId).containsKey("index.html")) {
                            result += GameEntry(dir.name, www.docId, dir.name + "/www")
                        }
                    }
                }
            }
        return result
    }
}

/** 游戏条目：显示名 + 实际作为游戏根的文档 id */
data class GameEntry(
    val name: String,
    val docId: String,
    val location: String
)
