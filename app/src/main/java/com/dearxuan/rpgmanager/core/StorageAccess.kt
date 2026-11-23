package com.dearxuan.rpgmanager.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

/**
 * SAF 目录 → 真实文件路径，以及「所有文件访问权限」相关判断。
 *
 * 【为什么还要真实路径】
 * 内嵌的 Node 运行时不认识 content:// ，它的 fs 只能操作真实路径。所以流程是：
 *   1. 用户照常通过系统选择器挑一个文件夹（SAF，持久化授权，菜单/解密/存档管理都靠它）；
 *   2. 同时把该目录换算成 /storage/emulated/0/... 这样的真实路径交给 Node；
 *   3. 读这个路径需要「所有文件访问权限」（Android 11+ 对非媒体文件的限制），
 *      没有就引导用户去系统设置里打开。
 */
object StorageAccess {

    private val SDCARD_VOLUME = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")

    fun isAllFilesGranted(): Boolean = Environment.isExternalStorageManager()

    /** 申请「所有文件访问权限」的设置页（部分 ROM 没有应用级入口，退回到总列表） */
    fun allFilesSettingsIntents(context: Context): List<Intent> {
        val specific = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        val generic = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        return listOf(specific, generic)
    }

    /** 由 SAF 的 tree uri 换算真实路径，失败返回 null（第三方网盘类 provider 没有实体路径） */
    fun realPathOf(treeUri: Uri): String? {
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return null
        }
        return realPathOfDocId(docId)
    }

    fun realPathOfDocId(docId: String): String? {
        val colon = docId.indexOf(':')
        val volume = if (colon >= 0) docId.substring(0, colon) else ""
        val relative = if (colon >= 0) docId.substring(colon + 1) else docId

        val base = when {
            volume.equals("primary", true) -> Environment.getExternalStorageDirectory().absolutePath
            volume.equals("home", true) ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath
            SDCARD_VOLUME.matches(volume) -> "/storage/$volume"
            else -> return null
        }
        return if (relative.isEmpty()) base else "$base/$relative"
    }

    fun canReadWrite(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        val dir = File(path)
        if (!dir.isDirectory) return false
        if (!dir.canRead() || !dir.canWrite()) return false
        // canRead/canWrite 在部分 ROM 上只反映 SELinux 标签，实际 list 一次更可靠
        return try {
            dir.list() != null
        } catch (e: Exception) {
            false
        }
    }
}
