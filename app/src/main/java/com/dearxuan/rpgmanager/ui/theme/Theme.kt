package com.dearxuan.rpgmanager.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 固定深色主题，配色沿用桌面端启动器（背景 #121214，强调色 #4FC3F7）。
 * 游戏启动器不需要跟随系统浅色，也不使用动态取色。
 */
private val RpgColorScheme = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF10131A),
    primaryContainer = Color(0xFF0F5C7A),
    onPrimaryContainer = Color(0xFFD6F1FF),
    secondary = Color(0xFF8CE99A),
    onSecondary = Color(0xFF10261A),
    background = Color(0xFF121214),
    onBackground = Color(0xFFE9E9F2),
    surface = Color(0xFF1E1E24),
    onSurface = Color(0xFFE9E9F2),
    surfaceVariant = Color(0xFF2A2A35),
    onSurfaceVariant = Color(0xFFB8BECC),
    outline = Color(0xFF3F3F4F),
    outlineVariant = Color(0xFF2F2F3A),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF2B0B0B)
)

@Composable
fun RPGManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RpgColorScheme,
        typography = Typography,
        content = content
    )
}
