package icu.gxb.hypertv.data.db

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import icu.gxb.hypertv.data.dao.AppConfigDao
import icu.gxb.hypertv.data.dao.ChannelDao
import icu.gxb.hypertv.data.dao.EpgChannelDao
import icu.gxb.hypertv.data.dao.EpgMatchRuleDao
import icu.gxb.hypertv.data.dao.EpgProgramDao
import icu.gxb.hypertv.data.dao.EpgSourceDao
import icu.gxb.hypertv.data.dao.GroupDao
import icu.gxb.hypertv.data.dao.PlaylistSourceDao
import icu.gxb.hypertv.data.repository.HypertvRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HypertvDatabase =
        Room.databaseBuilder(context, HypertvDatabase::class.java, "hypertv.db")
            .addMigrations(
                HypertvDatabase.MIGRATION_1_2,
                HypertvDatabase.MIGRATION_2_3,
                HypertvDatabase.MIGRATION_3_4,
            )
            .build()

    @Provides
    fun provideChannelDao(db: HypertvDatabase): ChannelDao = db.channelDao()

    @Provides
    fun provideGroupDao(db: HypertvDatabase): GroupDao = db.groupDao()

    @Provides
    fun providePlaylistSourceDao(db: HypertvDatabase): PlaylistSourceDao = db.playlistSourceDao()

    @Provides
    fun provideEpgProgramDao(db: HypertvDatabase): EpgProgramDao = db.epgProgramDao()

    @Provides
    fun provideAppConfigDao(db: HypertvDatabase): AppConfigDao = db.appConfigDao()

    @Provides
    fun provideEpgSourceDao(db: HypertvDatabase): EpgSourceDao = db.epgSourceDao()

    @Provides
    fun provideEpgMatchRuleDao(db: HypertvDatabase): EpgMatchRuleDao = db.epgMatchRuleDao()

    @Provides
    fun provideEpgChannelDao(db: HypertvDatabase): EpgChannelDao = db.epgChannelDao()

    @Provides
    @Singleton
    fun provideRepository(
        channelDao: ChannelDao,
        groupDao: GroupDao,
        playlistSourceDao: PlaylistSourceDao,
        epgProgramDao: EpgProgramDao,
        appConfigDao: AppConfigDao,
        epgSourceDao: EpgSourceDao,
        epgMatchRuleDao: EpgMatchRuleDao,
        epgChannelDao: EpgChannelDao,
    ): HypertvRepository = HypertvRepository(
        channelDao = channelDao,
        groupDao = groupDao,
        playlistSourceDao = playlistSourceDao,
        epgProgramDao = epgProgramDao,
        appConfigDao = appConfigDao,
        epgSourceDao = epgSourceDao,
        epgMatchRuleDao = epgMatchRuleDao,
        epgChannelDao = epgChannelDao,
    )
}
