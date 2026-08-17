package icu.gxb.hypertv.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import icu.gxb.hypertv.data.dao.AppConfigDao
import icu.gxb.hypertv.data.dao.ChannelDao
import icu.gxb.hypertv.data.dao.EpgChannelDao
import icu.gxb.hypertv.data.dao.EpgMatchRuleDao
import icu.gxb.hypertv.data.dao.EpgProgramDao
import icu.gxb.hypertv.data.dao.EpgSourceDao
import icu.gxb.hypertv.data.dao.GroupDao
import icu.gxb.hypertv.data.dao.PlaylistSourceDao
import icu.gxb.hypertv.data.entity.AppConfigEntity
import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.EpgChannelEntity
import icu.gxb.hypertv.data.entity.EpgMatchRuleEntity
import icu.gxb.hypertv.data.entity.EpgProgramEntity
import icu.gxb.hypertv.data.entity.EpgSourceEntity
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
        EpgSourceEntity::class,
        EpgMatchRuleEntity::class,
        EpgChannelEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class HypertvDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun groupDao(): GroupDao
    abstract fun playlistSourceDao(): PlaylistSourceDao
    abstract fun epgProgramDao(): EpgProgramDao
    abstract fun appConfigDao(): AppConfigDao
    abstract fun epgSourceDao(): EpgSourceDao
    abstract fun epgMatchRuleDao(): EpgMatchRuleDao
    abstract fun epgChannelDao(): EpgChannelDao

    companion object {
        /** v1 → v2：groups 表新增分组级 EPG 源字段（ticket 09），可空无需默认值。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `groups` ADD COLUMN `epgUrl` TEXT")
            }
        }

        /**
         * v2 → v3：
         * - channels 加 epgManual 列（默认 false，标记手动绑定的 epgId 不可被覆盖）
         * - 新表 epg_sources（全局多 EPG 源，url 唯一）
         * - 新表 epg_match_rules（EPG 频道关键字匹配规则）
         * - 迁移旧全局单源：app_config 的 epg_source_url 有值 → 迁为 epg_sources 第一条
         *   （enabled=true, orderIndex=0），随后删除该 key
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `epgManual` INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `epg_sources` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`url` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `orderIndex` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_epg_sources_url` ON `epg_sources` (`url`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `epg_match_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`epgChannelId` TEXT NOT NULL, `keyword` TEXT NOT NULL, `ruleType` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_epg_match_rules_epgChannelId` " +
                        "ON `epg_match_rules` (`epgChannelId`)",
                )

                // 旧全局单源 → epg_sources 第一条；无值/空值则跳过
                db.execSQL(
                    "INSERT INTO `epg_sources` (`url`, `enabled`, `orderIndex`) " +
                        "SELECT `value`, 1, 0 FROM `app_config` WHERE `key` = 'epg_source_url' AND TRIM(`value`) <> ''",
                )
                db.execSQL("DELETE FROM `app_config` WHERE `key` = 'epg_source_url'")
            }
        }

        /**
         * v3 → v4：
         * - 新表 epg_channels（EPG 频道目录：id=XMLTV 频道 id 主键、displayName、icon）。
         *   仅新增表，既有表/列不动，迁移即建表。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `epg_channels` (`id` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, `icon` TEXT, PRIMARY KEY(`id`))",
                )
            }
        }
    }
}
