package icu.gxb.hypertv.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import icu.gxb.hypertv.data.repository.HypertvRepository
import icu.gxb.hypertv.epg.EpgRefresher
import icu.gxb.hypertv.epg.EpgRefreshService
import icu.gxb.hypertv.epg.EpgStore
import icu.gxb.hypertv.epg.EpgUrlFetcher
import icu.gxb.hypertv.server.HypertvEpgStore
import javax.inject.Singleton

/** EPG 体系（ticket 09）的 Hilt 绑定。 */
@Module
@InstallIn(SingletonComponent::class)
object EpgBindings {

    @Provides
    @Singleton
    fun provideEpgStore(repository: HypertvRepository): EpgStore = HypertvEpgStore(repository)

    @Provides
    @Singleton
    fun provideEpgRefreshService(store: EpgStore): EpgRefreshService =
        EpgRefresher(store = store, fetcher = EpgUrlFetcher::fetch)
}
