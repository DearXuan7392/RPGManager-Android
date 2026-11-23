package com.dearxuan.rpgmanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dearxuan.rpgmanager.core.CoverMode
import com.dearxuan.rpgmanager.core.ListMode
import com.dearxuan.rpgmanager.core.RuntimeMode

/**
 * 设置页（覆盖在菜单之上，全屏）。
 *
 * 所有改动即时生效并写进 SharedPreferences：列表外观立刻重排，
 * 运行方式/游戏内开关在下次进入游戏时生效。
 */
@Composable
fun SettingsScreen(
    runtime: RuntimeMode,
    onRuntime: (RuntimeMode) -> Unit,
    virtualPad: Boolean,
    onVirtualPad: (Boolean) -> Unit,
    cheatMenu: Boolean,
    onCheatMenu: (Boolean) -> Unit,
    coverMode: CoverMode,
    onCoverMode: (CoverMode) -> Unit,
    listMode: ListMode,
    onListMode: (ListMode) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 18.dp, top = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
                contentAlignment = Alignment.Center
            ) {
                Text("‹", fontSize = 26.sp, color = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "设置",
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            SectionTitle("运行方式")

            ChoiceGroup(
                options = listOf(
                    RuntimeMode.NODE to "内嵌 Node",
                    RuntimeMode.WEBVIEW to "纯 WebView"
                ),
                selected = runtime,
                onSelect = onRuntime
            )
            if (runtime == RuntimeMode.WEBVIEW) {
                HintText("网页版存档存在应用内部，与桌面端不互通。")
            }

            SectionSpacer()
            SectionTitle("游戏内")

            SwitchRow(
                title = "虚拟按键",
                desc = "方向键，以及 Enter / Space / Shift / Ctrl / Esc",
                checked = virtualPad,
                onChange = onVirtualPad
            )
            SwitchRow(
                title = "作弊菜单",
                desc = "画面上的悬浮球面板",
                checked = cheatMenu,
                onChange = onCheatMenu
            )

            SectionSpacer()
            SectionTitle("列表外观")

            ChoiceGroup(
                options = listOf(
                    CoverMode.TITLE to "原图",
                    CoverMode.BLUR to "模糊",
                    CoverMode.OFF to "不显示"
                ),
                selected = coverMode,
                onSelect = onCoverMode
            )
            if (coverMode == CoverMode.OFF) {
                HintText("不加载封面，游戏名直接显示在卡片里。")
            }

            SectionSpacer()

            ChoiceGroup(
                options = listOf(
                    ListMode.GRID to "双列",
                    ListMode.SINGLE to "单列"
                ),
                selected = listMode,
                onSelect = onListMode
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "运行方式与游戏内开关下次进游戏时生效。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)
    )
}

@Composable
private fun SectionSpacer() {
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun HintText(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, start = 2.dp)
    )
}

@Composable
private fun <T> ChoiceGroup(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .border(
                        1.dp,
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(10.dp)
                    )
                    .pointerInput(value, selected) { detectTapGestures { onSelect(value) } },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                desc,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, end = 8.dp)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}
