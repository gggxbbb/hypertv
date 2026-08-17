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

    /** 清理已全部结束的过期节目（endTime < 阈值） */
    @Query("DELETE FROM epg_programs WHERE endTime < :threshold")
    suspend fun deleteExpired(threshold: Long)
}
