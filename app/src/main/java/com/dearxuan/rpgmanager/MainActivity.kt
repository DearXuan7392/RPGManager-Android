package com.dearxuan.rpgmanager

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import com.dearxuan.rpgmanager.core.SafRepo
import com.dearxuan.rpgmanager.ui.MenuRoute
import com.dearxuan.rpgmanager.ui.theme.RPGManagerTheme

/**
 * 菜单页：扫描用户目录下的游戏并展示（对应桌面端 menu/ 那一套）。
 * 点击卡片进入 [GameActivity] 实际游玩。
 */
class MainActivity : ComponentActivity() {

    private lateinit var repo: SafRepo

    /** 从游戏返回时自增，触发列表重扫（游戏目录里可能刚生出新存档/新游戏） */
    private val resumeTick = mutableIntStateOf(0)
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = SafRepo(this)

        setContent {
            RPGManagerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MenuRoute(
                        repo = repo,
                        resumeTick = resumeTick.intValue,
                        onPlay = { game, root ->
                            startActivity(
                                Intent(this, GameActivity::class.java).apply {
                                    putExtra(GameActivity.EXTRA_NAME, game.name)
                                    putExtra(GameActivity.EXTRA_LOCATION, game.location)
                                    putExtra(GameActivity.EXTRA_ROOT, root)
                                    putExtra(GameActivity.EXTRA_DOC_ID, game.docId)
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
            return
        }
        resumeTick.intValue++
    }
}
