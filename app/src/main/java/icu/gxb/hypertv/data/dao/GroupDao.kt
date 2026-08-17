package icu.gxb.hypertv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import icu.gxb.hypertv.data.db.HypertvDatabase
import icu.gxb.hypertv.data.entity.GroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class GroupDao(
    /** 供 @Transaction 方法访问同库其它 DAO（删除分组时清空频道分组名），Room 生成实现时注入 */
    protected val database: HypertvDatabase,
) {

    @Query("SELECT * FROM groups ORDER BY orderIndex ASC")
    abstract fun getAll(): Flow<List<GroupEntity>>

    /** 一次性（非 Flow）读取全部分组，供管理 API 等一次性任务使用 */
    @Query("SELECT * FROM groups ORDER BY orderIndex ASC")
    abstract suspend fun getAllOnce(): List<GroupEntity>

    /** 按名称一次性读取分组（EPG 分组刷新等一次性任务使用；不存在返回 null） */
    @Query("SELECT * FROM groups WHERE name = :name LIMIT 1")
    abstract suspend fun getByNameOnce(name: String): GroupEntity?

    /** 设置分组级 EPG 源 URL（null 表示清除覆盖，回退全局源） */
    @Query("UPDATE groups SET epgUrl = :epgUrl WHERE name = :name")
    abstract suspend fun updateEpgUrl(name: String, epgUrl: String?)

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

    /**
     * 删除分组并把组内频道归入"未分组"（groupName 置空），单事务原子完成。
     * WebUI 分组管理走这里，避免残留指向已删除分组的频道。
     */
    @Transaction
    open suspend fun deleteGroupWithChannels(name: String) {
        database.channelDao().clearGroup(name)
        deleteByName(name)
    }
}
