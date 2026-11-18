package com.dearxuan.rpgmanager

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dearxuan.rpgmanager.core.NativeBridge
import com.dearxuan.rpgmanager.core.SafRepo
import com.dearxuan.rpgmanager.server.GameServer
import com.dearxuan.rpgmanager.ui.theme.RPGManagerTheme

/**
 * 游戏页：WebView 加载本机 HTTP 服务上的游戏，并在其上叠加虚拟按键。
 *
 * 对应桌面端 inject.js 里的两件事：
 *   - iframe 挂载游戏（这里换成 WebView + 本地服务，菜单页不再需要保活）；
 *   - 等比缩放 + 黑边（这里由 MV/MZ 自身的 canvas 缩放完成，WebView 铺满屏幕即可）。
 */
class GameActivity : ComponentActivity() {

    companion object {
        const val EXTRA_NAME = "game_name"
        const val EXTRA_DOC_ID = "game_doc_id"
        private const val TAG = "GameActivity"
    }

    private lateinit var repo: SafRepo
    private var server: GameServer? = null
    private var webView: WebView? = null

    private var gameName: String = ""
    private var gameDocId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repo = SafRepo(this)
        gameName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        gameDocId = intent.getStringExtra(EXTRA_DOC_ID)
        if (gameDocId == null) {
            Toast.makeText(this, "游戏目录不存在，请重新选择文件夹", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 调试用：允许 chrome://inspect / CDP 连进 WebView
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        hideSystemBars()

        val srv = GameServer(this, repo)
        srv.gameDocId = gameDocId
        if (!srv.start()) {
            Toast.makeText(this, "本地资源服务启动失败", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        server = srv

        // 与桌面端一致：启动前先确保 save 目录存在，方便直接拷贝电脑上的存档进来
        val rootDocId = gameDocId!!
        Thread {
            try { repo.ensureDir(rootDocId, "save") } catch (e: Exception) { }
        }.start()

        setContent {
            RPGManagerTheme {
                GameScreen(
                    url = "${srv.baseUrl}/g/index.html",
                    gameName = gameName,
                    docId = gameDocId!!,
                    onAttachWebView = { webView = it },
                    onFinish = { finish() }
                )
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        webView?.onResume()
        webView?.resumeTimers()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        try {
            webView?.let { wv ->
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.removeAllViews()
                wv.destroy()
            }
        } catch (e: Exception) {
            Log.w(TAG, "销毁 WebView 失败", e)
        }
        webView = null
        server?.stop()
        server = null
        super.onDestroy()
    }
}

/** WebView 持有者（在 AndroidView 工厂里赋值，不参与 Compose 状态） */
private class WebViewHolder {
    var view: WebView? = null
}

/** 虚拟按键对应的 keyCode（RPG Maker MV/MZ 的 Input.keyMapper） */
private object Keys {
    const val UP = 38
    const val DOWN = 40
    const val LEFT = 37
    const val RIGHT = 39
    const val OK = 90      // Z
    const val CANCEL = 88  // X
    const val TAB = 9      // 快进
    const val SHIFT = 16   // 快走
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GameScreen(
    url: String,
    gameName: String,
    docId: String,
    onAttachWebView: (WebView) -> Unit,
    onFinish: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { SafRepo(context) }

    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showPad by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var exitConfirm by remember { mutableStateOf(false) }

    // 用普通持有者存放 WebView，避免在 AndroidView 工厂里写 Compose 状态
    val webHolder = remember { WebViewHolder() }

    fun sendKey(code: Int, down: Boolean) {
        webHolder.view?.evaluateJavascript("window.__rpgKey&&window.__rpgKey($code,$down);", null)
    }

    BackHandler {
        if (menuOpen) menuOpen = false
        else if (exitConfirm) exitConfirm = false
        else exitConfirm = true
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(android.graphics.Color.BLACK)
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = false
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)

                    addJavascriptInterface(NativeBridge(repo, docId), "RPGNative")

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? = null

                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            loading = false
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: android.webkit.WebResourceError
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request.isForMainFrame) {
                                loading = false
                                errorText = "游戏页面加载失败：${error.description}"
                            }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(
                            consoleMessage: android.webkit.ConsoleMessage
                        ): Boolean {
                            Log.d("RPGPage", "[${consoleMessage.messageLevel()}] ${consoleMessage.message()}")
                            return true
                        }
                    }

                    onAttachWebView(this)
                    webHolder.view = this
                    loadUrl(url)
                }
            }
        )

        if (loading) {
            LoadingOverlay(gameName)
        }

        errorText?.let { message ->
            ErrorOverlay(message) {
                loading = true
                errorText = null
                webHolder.view?.reload()
            }
        }

        if (!loading && errorText == null) {
            if (showPad) {
                GamePad(sendKey = ::sendKey)
            }
            MenuButton(onClick = { menuOpen = true })
            if (menuOpen) {
                GameMenu(
                    showPad = showPad,
                    onTogglePad = { showPad = !showPad; },
                    onOpenCheat = {
                        menuOpen = false
                        webHolder.view?.evaluateJavascript(
                            "window.__rpgOpenCheat&&window.__rpgOpenCheat();", null
                        )
                    },
                    onReload = {
                        menuOpen = false
                        loading = true
                        webHolder.view?.reload()
                    },
                    onExit = { menuOpen = false; exitConfirm = true },
                    onDismiss = { menuOpen = false }
                )
            }
        }
    }

    if (exitConfirm) {
        AlertDialog(
            onDismissRequest = { exitConfirm = false },
            title = { Text("退出游戏") },
            text = { Text("存档会写入游戏目录下的 save 文件夹，确认退出吗？") },
            confirmButton = {
                TextButton(onClick = { exitConfirm = false; onFinish() }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { exitConfirm = false }) { Text("继续游戏") }
            }
        )
    }
}

@Composable
private fun LoadingOverlay(gameName: String) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0E)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color(0xFF4FC3F7), strokeWidth = 3.dp)
            Spacer(Modifier.height(18.dp))
            Text(gameName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("正在加载游戏…", color = Color(0xFF9AA0AD), fontSize = 13.sp)
        }
    }
}

@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0E)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("出错了", color = Color(0xFFFF8A80), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(message, color = Color(0xFFB8BECC), fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

// =====================================================================
// 虚拟按键
// =====================================================================

@Composable
private fun HoldKey(
    code: Int,
    label: String,
    size: Int,
    shape: androidx.compose.ui.graphics.Shape,
    fontSize: Int = 16,
    onKey: (Int, Boolean) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(shape)
            .background(if (pressed) Color(0x664FC3F7) else Color(0x33000000))
            .border(1.dp, if (pressed) Color(0x994FC3F7) else Color(0x55FFFFFF), shape)
            .pointerInput(code) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onKey(code, true)
                    waitForUpOrCancellation()
                    pressed = false
                    onKey(code, false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color(0xE6FFFFFF), fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GamePad(sendKey: (Int, Boolean) -> Unit) {
    Box(Modifier.fillMaxSize()) {

        // 左下：方向键
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HoldKey(Keys.UP, "▲", 54, RoundedCornerShape(12.dp), onKey = sendKey)
                Spacer(Modifier.height(6.dp))
                Row {
                    HoldKey(Keys.LEFT, "◀", 54, RoundedCornerShape(12.dp), onKey = sendKey)
                    Spacer(Modifier.width(6.dp))
                    HoldKey(Keys.DOWN, "▼", 54, RoundedCornerShape(12.dp), onKey = sendKey)
                    Spacer(Modifier.width(6.dp))
                    HoldKey(Keys.RIGHT, "▶", 54, RoundedCornerShape(12.dp), onKey = sendKey)
                }
            }
        }

        // 右下：确定 / 取消 + 快进 / 快走
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.End
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0x22000000))
                        .border(1.dp, Color(0x44FFFFFF), CircleShape)
                        .pointerInput(Keys.TAB) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                sendKey(Keys.TAB, true)
                                waitForUpOrCancellation()
                                sendKey(Keys.TAB, false)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Text("快进", color = Color(0xCCFFFFFF), fontSize = 11.sp) }

                Spacer(Modifier.width(10.dp))

                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0x22000000))
                        .border(1.dp, Color(0x44FFFFFF), CircleShape)
                        .pointerInput(Keys.SHIFT) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                sendKey(Keys.SHIFT, true)
                                waitForUpOrCancellation()
                                sendKey(Keys.SHIFT, false)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Text("快走", color = Color(0xCCFFFFFF), fontSize = 11.sp) }
            }

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                HoldKey(Keys.CANCEL, "取消", 56, CircleShape, 13, onKey = sendKey)
                Spacer(Modifier.width(10.dp))
                HoldKey(Keys.OK, "确定", 66, CircleShape, 15, onKey = sendKey)
            }
        }
    }
}

@Composable
private fun MenuButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 10.dp, top = 10.dp)
            .size(38.dp)
            .clip(CircleShape)
            .background(Color(0x44000000))
            .border(1.dp, Color(0x44FFFFFF), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val up = waitForUpOrCancellation()
                    if (up != null) onClick()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text("≡", color = Color(0xE6FFFFFF), fontSize = 20.sp)
    }
}

@Composable
private fun GameMenu(
    showPad: Boolean,
    onTogglePad: () -> Unit,
    onOpenCheat: () -> Unit,
    onReload: () -> Unit,
    onExit: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color(0x88000000))) {
        // 点击空白处关闭（放在最下层，避免拦住菜单按钮）
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        waitForUpOrCancellation()
                        onDismiss()
                    }
                }
        )
        Column(
            modifier = Modifier
                .padding(start = 14.dp, top = 56.dp)
                .width(210.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xF218181F))
                .border(1.dp, Color(0xFF3F3F4F), RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MenuRow(if (showPad) "隐藏虚拟按键" else "显示虚拟按键", onTogglePad)
            MenuRow("打开作弊面板", onOpenCheat)
            MenuRow("重新加载游戏", onReload)
            MenuRow("退出游戏", onExit)
        }
    }
}

@Composable
private fun MenuRow(text: String, onClick: () -> Unit) {    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x2AFFFFFF))
            .pointerInput(text) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val up = waitForUpOrCancellation()
                    if (up != null) onClick()
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text, color = Color(0xFFE9E9F2), fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
    }
}
