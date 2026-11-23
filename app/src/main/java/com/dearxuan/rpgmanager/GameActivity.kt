package com.dearxuan.rpgmanager

import android.annotation.SuppressLint
import android.net.Uri
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dearxuan.rpgmanager.core.AppSettings
import com.dearxuan.rpgmanager.core.PageHost
import com.dearxuan.rpgmanager.core.RuntimeMode
import com.dearxuan.rpgmanager.core.SafRepo
import com.dearxuan.rpgmanager.node.NodeRuntime
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.sin
import com.dearxuan.rpgmanager.server.GameServer
import com.dearxuan.rpgmanager.ui.theme.RPGManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 游戏页：WebView 显示游戏，其上叠加虚拟按键。
 *
 * 资源由哪个进程提供取决于设置里的「运行方式」：
 *   - 内嵌 Node（默认）：[NodeRuntime] 起的 Node 服务，页面里有真的 require/fs，
 *     存档写进游戏目录 save/，与桌面端互通；
 *   - 纯 WebView：[GameServer] 这个 Kotlin 回环服务，页面里没有 Node 环境，
 *     引擎走网页版分支，存档进 IndexedDB。给只认浏览器环境的游戏兜底。
 *
 * 两种模式下 WebView 都只是显示层。
 */
class GameActivity : ComponentActivity() {

    companion object {
        const val EXTRA_NAME = "game_name"
        /** 游戏根相对用户所选目录的路径，例如 "御神巫女" 或 "御神巫女/www" */
        const val EXTRA_LOCATION = "game_location"
        /** 用户所选目录的真实路径 */
        const val EXTRA_ROOT = "game_root"
        /** 游戏根在 SAF 里的 document id（WebView 模式用） */
        const val EXTRA_DOC_ID = "game_doc_id"
        private const val TAG = "GameActivity"
    }

    private var webView: WebView? = null
    private var pendingUrl: String? = null
    private var fileServer: GameServer? = null

    private var gameName: String = ""
    private var gameLocation: String = ""
    private var gamesRoot: String? = null
    private var gameDocId: String? = null

    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = AppSettings(this)
        gameName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        gameLocation = intent.getStringExtra(EXTRA_LOCATION).orEmpty()
        gamesRoot = intent.getStringExtra(EXTRA_ROOT)
        gameDocId = intent.getStringExtra(EXTRA_DOC_ID)

        if (gameLocation.isEmpty()) {
            Toast.makeText(this, "游戏目录已失效，请在列表里重新选择文件夹", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // Node 模式要真实路径；WebView 模式只要 SAF 的 docId
        val missing = when (settings.runtime) {
            RuntimeMode.NODE -> gamesRoot.isNullOrEmpty()
            RuntimeMode.WEBVIEW -> gameDocId.isNullOrEmpty()
        }
        if (missing) {
            Toast.makeText(this, "游戏目录信息不完整，请在列表里重新选择文件夹", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        hideSystemBars()

        setContent {
            RPGManagerTheme {
                GameScreen(
                    gameName = gameName,
                    initialShowPad = settings.virtualPad,
                    cheatEnabled = settings.cheatMenu,
                    onAttachWebView = { view ->
                        webView = view
                        pendingUrl?.let { view.loadUrl(it) }
                    },
                    onRequestReload = { reloadGame() },
                    onFinish = { finish() }
                )
            }
        }

        bootGame()
    }

    private fun bootGame() {
        if (settings.runtime == RuntimeMode.WEBVIEW) {
            bootWebViewMode()
        } else {
            bootNodeMode()
        }
    }

    private fun bootWebViewMode() {
        val docId = gameDocId
        if (docId.isNullOrEmpty()) {
            Toast.makeText(this, "缺少游戏目录信息", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Thread({
            val server = GameServer(this, SafRepo(this))
            server.gameDocId = docId
            server.cheatUiEnabled = settings.cheatMenu
            if (!server.start()) {
                runOnUiThread {
                    Toast.makeText(this, "本地资源服务启动失败", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            fileServer = server
            // 与桌面端一致：先确保 save 目录存在，方便直接拷存档进来
            try { SafRepo(this).ensureDir(docId, "save") } catch (e: Exception) { }
            val url = "${server.baseUrl}/g/index.html"
            Log.i(TAG, "加载游戏（WebView 模式）: $url")
            runOnUiThread { deliverUrl(url) }
        }, "rpg-webview-boot").apply { isDaemon = true }.start()
    }

    private fun bootNodeMode() {
        val root = gamesRoot ?: return
        Thread({
            val port = NodeRuntime.ensureStarted(this)
            if (port == 0) {
                runOnUiThread {
                    Toast.makeText(this, NodeRuntime.lastError() ?: "Node 运行时启动失败", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            if (!NodeRuntime.setGamesRoot(root)) {
                Log.w(TAG, "设置游戏根目录失败: $root")
            }
            NodeRuntime.setCheatUi(settings.cheatMenu)
            val url = "${NodeRuntime.baseUrl}/g/" + Uri.encode(gameLocation) + "/index.html"
            Log.i(TAG, "加载游戏: $url")
            runOnUiThread { deliverUrl(url) }
        }, "rpg-game-boot").apply { isDaemon = true }.start()
    }

    private fun deliverUrl(url: String) {
        pendingUrl = url
        val view = webView
        if (view != null) view.loadUrl(url) else pendingUrl = url
    }

    private fun reloadGame() {
        val url = pendingUrl ?: return
        webView?.loadUrl(url)
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
        fileServer?.stop()
        fileServer = null
        super.onDestroy()
    }
}

/** 虚拟按键对应的 keyCode（RPG Maker MV/MZ 的 Input.keyMapper） */
private object Keys {
    const val UP = 38
    const val DOWN = 40
    const val LEFT = 37
    const val RIGHT = 39
    const val ENTER = 13   // → 确定（MV/MZ 的 ok）
    const val SPACE = 32   // → 确定（MV/MZ 里空格同样是 ok）
    const val ESC = 27     // → 取消 / 打开菜单
    const val SHIFT = 16   // → 快走（MV/MZ 的 dash）
    const val CTRL = 17    // → 跳过对话（MV/MZ 的 control）
}

private const val CHEAT_BALL_DP = 56

/** 右下角按键簇的几何参数（单位 dp） */
private const val PAD_BIG = 72          // Enter 直径
private const val PAD_SMALL = 46        // 环绕键直径
private const val PAD_RADIUS = 112f     // 环绕半径（小键圆心到 Enter 圆心的距离）
private const val PAD_CLUSTER = PAD_BIG / 2f + PAD_RADIUS + PAD_SMALL / 2f

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GameScreen(
    gameName: String,
    initialShowPad: Boolean,
    cheatEnabled: Boolean,
    onAttachWebView: (WebView) -> Unit,
    onRequestReload: () -> Unit,
    onFinish: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showPad by remember { mutableStateOf(initialShowPad) }
    // 作弊面板打开时先收起虚拟按键（按键是原生层，会盖住面板）
    var cheatPanelOpen by remember { mutableStateOf(false) }

    // 悬浮球由原生绘制：只有原生 View 才能永远盖在虚拟按键之上，并且能全屏拖拽
    val density = LocalDensity.current.density
    val ballPx = with(LocalDensity.current) { CHEAT_BALL_DP.dp.toPx() }
    val ballMargin = with(LocalDensity.current) { 10.dp.toPx() }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var ballPos by remember { mutableStateOf<Offset?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var exitConfirm by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf<String?>(null) }

    val webHolder = remember { WebViewHolder() }
    val scope = rememberCoroutineScope()

    fun sendKey(code: Int, down: Boolean) {
        webHolder.view?.evaluateJavascript("window.__rpgKey&&window.__rpgKey($code,$down);", null)
    }

    BackHandler {
        if (menuOpen) menuOpen = false
        else if (exitConfirm) exitConfirm = false
        else exitConfirm = true
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { rootSize = it }) {

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

                    // 页面里的 cheat.js 通过它告诉原生「面板开了」，好把虚拟按键收起来
                    addJavascriptInterface(
                        PageHost { open -> post { cheatPanelOpen = open } },
                        "RPGHost"
                    )

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? = null

                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            loading = false
                            cheatPanelOpen = false
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
                            Log.d(
                                "RPGPage",
                                "[${consoleMessage.messageLevel()}] ${consoleMessage.message()}"
                            )
                            return true
                        }
                    }

                    onAttachWebView(this)
                    webHolder.view = this
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
                onRequestReload()
            }
        }

        if (!loading && errorText == null) {
            if (showPad && !cheatPanelOpen) {
                GamePad(sendKey = ::sendKey)
            }
            MenuButton(onClick = { menuOpen = true })

            if (cheatEnabled && !cheatPanelOpen && !menuOpen && rootSize != IntSize.Zero) {
                val maxX = (rootSize.width - ballPx).coerceAtLeast(0f)
                val maxY = (rootSize.height - ballPx).coerceAtLeast(0f)
                val anchor = ballPos ?: Offset(maxX - ballMargin, rootSize.height * 0.16f)
                val pos = Offset(anchor.x.coerceIn(0f, maxX), anchor.y.coerceIn(0f, maxY))

                Box(
                    modifier = Modifier
                        .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                        .size(CHEAT_BALL_DP.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0xFF7FDCFF), Color(0xFF0F7FBF))
                            )
                        )
                        .border(1.dp, Color(0x66FFFFFF), CircleShape)
                        // 拖动：全屏范围，不做任何保留区
                        .pointerInput(maxX, maxY) {
                            detectDragGestures { change, delta ->
                                change.consume()
                                val base = ballPos ?: pos
                                ballPos = Offset(
                                    (base.x + delta.x).coerceIn(0f, maxX),
                                    (base.y + delta.y).coerceIn(0f, maxY)
                                )
                            }
                        }
                        // 轻点：把当前位置告诉页面，再打开面板
                        .pointerInput(pos, density) {
                            detectTapGestures {
                                val cx = (pos.x + ballPx / 2f) / density
                                val cy = (pos.y + ballPx / 2f) / density
                                webHolder.view?.evaluateJavascript(
                                    "window.__rpgSetAnchor&&window.__rpgSetAnchor($cx,$cy);" +
                                        "window.__rpgOpenCheat&&window.__rpgOpenCheat();",
                                    null
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "作弊",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (menuOpen) {
                GameMenu(
                    showPad = showPad,
                    cheatEnabled = cheatEnabled,
                    onTogglePad = { showPad = !showPad },
                    onOpenCheat = {
                        menuOpen = false
                        webHolder.view?.evaluateJavascript(
                            "window.__rpgOpenCheat&&window.__rpgOpenCheat();", null
                        )
                    },
                    onReload = {
                        menuOpen = false
                        loading = true
                        onRequestReload()
                    },
                    onShowLog = {
                        menuOpen = false
                        scope.launch {
                            val text = withContext(Dispatchers.IO) { NodeRuntime.readLog(context) }
                            logText = text.ifEmpty { "（暂无日志）" }
                        }
                    },
                    onExit = { menuOpen = false; exitConfirm = true },
                    onDismiss = { menuOpen = false }
                )
            }
        }
    }

    logText?.let { text ->
        AlertDialog(
            onDismissRequest = { logText = null },
            title = { Text("Node 运行日志") },
            text = {
                Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                    Text(text, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { logText = null }) { Text("关闭") } }
        )
    }

    if (exitConfirm) {
        AlertDialog(
            onDismissRequest = { exitConfirm = false },
            title = { Text("退出游戏") },
            confirmButton = {
                TextButton(onClick = { exitConfirm = false; onFinish() }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { exitConfirm = false }) { Text("继续游戏") }
            }
        )
    }
}

/** WebView 持有者（在 AndroidView 工厂里赋值，不参与 Compose 状态） */
private class WebViewHolder {
    var view: WebView? = null
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
    modifier: Modifier = Modifier,
    onKey: (Int, Boolean) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
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

        // 右下：Enter 大圆压在角上，其余键绕着它排成四分之一圆弧
        // （像王者荣耀的技能键：从紧贴 Enter 的左侧开始，一路向上）
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 12.dp)
                .size(PAD_CLUSTER.dp)
        ) {
            HoldKey(
                Keys.ENTER, "Enter", PAD_BIG, CircleShape, 14,
                modifier = Modifier.align(Alignment.BottomEnd),
                onKey = sendKey
            )

            // 角度按数学坐标系：180° 在正左、90° 在正上
            val arc = listOf(
                Triple(Keys.SPACE, "Space", 180.0),
                Triple(Keys.SHIFT, "Shift", 150.0),
                Triple(Keys.CTRL, "Ctrl", 120.0),
                Triple(Keys.ESC, "Esc", 90.0)
            )
            val centerX = PAD_CLUSTER - PAD_BIG / 2f
            val centerY = PAD_CLUSTER - PAD_BIG / 2f

            arc.forEach { (code, label, degrees) ->
                val rad = degrees * PI / 180.0
                val cx = centerX + (cos(rad) * PAD_RADIUS).toFloat()
                val cy = centerY - (sin(rad) * PAD_RADIUS).toFloat()
                HoldKey(
                    code, label, PAD_SMALL, CircleShape,
                    fontSize = if (label == "Space") 10 else 12,
                    modifier = Modifier.offset(
                        (cx - PAD_SMALL / 2f).dp,
                        (cy - PAD_SMALL / 2f).dp
                    ),
                    onKey = sendKey
                )
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
    cheatEnabled: Boolean,
    onTogglePad: () -> Unit,
    onOpenCheat: () -> Unit,
    onReload: () -> Unit,
    onShowLog: () -> Unit,
    onExit: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color(0x88000000))) {
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
            if (cheatEnabled) {
                MenuRow("打开作弊面板", onOpenCheat)
            }
            MenuRow("重新加载游戏", onReload)
            MenuRow("运行日志", onShowLog)
            MenuRow("退出游戏", onExit)
        }
    }
}

@Composable
private fun MenuRow(text: String, onClick: () -> Unit) {
    Box(
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
