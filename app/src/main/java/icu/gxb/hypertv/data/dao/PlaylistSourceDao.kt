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

    @Query("SELECT * FROM playlist_sources WHERE id = :id")
    suspend fun getById(id: String): PlaylistSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: PlaylistSourceEntity)

    /** 删除直播源，channels 表经外键 CASCADE 级联删除其全部频道 */
    @Query("DELETE FROM playlist_sources WHERE id = :id")
    suspend fun deleteById(id: String)
}
