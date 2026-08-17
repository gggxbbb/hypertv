package icu.gxb.hypertv.player

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
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
