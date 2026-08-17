package icu.gxb.hypertv.player

import android.content.Context
import android.media.AudioManager
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import icu.gxb.hypertv.data.repository.HypertvRepository
import icu.gxb.hypertv.di.ApplicationScope
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * 播放器装配（ticket 04）。全链路单例：单一 ExoPlayer 实例被 [ExoPlayerPlayerOperations]
 * 包装驱动，同时供 Compose 播放页的 PlayerView 直接附着（同一实例，ADR-0006）。
 */
@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @Provides
    @Singleton
    fun provideExoPlayer(@ApplicationContext context: Context): ExoPlayer =
        ExoPlayer.Builder(context).build()

    @Provides
    @Singleton
    fun providePlayerOperations(exoPlayer: ExoPlayer): PlayerOperations =
        ExoPlayerPlayerOperations(exoPlayer)

    /**
     * 媒体会话（ticket 03 目标①）：包住同一 ExoPlayer 实例，系统据此感知播放状态
     * （Android TV 抑制屏保/idle、系统媒体控制台展示）。media3-session 1.11.0 中
     * MediaSession 构造即 active（已注册到系统，旧版本另有 setActive/isActive，
     * 本版本已移除），release() 后 inactive。
     * 不做 MediaSessionService（用户选定轻量方案，无需新权限/前台服务/通知）。
     *
     * 生命周期：@Singleton 随 App 进程存在。release() 时机挂靠 HyperTvApplication.onTerminate
     * （见该处注释）；生产环境进程终结时系统直接回收，MediaSession 无需显式 release。
     */
    @Provides
    @Singleton
    fun provideMediaSession(
        @ApplicationContext context: Context,
        exoPlayer: ExoPlayer,
    ): MediaSession = MediaSession.Builder(context, exoPlayer).build()

    /**
     * 音频焦点控制器（ticket 03 目标②）：注入可替换的 AudioManager 以利单测，
     * 内部通过 Player.Listener 解耦播放时机（详见 AudioFocusController / AudioFocusPolicy）。
     */
    @Provides
    @Singleton
    fun provideAudioFocusController(
        exoPlayer: ExoPlayer,
        @ApplicationContext context: Context,
    ): AudioFocusController =
        AudioFocusController(exoPlayer, context.getSystemService(AudioManager::class.java))

    @Provides
    fun provideChannelSource(repository: HypertvRepository): ChannelSource =
        RepositoryChannelSource(repository)

    @Provides
    fun provideGroupSource(repository: HypertvRepository): GroupSource =
        RepositoryGroupSource(repository)

    @Provides
    fun provideFavoriteDataSource(repository: HypertvRepository): FavoriteDataSource =
        RepositoryFavoriteSource(repository)

    @Provides
    @Singleton
    fun provideFavoriteStore(
        dataSource: FavoriteDataSource,
        @ApplicationScope scope: CoroutineScope,
    ): FavoriteStore = FavoriteStore(dataSource, scope)

    @Provides
    fun provideLastChannelStore(repository: HypertvRepository): LastChannelStore =
        AppConfigLastChannelStore(repository)

    @Provides
    @Singleton
    fun providePlayerController(
        playerOperations: PlayerOperations,
        channelSource: ChannelSource,
        groupSource: GroupSource,
        lastChannelStore: LastChannelStore,
        @ApplicationScope scope: CoroutineScope,
    ): PlayerController = PlayerController(
        player = playerOperations,
        channelSource = channelSource,
        groupSource = groupSource,
        lastChannelStore = lastChannelStore,
        scope = scope,
    )
}
