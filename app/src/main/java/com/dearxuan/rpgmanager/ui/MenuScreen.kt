package com.dearxuan.rpgmanager.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.ui.draw.blur
import com.dearxuan.rpgmanager.core.AppSettings
import com.dearxuan.rpgmanager.core.CoverLoader
import com.dearxuan.rpgmanager.core.CoverMode
import com.dearxuan.rpgmanager.core.ListMode
import com.dearxuan.rpgmanager.core.RuntimeMode
import com.dearxuan.rpgmanager.core.DecryptTasks
import com.dearxuan.rpgmanager.core.DocInfo
import com.dearxuan.rpgmanager.core.GameEntry
import com.dearxuan.rpgmanager.core.SafRepo
import com.dearxuan.rpgmanager.core.StorageAccess
import com.dearxuan.rpgmanager.node.NodeRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class TaskState(val title: String, val detail: String, val fraction: Float)

@Composable
fun MenuRoute(
    repo: SafRepo,
    resumeTick: Int,
    onPlay: (GameEntry, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val coverLoader = remember { CoverLoader(context, repo) }
    val tasks = remember { DecryptTasks(repo) }
    val settings = remember { AppSettings(context) }

    var runtime by remember { mutableStateOf(settings.runtime) }
    var virtualPad by remember { mutableStateOf(settings.virtualPad) }
    var cheatMenu by remember { mutableStateOf(settings.cheatMenu) }
    var coverMode by remember { mutableStateOf(settings.coverMode) }
    var listMode by remember { mutableStateOf(settings.listMode) }
    var showSettings by remember { mutableStateOf(false) }

    var games by remember { mutableStateOf<List<GameEntry>>(emptyList()) }
    var coverEpoch by remember { mutableStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var permissionOk by remember { mutableStateOf(repo.hasPermission()) }
    var realRoot by remember { mutableStateOf(repo.realRootPath()) }
    var allFilesOk by remember { mutableStateOf(StorageAccess.isAllFilesGranted()) }
    var permissionDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var task by remember { mutableStateOf<TaskState?>(null) }
    var taskJob by remember { mutableStateOf<Job?>(null) }
    var sheetGame by remember { mutableStateOf<GameEntry?>(null) }
    var saveGame by remember { mutableStateOf<GameEntry?>(null) }

    fun refresh(forceCovers: Boolean = false) {
        if (!repo.hasPermission()) {
            permissionOk = false
            scanning = false
            return
        }
        permissionOk = true
        realRoot = repo.realRootPath()
        allFilesOk = StorageAccess.isAllFilesGranted()
        scanning = true

        scope.launch {
            // 清缓存、扫描、清理都放 IO：刷新按钮点下去先让列表尽快回来
            if (forceCovers) {
                withContext(Dispatchers.IO) { coverLoader.clear() }
            }
            val list = try {
                withContext(Dispatchers.IO) { repo.scanGames() }
            } catch (e: Exception) {
                emptyList()
            }
            if (forceCovers) coverEpoch++
            games = list
            scanning = false

            // 列表已经显示，封面交给各张卡片在后台按需解码（慢的一步不挡列表）
            withContext(Dispatchers.IO) { coverLoader.prune(list) }

            // 权限齐了就顺手把 Node 起来，进游戏时不用再等（启动一次要一两秒）。
            // WebView 模式不碰 Node：省内存，也避免它占着端口。
            val root = realRoot
            if (runtime == RuntimeMode.NODE && root != null && StorageAccess.canReadWrite(root)) {
                withContext(Dispatchers.IO) {
                    if (NodeRuntime.ensureStarted(context) != 0) {
                        NodeRuntime.setGamesRoot(root)
                    }
                }
            }
        }
    }

    LaunchedEffect(resumeTick) { refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = with(repo) { setTree(uri) }
        if (!ok) {
            message = "无法获得该文件夹的读写权限，请换一个文件夹（或换用系统「文件」应用选择）"
            return@rememberLauncherForActivityResult
        }
        permissionOk = true
        refresh()
    }

    // 「所有文件访问权限」设置页：回来后会走 onResume → resumeTick 变化 → refresh
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh() }

    fun requestAllFiles() {
        for (intent in StorageAccess.allFilesSettingsIntents(context)) {
            try {
                settingsLauncher.launch(intent)
                return
            } catch (e: Exception) {
            }
        }
        message = "这个系统没有「所有文件访问权限」入口。请把游戏放到应用自己的目录，或换一台设备。"
    }

    /**
     * Node 模式要真实路径 + 「所有文件访问权限」（内嵌 Node 直接读磁盘）；
     * WebView 模式走 SAF，只凭已有的目录授权，不需要这些。
     */
    fun play(game: GameEntry) {
        if (runtime == RuntimeMode.WEBVIEW) {
            onPlay(game, realRoot.orEmpty())
            return
        }
        val root = realRoot
        if (root == null) {
            message = "这个文件夹没有可用的本地路径（可能来自网盘类应用）。\n" +
                "请选择内部存储或 SD 卡上的普通文件夹，或把运行方式改成「纯 WebView」。"
            return
        }
        if (!StorageAccess.isAllFilesGranted()) {
            permissionDialog = true
            return
        }
        if (!StorageAccess.canReadWrite(root)) {
            message = "无法读写 $root\n请在系统设置里检查文件权限。"
            return
        }
        allFilesOk = true
        onPlay(game, root)
    }

    fun startDecrypt(game: GameEntry, full: Boolean) {
        if (taskJob?.isActive == true) return
        taskJob = scope.launch {
            task = TaskState(if (full) "正在克隆解密版游戏" else "正在解密图片资产", "准备中…", 0f)
            var result: String?
            try {
                result = withContext(Dispatchers.IO) {
                    if (full) {
                        tasks.cloneDecryptedGame(game, repo.rootDocId().orEmpty()) { p ->
                            task = TaskState(p.title, "${p.current}/${p.total}  ${p.detail}", p.fraction)
                        }
                    } else {
                        tasks.decryptImages(game) { p ->
                            task = TaskState(p.title, "${p.current}/${p.total}  ${p.detail}", p.fraction)
                        }
                    }
                }
            } catch (e: CancellationException) {
                result = null
            } catch (e: Exception) {
                result = "处理失败：${e.message}"
            }
            task = null
            if (result != null) {
                message = result
                refresh()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 设置页是全屏的，打开时不再渲染列表：既不浪费合成，也避免点击穿透到卡片
        if (!showSettings) Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            MenuHeader(
                gameCount = games.size,
                onRefresh = { refresh(forceCovers = true) },
                onChangeFolder = {
                    val root = repo.rootDocId()
                    if (repo.hasPermission() && root != null) {
                        openDocFolder(context, repo, root)
                    } else {
                        picker.launch(null)
                    }
                },
                onOpenSettings = { showSettings = true }
            )

            if (runtime == RuntimeMode.NODE && permissionOk && !allFilesOk) {
                PermissionBanner(onClick = { requestAllFiles() })
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    !permissionOk -> Onboarding(onPick = { picker.launch(null) })
                    scanning -> CenterLoading("正在扫描游戏…")
                    games.isEmpty() -> NoGames(
                        onPick = { picker.launch(null) },
                        onRefresh = { refresh(forceCovers = true) }
                    )

                    listMode == ListMode.SINGLE -> LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        lazyItems(games, key = { it.docId }) { game ->
                            GameListRow(
                                game = game,
                                onClick = { play(game) },
                                onLongClick = { sheetGame = game }
                            )
                        }
                    }

                    // 两两成行自己排版：同一行用 IntrinsicSize.Max 对齐高度，
                    // 左边标题多一行时右边跟着一起长高，两张卡永远一样大。
                    // （LazyVerticalGrid 里同一行的卡片各算各的高度，底部会对不齐。）
                    else -> LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        lazyItems(games.chunked(2), key = { row -> row.first().docId }) { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Max),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                row.forEach { game ->
                                    GameCard(
                                        game = game,
                                        coverLoader = coverLoader,
                                        coverMode = coverMode,
                                        coverEpoch = coverEpoch,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                        onClick = { play(game) },
                                        onLongClick = { sheetGame = game }
                                    )
                                }
                                // 单数时补一个占位，最后一张卡不会被拉成整行宽
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        if (showSettings) {
            SettingsScreen(
                runtime = runtime,
                onRuntime = { runtime = it; settings.runtime = it },
                virtualPad = virtualPad,
                onVirtualPad = { virtualPad = it; settings.virtualPad = it },
                cheatMenu = cheatMenu,
                onCheatMenu = { cheatMenu = it; settings.cheatMenu = it },
                coverMode = coverMode,
                onCoverMode = { coverMode = it; settings.coverMode = it },
                listMode = listMode,
                onListMode = { listMode = it; settings.listMode = it },
                onDismiss = { showSettings = false }
            )
        }
    }

    sheetGame?.let { game ->
        GameActionsSheet(
            game = game,
            onPlay = { sheetGame = null; play(game) },
            onDecryptImages = { sheetGame = null; startDecrypt(game, false) },
            onCloneGame = { sheetGame = null; startDecrypt(game, true) },
            onEditSaves = { sheetGame = null; saveGame = game },
            onOpenFolder = { sheetGame = null; openFolder(context, repo, game) },
            onDismiss = { sheetGame = null }
        )
    }

    saveGame?.let { game ->
        SaveManagerDialog(repo = repo, game = game, onDismiss = { saveGame = null })
    }

    if (permissionDialog) {
        AlertDialog(
            onDismissRequest = { permissionDialog = false },
            title = { Text("还需要「所有文件访问权限」") },
            text = {
                Text(
                    "内嵌的 Node 需要它才能直接读取游戏文件，在设置里打开后返回即可。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { permissionDialog = false; requestAllFiles() }) {
                    Text("去开启")
                }
            },
            dismissButton = {
                TextButton(onClick = { permissionDialog = false }) { Text("以后再说") }
            }
        )
    }

    task?.let { state ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(state.title) },
            text = {
                Column {
                    if (state.fraction > 0f) {
                        LinearProgressIndicator(
                            progress = { state.fraction },
                            modifier = Modifier.fillMaxWidth().height(6.dp)
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(state.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { taskJob?.cancel() }) { Text("取消") }
            }
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("提示") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } }
        )
    }
}

// =====================================================================
// 顶栏 / 空态
// =====================================================================

@Composable
private fun MenuHeader(
    gameCount: Int,
    onRefresh: () -> Unit,
    onChangeFolder: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 10.dp, top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "RPG Maker 游戏库",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (gameCount > 0) "$gameCount 个游戏 · 点击开始，长按管理" else "把所有游戏放在同一个文件夹里",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
        GlyphButton("⟳", onRefresh)
        Spacer(Modifier.width(6.dp))
        GlyphButton("📁", onChangeFolder)
        Spacer(Modifier.width(6.dp))
        GlyphButton("⚙", onOpenSettings)
    }
}

@Composable
private fun PermissionBanner(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x33FFB74D))
            .border(1.dp, Color(0x66FFB74D), RoundedCornerShape(10.dp))
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "还差「所有文件访问权限」，点这里开启后才能启动游戏",
            fontSize = 12.sp,
            color = Color(0xFFFFD08A),
            modifier = Modifier.weight(1f)
        )
        Text("去开启 ›", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFD08A))
    }
}

@Composable
private fun GlyphButton(text: String, onClick: () -> Unit) {
    // 保持原来的 40dp 尺寸，按下时只把底色换成主色，不做缩放/涟漪
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = if (pressed) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (pressed) Color.White else MaterialTheme.colorScheme.onSurface
    val borderColor = if (pressed) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outline

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontSize = 16.sp)
    }
}

@Composable
private fun CenterLoading(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
            Spacer(Modifier.height(14.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Onboarding(onPick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) { Text("▶", fontSize = 34.sp, color = MaterialTheme.colorScheme.primary) }

        Spacer(Modifier.height(18.dp))
        Text("先选一个存放游戏的文件夹", fontSize = 18.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(16.dp))

        StepHint("1", "点下面的按钮，选择一个文件夹（例如 内部存储/Games）。")
        StepHint("2", "把游戏的 www 文件夹里的所有内容，放进这个文件夹下一个以游戏名命名的子文件夹里。")
        StepHint("3", "按提示开启「所有文件访问权限」。")
        StepHint("4", "返回本应用，游戏会自动出现在列表中，点击即可开始游玩。")

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onPick,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) { Text("选择游戏文件夹", fontSize = 15.sp, fontWeight = FontWeight.Bold) }

        Spacer(Modifier.height(14.dp))
        Text(
            "目录结构示例：\nGames/御神巫女/index.html\nGames/御神巫女/data、img、audio …",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun StepHint(index: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(index, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun NoGames(onPick: () -> Unit, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("这个文件夹里还没有游戏", fontSize = 16.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(10.dp))
        Text(
            "每个游戏的子文件夹里必须包含 index.html。\n" +
                "如果游戏多套了一层 www，也能自动识别。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onRefresh, shape = RoundedCornerShape(10.dp)) { Text("重新扫描") }
            TextButton(onClick = onPick) { Text("更换文件夹") }
        }
    }
}

// =====================================================================
// 游戏卡片
// =====================================================================

@Composable
private fun GameCard(
    game: GameEntry,
    coverLoader: CoverLoader,
    coverMode: CoverMode,
    coverEpoch: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val showCover = coverMode != CoverMode.OFF
    val cover by produceState<Bitmap?>(
        initialValue = if (showCover) coverLoader.cached(game) else null,
        game.docId, showCover, coverEpoch
    ) {
        if (showCover && value == null) {
            value = withContext(Dispatchers.IO) { coverLoader.load(game, 480) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .pointerInput(game.docId) {
                detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() })
            }
            .padding(8.dp)
    ) {
        if (showCover) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF15151B)),
                contentAlignment = Alignment.Center
            ) {
                val bmp = cover
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (coverMode == CoverMode.BLUR) Modifier.blur(12.dp)
                                else Modifier
                            )
                    )
                } else {
                    Text(
                        game.name.take(1),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                game.name,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A1A22))
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    game.name,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun GameListRow(
    game: GameEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .pointerInput(game.docId) {
                detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() })
            }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                game.name,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (game.location != game.name) {
                Text(
                    game.location,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// =====================================================================
// 长按菜单
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameActionsSheet(
    game: GameEntry,
    onPlay: () -> Unit,
    onDecryptImages: () -> Unit,
    onCloneGame: () -> Unit,
    onEditSaves: () -> Unit,
    onOpenFolder: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 26.dp)) {
            Text(game.name, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface)
            Text(game.location, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(6.dp))

            SheetAction("开始游戏", onPlay)
            SheetAction("存档管理", onEditSaves)
            SheetAction("解密图片资产 → decrypt_img/", onDecryptImages)
            SheetAction("解密完整游戏副本（占用双倍空间）", onCloneGame)
            SheetAction("在系统文件管理器中打开", onOpenFolder)
        }
    }
}

@Composable
private fun SheetAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(text) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

// =====================================================================
// 存档管理
// =====================================================================

@Composable
private fun SaveManagerDialog(repo: SafRepo, game: GameEntry, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var saves by remember { mutableStateOf<List<DocInfo>?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun loadSaves() {
        scope.launch {
            saves = withContext(Dispatchers.IO) {
                val saveDir = repo.child(game.docId, "save")?.takeIf { it.isDir } ?: return@withContext emptyList()
                repo.children(saveDir.docId).values
                    .filter { !it.isDir && (it.name.endsWith(".rpgsave") || it.name.endsWith(".rmmzsave")) }
                    .sortedBy { it.name }
            }
        }
    }

    LaunchedEffect(game.docId) { loadSaves() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("存档 - ${game.name}", fontSize = 16.sp) },
        text = {
            Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                val list = saves
                when {
                    list == null -> Text("读取中…", fontSize = 13.sp)
                    list.isEmpty() -> Text(
                        "还没有存档。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> list.forEach { doc ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(doc.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    "${formatSize(doc.size)} · ${formatTime(doc.lastModified)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { backupSave(repo, game, doc) }
                                    info = if (ok) "已备份 ${doc.name}" else "备份失败"
                                    loadSaves()
                                }
                            }) { Text("备份", fontSize = 12.sp) }
                        }
                    }
                }
                info?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = { loadSaves() }) { Text("刷新") } }
    )
}

private fun backupSave(repo: SafRepo, game: GameEntry, doc: DocInfo): Boolean {
    return try {
        val saveDir = repo.child(game.docId, "save")?.takeIf { it.isDir } ?: return false
        val bytes = repo.readBytes(doc.docId) ?: return false
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        repo.writeFile(saveDir.docId, "${doc.name}.backup_$stamp", "application/octet-stream", bytes) != null
    } catch (e: Exception) {
        false
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatTime(millis: Long): String =
    if (millis <= 0) "未知时间"
    else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))

// =====================================================================
// 其他
// =====================================================================

/** 尽量用系统文件管理器打开游戏目录（部分 ROM 不支持，失败就提示） */
private fun openFolder(context: Context, repo: SafRepo, game: GameEntry) {
    openDocFolder(context, repo, game.docId)
}

/** 打开任意 SAF 文档目录；未传 docId 时由调用方负责先选文件夹 */
private fun openDocFolder(context: Context, repo: SafRepo, docId: String) {
    val uri: Uri = repo.documentUri(docId) ?: return
    val candidates = listOf(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        Intent(Intent.ACTION_VIEW).apply {
            setData(uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent)
            return
        } catch (e: Exception) {
        }
    }
    Toast.makeText(context, "系统没有可打开该目录的文件管理器", Toast.LENGTH_SHORT).show()
}
