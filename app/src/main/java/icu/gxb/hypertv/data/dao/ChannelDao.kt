package icu.gxb.hypertv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import icu.gxb.hypertv.data.db.HypertvDatabase
import icu.gxb.hypertv.data.entity.ChannelEntity
import icu.gxb.hypertv.data.entity.PlaylistSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ChannelDao(
    /** 供 @Transaction 方法访问同库其它 DAO（直播源 upsert），Room 生成实现时注入 */
    protected val database: HypertvDatabase,
) {

    @Query("SELECT * FROM channels ORDER BY orderIndex ASC")
    abstract fun getAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE id = :id")
    abstract fun getById(id: String): Flow<ChannelEntity?>

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId ORDER BY orderIndex ASC")
    abstract fun getBySourceId(sourceId: String): Flow<List<ChannelEntity>>

    /** 一次性（非 Flow）按源查询，供导入等一次性任务使用 */
    @Query("SELECT * FROM channels WHERE sourceId = :sourceId ORDER BY orderIndex ASC")
    abstract suspend fun getBySourceIdOnce(sourceId: String): List<ChannelEntity>

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

    /**
     * 导入事务：写入/更新直播源 + 批量写频道（新增/更新/隐藏）原子完成（ADR-0004）。
     * 需在 upsert 频道前先保证 playlist_sources 中 source 存在（外键约束）。
     */
    @Transaction
    open suspend fun persistImport(
        source: PlaylistSourceEntity,
        inserts: List<ChannelEntity>,
        updates: List<ChannelEntity>,
        hides: List<ChannelEntity>,
    ) {
        database.playlistSourceDao().upsert(source)
        if (inserts.isNotEmpty()) upsertAll(inserts)
        if (updates.isNotEmpty()) upsertAll(updates)
        if (hides.isNotEmpty()) upsertAll(hides)
    }
}
