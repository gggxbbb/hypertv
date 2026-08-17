package icu.gxb.hypertv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import icu.gxb.hypertv.data.entity.EpgSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpgSourceDao {

    /** 全部全局 EPG 源（按 orderIndex 升序） */
    @Query("SELECT * FROM epg_sources ORDER BY orderIndex ASC")
    fun getAll(): Flow<List<EpgSourceEntity>>

    /** 一次性（非 Flow）读取全部全局 EPG 源，供管理 API / 刷新等一次性任务使用 */
    @Query("SELECT * FROM epg_sources ORDER BY orderIndex ASC")
    suspend fun getAllOnce(): List<EpgSourceEntity>

    /** 一次性读取启用中的源（刷新时按 orderIndex 顺序拉取） */
    @Query("SELECT * FROM epg_sources WHERE enabled = 1 ORDER BY orderIndex ASC")
    suspend fun getEnabledOnce(): List<EpgSourceEntity>

    @Query("SELECT * FROM epg_sources WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): EpgSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: EpgSourceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<EpgSourceEntity>)

    @Query("DELETE FROM epg_sources WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM epg_sources")
    suspend fun clearAll()

    /** 单事务替换全部全局源（旧 PUT /api/epg/source 单源设置兼容） */
    @Transaction
    suspend fun replaceAll(sources: List<EpgSourceEntity>) {
        clearAll()
        if (sources.isNotEmpty()) upsertAll(sources)
    }
}
