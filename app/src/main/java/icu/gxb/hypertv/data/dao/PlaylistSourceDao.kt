package icu.gxb.hypertv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import icu.gxb.hypertv.data.entity.PlaylistSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistSourceDao {

    @Query("SELECT * FROM playlist_sources ORDER BY createdAt ASC")
    fun getAll(): Flow<List<PlaylistSourceEntity>>

    /** 一次性（非 Flow）读取全部直播源，供管理 API 等一次性任务使用 */
    @Query("SELECT * FROM playlist_sources ORDER BY createdAt ASC")
    suspend fun getAllOnce(): List<PlaylistSourceEntity>

    @Query("SELECT * FROM playlist_sources WHERE id = :id")
    suspend fun getById(id: String): PlaylistSourceEntity?

    /** 按 URL 精确匹配直播源（URL 需调用方归一化），用于重复导入同源时增量合并 */
    @Query("SELECT * FROM playlist_sources WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): PlaylistSourceEntity?

    /** 按 (type, name) 匹配直播源，用于文件上传重复导入同源时增量合并（ADR-0004） */
    @Query("SELECT * FROM playlist_sources WHERE type = :type AND name = :name LIMIT 1")
    suspend fun getByTypeAndName(type: String, name: String): PlaylistSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: PlaylistSourceEntity)

    /** 删除直播源，channels 表经外键 CASCADE 级联删除其全部频道 */
    @Query("DELETE FROM playlist_sources WHERE id = :id")
    suspend fun deleteById(id: String)
}
