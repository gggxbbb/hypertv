package icu.gxb.hypertv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import icu.gxb.hypertv.data.entity.EpgChannelEntity

@Dao
interface EpgChannelDao {

    /** 全部 EPG 频道目录（GET /api/epg/channels 用，排序在路由层按 id 数字序处理） */
    @Query("SELECT * FROM epg_channels")
    suspend fun getAllOnce(): List<EpgChannelEntity>

    /** 批量 upsert 目录（主键 id = XMLTV 频道 id，重复刷新幂等覆盖） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(channels: List<EpgChannelEntity>)
}
