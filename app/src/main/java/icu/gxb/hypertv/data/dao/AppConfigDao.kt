package icu.gxb.hypertv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import icu.gxb.hypertv.data.entity.AppConfigEntity

@Dao
abstract class AppConfigDao {

    @Query("SELECT value FROM app_config WHERE key = :key")
    abstract suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insert(item: AppConfigEntity)

    /** 写入或覆盖键值 */
    open suspend fun put(key: String, value: String) = insert(AppConfigEntity(key = key, value = value))
}
