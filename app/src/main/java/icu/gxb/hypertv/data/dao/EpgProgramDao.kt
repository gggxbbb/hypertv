package icu.gxb.hypertv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import icu.gxb.hypertv.data.entity.EpgProgramEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpgProgramDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(programs: List<EpgProgramEntity>)

    /**
     * 查询 [start, end) 时间窗口内与频道有交集的节目（用于节目单/正在播放）。
     * 交集语义：节目开始早于窗口结束 且 节目结束晚于窗口开始。
     */
    @Query(
        "SELECT * FROM epg_programs WHERE channelEpgId = :channelEpgId " +
            "AND startTime < :end AND endTime > :start ORDER BY startTime ASC",
    )
    fun getByChannelAndTime(
        channelEpgId: String,
        start: Long,
        end: Long,
    ): Flow<List<EpgProgramEntity>>

    /** 一次性（非 Flow）查询单频道在 [start, end) 窗口内的节目，按 startTime 升序（EPG guide 用） */
    @Query(
        "SELECT * FROM epg_programs WHERE channelEpgId = :channelEpgId " +
            "AND startTime < :end AND endTime > :start ORDER BY startTime ASC",
    )
    suspend fun getByChannelEpgIdAndTimeOnce(
        channelEpgId: String,
        start: Long,
        end: Long,
    ): List<EpgProgramEntity>

    /**
     * 一次性（非 Flow）查询多个频道在 [start, end) 窗口内的节目，按 startTime 升序
     * （EPG now 用：一次取回所有频道的当前节目）。
     */
    @Query(
        "SELECT * FROM epg_programs WHERE channelEpgId IN (:channelEpgIds) " +
            "AND startTime < :end AND endTime > :start ORDER BY startTime ASC",
    )
    suspend fun getByChannelEpgIdsAndTimeOnce(
        channelEpgIds: List<String>,
        start: Long,
        end: Long,
    ): List<EpgProgramEntity>

    /** 删除这些 channelEpgId 的全部节目（刷新前清旧数据） */
    @Query("DELETE FROM epg_programs WHERE channelEpgId IN (:channelEpgIds)")
    suspend fun deleteByChannelEpgIds(channelEpgIds: List<String>)

    /** 清理已全部结束的过期节目（endTime < 阈值） */
    @Query("DELETE FROM epg_programs WHERE endTime < :threshold")
    suspend fun deleteExpired(threshold: Long)

    /** 聚合去重的 EPG 频道 id 列表（GET /api/epg/channels 用，按 id 升序） */
    @Query("SELECT DISTINCT channelEpgId FROM epg_programs ORDER BY channelEpgId ASC")
    suspend fun getDistinctChannelEpgIds(): List<String>
}
