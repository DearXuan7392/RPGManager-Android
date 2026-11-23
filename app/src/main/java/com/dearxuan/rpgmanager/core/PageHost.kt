package com.dearxuan.rpgmanager.core

import android.webkit.JavascriptInterface

/**
 * 游戏页暴露给页面的一小块能力：告诉原生层「作弊面板开/关了」。
 *
 * 为什么需要它：虚拟按键是原生 View，画在 WebView 之上，会把页面里的作弊面板盖掉一半。
 * 面板一打开就让原生收起按键，关掉再放回来。
 *
 * 只有这一个方法，不碰文件、不碰游戏状态，暴露给页面没什么风险。
 */
class PageHost(private val onCheatPanelOpen: (Boolean) -> Unit) {

    @JavascriptInterface
    fun setCheatPanelOpen(open: Boolean) {
        onCheatPanelOpen(open)
    }
}
