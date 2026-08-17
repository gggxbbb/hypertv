package icu.gxb.hypertv.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import icu.gxb.hypertv.data.db.HypertvDatabase
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room 迁移测试（v1 → v4）：手工按导出的 schema（app/schemas/N.json）建库并预置数据，
 * 再以当前版本打开执行对应 Migration。Room 打开时会对迁移后的 schema 与编译期期望
 * （4.json）做身份校验，迁移不正确会抛 IllegalStateException。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HypertvMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"
    private val dbFile: File get() = context.getDatabasePath(dbName)

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migrate 1 to 2 preserves data and adds epgUrl column`() = runTest {
        createV1Database()

        // 以当前版本打开：触发 MIGRATION_1_2 → 2_3 → 3_4，Room 内部校验迁移结果与期望 schema 一致
        val db = Room.databaseBuilder(context, HypertvDatabase::class.java, dbName)
            .addMigrations(
                HypertvDatabase.MIGRATION_1_2,
                HypertvDatabase.MIGRATION_2_3,
                HypertvDatabase.MIGRATION_3_4,
            )
            .build()
        try {
            // 数据完整保留（通过 DAO 访问，同时证明迁移后 schema 可用）
            assertEquals("源 1", db.playlistSourceDao().getById("src-1")?.name)
            assertEquals("新闻", db.groupDao().getByNameOnce("新闻")?.name)
            // v2 新增列可空，旧行 epgUrl 为 NULL
            assertNull(db.groupDao().getByNameOnce("新闻")?.epgUrl)
            assertEquals("cctv1", db.channelDao().getByIdOnce("ch-1")?.epgId)
            // v3 迁移：旧全局源转入 epg_sources，app_config 键被删除
            assertEquals("http://x/epg.xml", db.epgSourceDao().getAllOnce().single().url)
            assertNull(db.appConfigDao().get("epg_source_url"))
            assertEquals(false, db.channelDao().getByIdOnce("ch-1")?.epgManual)
        } finally {
            db.close()
        }

        // 迁移后 epgUrl 列可写可读（分组级 EPG 源）
        val db2 = Room.databaseBuilder(context, HypertvDatabase::class.java, dbName).build()
        try {
            db2.groupDao().updateEpgUrl("新闻", "http://news.example.com/epg.xml")
            assertEquals("http://news.example.com/epg.xml", db2.groupDao().getByNameOnce("新闻")?.epgUrl)
        } finally {
            db2.close()
        }
    }

    @Test
    fun `migrate 2 to 3 adds tables and column and migrates legacy source`() = runTest {
        createV2Database()

        // 以当前版本打开：触发 MIGRATION_2_3 + MIGRATION_3_4，Room 内部校验迁移结果与期望 schema（4.json）一致
        val db = Room.databaseBuilder(context, HypertvDatabase::class.java, dbName)
            .addMigrations(HypertvDatabase.MIGRATION_2_3, HypertvDatabase.MIGRATION_3_4)
            .build()
        try {
            // 旧全局单源迁入 epg_sources 第一条（enabled=true, orderIndex=0），且 key 被删除
            val sources = db.epgSourceDao().getAllOnce()
            assertEquals(1, sources.size)
            assertEquals("http://x/epg.xml", sources[0].url)
            assertEquals(true, sources[0].enabled)
            assertEquals(0, sources[0].orderIndex)
            assertNull(db.appConfigDao().get("epg_source_url"))

            // channels 新列 epgManual 默认 false，旧行不受影响
            assertEquals(false, db.channelDao().getByIdOnce("ch-1")?.epgManual)
            // 新表可用
            assertTrue(db.epgMatchRuleDao().getAllOnce().isEmpty())
            // 既有数据完整保留
            assertEquals("源 1", db.playlistSourceDao().getById("src-1")?.name)
            assertEquals("cctv1", db.channelDao().getByIdOnce("ch-1")?.epgId)
        } finally {
            db.close()
        }
    }

    @Test
    fun `migrate 2 to 3 without legacy source leaves epg_sources empty`() = runTest {
        createV2Database(withLegacySource = false)

        val db = Room.databaseBuilder(context, HypertvDatabase::class.java, dbName)
            .addMigrations(HypertvDatabase.MIGRATION_2_3, HypertvDatabase.MIGRATION_3_4)
            .build()
        try {
            assertTrue(db.epgSourceDao().getAllOnce().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `migrate 3 to 4 adds epg_channels table and preserves data`() = runTest {
        createV3Database()

        // 以当前版本（4）打开：触发 MIGRATION_3_4，Room 内部校验迁移结果与期望 schema（4.json）一致
        val db = Room.databaseBuilder(context, HypertvDatabase::class.java, dbName)
            .addMigrations(HypertvDatabase.MIGRATION_3_4)
            .build()
        try {
            // 新表可用（初始为空，目录在首次刷新后写入）
            assertTrue(db.epgChannelDao().getAllOnce().isEmpty())
            // 既有数据完整保留
            assertEquals("源 1", db.playlistSourceDao().getById("src-1")?.name)
            assertEquals("cctv1", db.channelDao().getByIdOnce("ch-1")?.epgId)
            assertEquals("http://x/epg.xml", db.epgSourceDao().getAllOnce().single().url)
            assertEquals(1, db.epgMatchRuleDao().getAllOnce().size)
        } finally {
            db.close()
        }

        // 迁移后的 epg_channels 可写可读（刷新成功后 upsert 目录）
        val db2 = Room.databaseBuilder(context, HypertvDatabase::class.java, dbName)
            .addMigrations(HypertvDatabase.MIGRATION_3_4)
            .build()
        try {
            db2.epgChannelDao().upsertAll(
                listOf(
                    icu.gxb.hypertv.data.entity.EpgChannelEntity(
                        id = "1",
                        displayName = "CCTV1",
                        icon = "http://x/1.png",
                    ),
                ),
            )
            val catalog = db2.epgChannelDao().getAllOnce()
            assertEquals(1, catalog.size)
            assertEquals("CCTV1", catalog.single().displayName)
            assertEquals("http://x/1.png", catalog.single().icon)
        } finally {
            db2.close()
        }
    }

    /** 按 1.json 的 createSql 建 v1 库（含 room_master_table 身份哈希）并预置数据。 */
    private fun createV1Database() {
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlist_sources` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`type` TEXT NOT NULL, `url` TEXT NOT NULL, `lastImportedAt` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `groups` (`name` TEXT NOT NULL, `orderIndex` INTEGER NOT NULL, " +
                    "`isCollapsed` INTEGER NOT NULL, PRIMARY KEY(`name`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `channels` (`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, `url` TEXT NOT NULL, `groupName` TEXT NOT NULL, `logoUrl` TEXT, " +
                    "`orderIndex` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, `isHidden` INTEGER NOT NULL, " +
                    "`epgId` TEXT, `catchup` TEXT, `catchupDays` INTEGER, `catchupSource` TEXT, " +
                    "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`sourceId`) " +
                    "REFERENCES `playlist_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `epg_programs` (`id` TEXT NOT NULL, `channelEpgId` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `description` TEXT, `startTime` INTEGER NOT NULL, " +
                    "`endTime` INTEGER NOT NULL, `category` TEXT, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `app_config` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                    "PRIMARY KEY(`key`))",
            )
            // v1 schema 的索引（Room 校验表结构包含索引）
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_url` ON `channels` (`url`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_sourceId` ON `channels` (`sourceId`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_epg_programs_channelEpgId_startTime` " +
                    "ON `epg_programs` (`channelEpgId`, `startTime`)",
            )
            // Room 校验数据完整性所需的身份哈希（来自 1.json）
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                    "VALUES(42, '073040cee6eaa9c96f858a13e9a80fdb')",
            )
            db.version = 1

            db.execSQL(
                "INSERT INTO playlist_sources (id, name, type, url, lastImportedAt, createdAt) " +
                    "VALUES ('src-1', '源 1', 'url', 'http://x/m.m3u', 100, 50)",
            )
            db.execSQL("INSERT INTO groups (name, orderIndex, isCollapsed) VALUES ('新闻', 0, 0)")
            db.execSQL("INSERT INTO groups (name, orderIndex, isCollapsed) VALUES ('体育', 1, 1)")
            db.execSQL(
                "INSERT INTO channels (id, sourceId, name, url, groupName, logoUrl, orderIndex, isFavorite, " +
                    "isHidden, epgId, catchup, catchupDays, catchupSource, createdAt) VALUES " +
                    "('ch-1', 'src-1', 'CCTV-1', 'http://x/1.m3u8', '新闻', NULL, 0, 0, 0, 'cctv1', NULL, NULL, NULL, 100)",
            )
            db.execSQL("INSERT INTO app_config (key, value) VALUES ('epg_source_url', 'http://x/epg.xml')")
        }
    }

    /**
     * 按 2.json 的 createSql 建 v2 库（含 room_master_table 身份哈希）并预置数据。
     * @param withLegacySource 是否写入旧全局源键 epg_source_url（迁移转换测试用）
     */
    private fun createV2Database(withLegacySource: Boolean = true) {
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlist_sources` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`type` TEXT NOT NULL, `url` TEXT NOT NULL, `lastImportedAt` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `groups` (`name` TEXT NOT NULL, `orderIndex` INTEGER NOT NULL, " +
                    "`isCollapsed` INTEGER NOT NULL, `epgUrl` TEXT, PRIMARY KEY(`name`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `channels` (`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, `url` TEXT NOT NULL, `groupName` TEXT NOT NULL, `logoUrl` TEXT, " +
                    "`orderIndex` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, `isHidden` INTEGER NOT NULL, " +
                    "`epgId` TEXT, `catchup` TEXT, `catchupDays` INTEGER, `catchupSource` TEXT, " +
                    "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`sourceId`) " +
                    "REFERENCES `playlist_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `epg_programs` (`id` TEXT NOT NULL, `channelEpgId` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `description` TEXT, `startTime` INTEGER NOT NULL, " +
                    "`endTime` INTEGER NOT NULL, `category` TEXT, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `app_config` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                    "PRIMARY KEY(`key`))",
            )
            // v2 schema 的索引（Room 校验表结构包含索引）
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_url` ON `channels` (`url`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_sourceId` ON `channels` (`sourceId`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_epg_programs_channelEpgId_startTime` " +
                    "ON `epg_programs` (`channelEpgId`, `startTime`)",
            )
            // v2 身份哈希（来自 2.json）
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                    "VALUES(42, 'f2005d8cb10fd0e1bebea90936b0051e')",
            )
            db.version = 2

            db.execSQL(
                "INSERT INTO playlist_sources (id, name, type, url, lastImportedAt, createdAt) " +
                    "VALUES ('src-1', '源 1', 'url', 'http://x/m.m3u', 100, 50)",
            )
            db.execSQL("INSERT INTO groups (name, orderIndex, isCollapsed) VALUES ('新闻', 0, 0)")
            db.execSQL(
                "INSERT INTO channels (id, sourceId, name, url, groupName, logoUrl, orderIndex, isFavorite, " +
                    "isHidden, epgId, catchup, catchupDays, catchupSource, createdAt) VALUES " +
                    "('ch-1', 'src-1', 'CCTV-1', 'http://x/1.m3u8', '新闻', NULL, 0, 0, 0, 'cctv1', NULL, NULL, NULL, 100)",
            )
            if (withLegacySource) {
                db.execSQL("INSERT INTO app_config (key, value) VALUES ('epg_source_url', 'http://x/epg.xml')")
            }
        }
    }

    /** 按 3.json 的 createSql 建 v3 库（含 room_master_table 身份哈希）并预置数据。 */
    private fun createV3Database() {
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlist_sources` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`type` TEXT NOT NULL, `url` TEXT NOT NULL, `lastImportedAt` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `groups` (`name` TEXT NOT NULL, `orderIndex` INTEGER NOT NULL, " +
                    "`isCollapsed` INTEGER NOT NULL, `epgUrl` TEXT, PRIMARY KEY(`name`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `channels` (`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, `url` TEXT NOT NULL, `groupName` TEXT NOT NULL, `logoUrl` TEXT, " +
                    "`orderIndex` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, `isHidden` INTEGER NOT NULL, " +
                    "`epgId` TEXT, `epgManual` INTEGER NOT NULL, `catchup` TEXT, `catchupDays` INTEGER, " +
                    "`catchupSource` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`sourceId`) REFERENCES `playlist_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `epg_programs` (`id` TEXT NOT NULL, `channelEpgId` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `description` TEXT, `startTime` INTEGER NOT NULL, " +
                    "`endTime` INTEGER NOT NULL, `category` TEXT, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `app_config` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                    "PRIMARY KEY(`key`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `epg_sources` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`url` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `orderIndex` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `epg_match_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`epgChannelId` TEXT NOT NULL, `keyword` TEXT NOT NULL, `ruleType` TEXT NOT NULL)",
            )
            // v3 schema 的索引（Room 校验表结构包含索引）
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_url` ON `channels` (`url`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_channels_sourceId` ON `channels` (`sourceId`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_epg_programs_channelEpgId_startTime` " +
                    "ON `epg_programs` (`channelEpgId`, `startTime`)",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_epg_sources_url` ON `epg_sources` (`url`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_epg_match_rules_epgChannelId` " +
                    "ON `epg_match_rules` (`epgChannelId`)",
            )
            // v3 身份哈希（来自 3.json）
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                    "VALUES(42, '532d0f9f2dbda4d5461126c51650400c')",
            )
            db.version = 3

            db.execSQL(
                "INSERT INTO playlist_sources (id, name, type, url, lastImportedAt, createdAt) " +
                    "VALUES ('src-1', '源 1', 'url', 'http://x/m.m3u', 100, 50)",
            )
            db.execSQL("INSERT INTO groups (name, orderIndex, isCollapsed) VALUES ('新闻', 0, 0)")
            db.execSQL(
                "INSERT INTO channels (id, sourceId, name, url, groupName, logoUrl, orderIndex, isFavorite, " +
                    "isHidden, epgId, epgManual, catchup, catchupDays, catchupSource, createdAt) VALUES " +
                    "('ch-1', 'src-1', 'CCTV-1', 'http://x/1.m3u8', '新闻', NULL, 0, 0, 0, 'cctv1', 0, NULL, NULL, NULL, 100)",
            )
            db.execSQL("INSERT INTO app_config (key, value) VALUES ('epg_source_url', 'http://x/epg.xml')")
            db.execSQL(
                "INSERT INTO epg_sources (url, enabled, orderIndex) VALUES ('http://x/epg.xml', 1, 0)",
            )
            db.execSQL(
                "INSERT INTO epg_match_rules (epgChannelId, keyword, ruleType) VALUES ('cctv1', 'CCTV-1', 'prefix')",
            )
        }
    }
}
