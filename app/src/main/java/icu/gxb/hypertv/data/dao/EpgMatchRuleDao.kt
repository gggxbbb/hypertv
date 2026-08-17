package icu.gxb.hypertv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import icu.gxb.hypertv.data.entity.EpgMatchRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpgMatchRuleDao {

    @Query("SELECT * FROM epg_match_rules ORDER BY id ASC")
    fun getAll(): Flow<List<EpgMatchRuleEntity>>

    /** 一次性（非 Flow）读取全部规则，供刷新/管理 API 使用 */
    @Query("SELECT * FROM epg_match_rules ORDER BY id ASC")
    suspend fun getAllOnce(): List<EpgMatchRuleEntity>

    @Query("SELECT * FROM epg_match_rules WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): EpgMatchRuleEntity?

    /** 按 EPG 频道 id 查询其匹配规则 */
    @Query("SELECT * FROM epg_match_rules WHERE epgChannelId = :epgChannelId ORDER BY id ASC")
    suspend fun getByEpgChannelId(epgChannelId: String): List<EpgMatchRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: EpgMatchRuleEntity): Long

    @Query("DELETE FROM epg_match_rules WHERE id = :id")
    suspend fun deleteById(id: Long)
}
