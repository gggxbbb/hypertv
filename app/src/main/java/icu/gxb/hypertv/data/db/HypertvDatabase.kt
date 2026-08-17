package icu.gxb.hypertv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import icu.gxb.hypertv.data.dao.AppConfigDao
import icu.gxb.hypertv.data.dao.ChannelDao
import icu.gxb.hypertv.data.dao.EpgProgramDao
import icu.gxb.hypertv.data.dao.GroupDao
import icu.gxb.hypertv.data.dao.PlaylistSourceDao
import icu.gxb.hypertv.data.entity.AppConfigEntity
import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.data.entity.GroupEntity
import icu.gxb.hypertv.data.entity.PlaylistSourceEntity

/**
 * HyperTV 数据库。schema 变更递增 version 并补充 Migration。
 * schema 导出至 app/schemas（ksp arg room.schemaLocation），供迁移编写与校验参考。
 */
@Database(
    entities = [
        PlaylistSourceEntity::class,
        GroupEntity::class,
        ChannelEntity::class,
        EpgProgramEntity::class,
        AppConfigEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class HypertvDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun groupDao(): GroupDao
    abstract fun playlistSourceDao(): PlaylistSourceDao
    abstract fun epgProgramDao(): EpgProgramDao
    abstract fun appConfigDao(): AppConfigDao

    companion object {
        /** v1 → v2：groups 表新增分组级 EPG 源字段（ticket 09），可空无需默认值。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `groups` ADD COLUMN `epgUrl` TEXT")
            }
        }
    }
}
