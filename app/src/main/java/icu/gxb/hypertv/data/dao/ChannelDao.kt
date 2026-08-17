package icu.gxb.hypertv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import icu.gxb.hypertv.data.entity.ChannelEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ChannelDao {

    @Query("SELECT * FROM channels ORDER BY orderIndex ASC")
    abstract fun getAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE id = :id")
    abstract fun getById(id: String): Flow<ChannelEntity?>

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId ORDER BY orderIndex ASC")
    abstract fun getBySourceId(sourceId: String): Flow<List<ChannelEntity>>

    /** 增量合并按频道 URL 匹配（ADR-0004），URL 归一化由调用方负责 */
    @Query("SELECT * FROM channels WHERE url = :url LIMIT 1")
    abstract suspend fun getByUrl(url: String): ChannelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(channels: List<ChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(channel: ChannelEntity)

    @Update
    abstract suspend fun update(channel: ChannelEntity)

    @Query("DELETE FROM channels WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    abstract suspend fun deleteBySourceId(sourceId: String)

    @Query("UPDATE channels SET isFavorite = :isFavorite WHERE id = :id")
    abstract suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE channels SET isHidden = :isHidden WHERE id = :id")
    abstract suspend fun setHidden(id: String, isHidden: Boolean)

    @Query("SELECT * FROM channels WHERE isFavorite = 1 ORDER BY orderIndex ASC")
    abstract fun getFavorites(): Flow<List<ChannelEntity>>

    @Query("UPDATE channels SET orderIndex = :orderIndex WHERE id = :id")
    protected abstract suspend fun updateOrderIndex(id: String, orderIndex: Int)

    /** 批量重排：一次性事务内更新多个频道的 orderIndex */
    @Transaction
    open suspend fun reorder(newOrder: List<Pair<String, Int>>) {
        newOrder.forEach { (id, orderIndex) -> updateOrderIndex(id, orderIndex) }
    }
}
