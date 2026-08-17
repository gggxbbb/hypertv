package icu.gxb.hypertv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.ExperimentalTvMaterial3Api
import dagger.hilt.android.AndroidEntryPoint
import icu.gxb.hypertv.player.FavoriteStore
import icu.gxb.hypertv.player.PlayerController
import icu.gxb.hypertv.ui.BootstrapScreen
import icu.gxb.hypertv.ui.PlayerScreen
import icu.gxb.hypertv.ui.theme.HyperTVTheme
import javax.inject.Inject

/**
 * 首页路由（ticket 04）：
 * - 无任何可见频道 → BootstrapScreen（引导页，保留现有）
 * - 有频道 → PlayerScreen 自动播放（开机自动播放由 PlayerController 在 App 启动时驱动）
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var playerController: PlayerController

    /** 与播放器驱动同源的单一 ExoPlayer 实例（ADR-0006），供 PlayerView 附着 */
    @Inject
    lateinit var exoPlayer: ExoPlayer

    /** 收藏状态机（ticket 06），供播放页星号键收藏与收藏列表页渲染 */
    @Inject
    lateinit var favoriteStore: FavoriteStore

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HyperTVTheme {
                val channels by playerController.channels.collectAsState()
                if (channels.isEmpty()) {
                    BootstrapScreen()
                } else {
                    PlayerScreen(
                        player = exoPlayer,
                        controller = playerController,
                        favoriteStore = favoriteStore,
                    )
                }
            }
        }
    }
}
