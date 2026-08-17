package icu.gxb.hypertv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import icu.gxb.hypertv.data.entity.GroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class GroupDao {

    @Query("SELECT * FROM groups ORDER BY orderIndex ASC")
    abstract fun getAll(): Flow<List<GroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(group: GroupEntity)

    @Query("DELETE FROM groups WHERE name = :name")
    abstract suspend fun deleteByName(name: String)

    @Query("UPDATE groups SET orderIndex = :orderIndex WHERE name = :name")
    protected abstract suspend fun updateOrderIndex(name: String, orderIndex: Int)

    /** 批量重排分组 */
    @Transaction
    open suspend fun reorder(newOrder: List<Pair<String, Int>>) {
        newOrder.forEach { (name, orderIndex) -> updateOrderIndex(name, orderIndex) }
    }
}
