package com.example.voicerec.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 录音数据访问对象
 */
@Dao
interface RecordingDao {
    /**
     * 获取所有日期（按日期倒序）
     */
    @Query("SELECT DISTINCT dayFolder FROM recordings ORDER BY dayFolder DESC")
    fun getAllDays(): Flow<List<String>>

    /**
     * 获取某日期下的所有小时（按小时倒序）
     */
    @Query("SELECT DISTINCT hourFolder FROM recordings WHERE dayFolder = :day ORDER BY hourFolder DESC")
    fun getHoursInDay(day: String): Flow<List<String>>

    /**
     * 获取某日期某小时下的所有录音（按时间倒序）
     */
    @Query("SELECT * FROM recordings WHERE dayFolder = :day AND hourFolder = :hour ORDER BY timestamp DESC")
    fun getRecordingsInHour(day: String, hour: String): Flow<List<Recording>>

    /**
     * 获取所有录音（按时间倒序）
     */
    @Query("SELECT * FROM recordings ORDER BY timestamp DESC")
    fun getAllRecordings(): Flow<List<Recording>>

    /**
     * 根据ID获取录音
     */
    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecordingById(id: Long): Recording?

    /**
     * 插入录音
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: Recording): Long

    /**
     * 删除录音
     */
    @Delete
    suspend fun delete(recording: Recording)

    /**
     * 根据路径删除录音
     */
    @Query("DELETE FROM recordings WHERE filePath = :path")
    suspend fun deleteByPath(path: String)

    /**
     * 删除某日期下的所有录音
     */
    @Query("DELETE FROM recordings WHERE dayFolder = :day")
    suspend fun deleteByDay(day: String): Int

    /**
     * 获取今日录音数量
     */
    @Query("SELECT COUNT(*) FROM recordings WHERE dayFolder = :today")
    suspend fun getTodayCount(today: String): Int

    /**
     * 获取今日录音总时长（毫秒）
     */
    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM recordings WHERE dayFolder = :today")
    suspend fun getTodayDuration(today: String): Long

    /**
     * 同步获取某日期下的所有录音（用于删除操作）
     */
    @Query("SELECT * FROM recordings WHERE dayFolder = :day ORDER BY timestamp DESC")
    suspend fun getRecordingsByDaySync(day: String): List<Recording>
}
