package com.dearxuan.rpgmanager.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * 把运行日志导出到用户通过 SAF 选定的文件夹。
 *
 * 不复用 SafRepo：那是游戏目录的全局授权，导出目标只是临时选择，
 * 不能覆盖用户的游戏根目录设置。
 */
object LogExporter {
    private const val TAG = "LogExporter"

    /** 返回是否写入成功；导出时自动在目标文件夹创建 .nomedia */
    fun export(context: Context, treeUri: Uri, fileName: String, text: String): Boolean {
        val cr = context.contentResolver
        val root = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            Log.w(TAG, "读取目录 ID 失败", e)
            return false
        }
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, root)

        if (findChild(cr, treeUri, root, ".nomedia") == null) {
            try {
                DocumentsContract.createDocument(
                    cr, dirUri, "application/octet-stream", ".nomedia"
                )
            } catch (e: Exception) {
                Log.w(TAG, "创建 .nomedia 失败", e)
            }
        }

        val docId = findChild(cr, treeUri, root, fileName)
        val target = if (docId != null) {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        } else {
            try {
                DocumentsContract.createDocument(cr, dirUri, "text/plain", fileName)
            } catch (e: Exception) {
                Log.w(TAG, "创建日志文件失败: $fileName", e)
                return false
            } ?: return false
        }

        return try {
            cr.openOutputStream(target, "wt")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: return false
            true
        } catch (e: Exception) {
            Log.w(TAG, "写入日志文件失败: $fileName", e)
            false
        }
    }

    private fun findChild(cr: ContentResolver, treeUri: Uri, parentId: String, name: String): String? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        try {
            cr.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    if (name == cursor.getString(1)) return id
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询目录失败: $name", e)
        }
        return null
    }
}
