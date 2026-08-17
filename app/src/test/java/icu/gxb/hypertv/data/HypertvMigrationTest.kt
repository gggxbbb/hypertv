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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room 迁移测试（v1 → v2，ticket 09）：手工按导出的 v1 schema（app/schemas/1.json）
 * 建库并预置数据，再以 v2 打开执行 MIGRATION_1_2。Room 打开时会对迁移后的 schema
 * 与编译期期望（2.json）做身份校验，迁移不正确会抛 IllegalStateException。
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

        // 以 v2 打开：触发 MIGRATION_1_2，Room 内部校验迁移结果与期望 schema 一致
        val db = Room.databaseBuilder(context, HypertvDatabase::class.java, dbName)
            .addMigrations(HypertvDatabase.MIGRATION_1_2)
            .build()
        try {
            // 数据完整保留（通过 DAO 访问，同时证明迁移后 schema 可用）
            assertEquals("源 1", db.playlistSourceDao().getById("src-1")?.name)
            assertEquals("新闻", db.groupDao().getByNameOnce("新闻")?.name)
            // 新列可空，旧行 epgUrl 为 NULL
            assertNull(db.groupDao().getByNameOnce("新闻")?.epgUrl)
            assertEquals("cctv1", db.channelDao().getByIdOnce("ch-1")?.epgId)
            assertEquals("http://x/epg.xml", db.appConfigDao().get("epg_source_url"))
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
}
