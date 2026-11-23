package com.dearxuan.rpgmanager.core

import android.content.Context

/** 游戏由谁来加载：内嵌 Node（默认，存档与桌面端互通）或纯 WebView（网页版兜底） */
enum class RuntimeMode { NODE, WEBVIEW }

enum class CoverMode { TITLE, BLUR, OFF }

enum class ListMode { GRID, SINGLE }

/**
 * 用户偏好的持久化存储（SharedPreferences）。
 *
 * 只放"跨启动要记住"的开关；读写在 UI 线程直接做，量很小。
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("rpg_settings", Context.MODE_PRIVATE)

    var runtime: RuntimeMode
        get() = enumOf(prefs.getString(KEY_RUNTIME, null), RuntimeMode.NODE)
        set(value) = prefs.edit().putString(KEY_RUNTIME, value.name).apply()

    /** 游戏内的虚拟按键（方向键 + Enter / Space / Shift / Ctrl / Esc） */
    var virtualPad: Boolean
        get() = prefs.getBoolean(KEY_PAD, true)
        set(value) = prefs.edit().putBoolean(KEY_PAD, value).apply()

    /** 游戏内的作弊悬浮球与面板 */
    var cheatMenu: Boolean
        get() = prefs.getBoolean(KEY_CHEAT, true)
        set(value) = prefs.edit().putBoolean(KEY_CHEAT, value).apply()

    var coverMode: CoverMode
        get() = enumOf(prefs.getString(KEY_COVER, null), CoverMode.TITLE)
        set(value) = prefs.edit().putString(KEY_COVER, value.name).apply()

    var listMode: ListMode
        get() = enumOf(prefs.getString(KEY_LIST, null), ListMode.GRID)
        set(value) = prefs.edit().putString(KEY_LIST, value.name).apply()

    private inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T {
        if (name.isNullOrEmpty()) return fallback
        return try {
            enumValueOf<T>(name)
        } catch (e: IllegalArgumentException) {
            fallback
        }
    }

    private companion object {
        const val KEY_RUNTIME = "runtime"
        const val KEY_PAD = "virtual_pad"
        const val KEY_CHEAT = "cheat_menu"
        const val KEY_COVER = "cover_mode"
        const val KEY_LIST = "list_mode"
    }
}
