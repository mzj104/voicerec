package com.example.voicerec.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * 录音仓库
 * 负责数据库和文件操作的协调
 */
class RecordingRepository(private val context: Context) {
    private val dao = AppDatabase.getInstance(context).recordingDao()
    private val recordingsDir = File(context.filesDir, "recordings")

    init {
        // 确保录音目录存在
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
        }
    }

    /**
     * 获取所有日期
     */
    fun getAllDays(): Flow<List<String>> = dao.getAllDays()

    /**
     * 获取某日期下的所有小时
     */
    fun getHoursInDay(day: String): Flow<List<String>> = dao.getHoursInDay(day)

    /**
     * 获取某日期某小时下的所有录音
     */
    fun getRecordingsInHour(day: String, hour: String): Flow<List<Recording>> =
        dao.getRecordingsInHour(day, hour)

    /**
     * 获取所有录音
     */
    fun getAllRecordings(): Flow<List<Recording>> = dao.getAllRecordings()

    /**
     * 根据ID获取录音
     */
    suspend fun getRecordingById(id: Long): Recording? = dao.getRecordingById(id)

    /**
     * 创建录音文件并保存到数据库
     * @return 保存的Recording对象，失败返回null
     */
    suspend fun saveRecording(
        fileName: String,
        durationMs: Long,
        fileSizeBytes: Long
    ): Recording? {
        val file = getFileForRecording(fileName)
        if (!file.exists()) {
            return null
        }

        val dayFolder = getDayFolder()
        val hourFolder = getHourFolder()
        val timestamp = System.currentTimeMillis()

        val recording = Recording(
            fileName = fileName,
            filePath = file.absolutePath,
            dayFolder = dayFolder,
            hourFolder = hourFolder,
            timestamp = timestamp,
            durationMs = durationMs,
            fileSizeBytes = fileSizeBytes
        )

        val id = dao.insert(recording)
        return recording.copy(id = id)
    }

    /**
     * 删除录音（包括数据库记录和文件）
     */
    suspend fun deleteRecording(recording: Recording) {
        dao.delete(recording)
        // 删除文件
        File(recording.filePath).delete()
        // 清理空文件夹
        cleanEmptyFolders(recording.dayFolder, recording.hourFolder)
    }

    /**
     * 根据日期删除所有录音
     */
    suspend fun deleteByDay(day: String): Int {
        val recordings = getAllRecordingsInDaySync(day)
        recordings.forEach { recording ->
            File(recording.filePath).delete()
        }
        cleanEmptyFolders(day, null)
        return dao.deleteByDay(day)
    }

    /**
     * 获取今日录音数量
     */
    suspend fun getTodayCount(): Int = dao.getTodayCount(getDayFolder())

    /**
     * 获取今日录音总时长
     */
    suspend fun getTodayDuration(): Long = dao.getTodayDuration(getDayFolder())

    /**
     * 获取录音文件路径（用于保存新录音）
     */
    fun getNewRecordingFilePath(): String {
        val dayFolder = getDayFolder()
        val hourFolder = getHourFolder()
        val timestamp = System.currentTimeMillis()

        val fileName = "${timestampToFileName(timestamp)}.m4a"

        val dayDir = File(recordingsDir, dayFolder)
        val hourDir = File(dayDir, hourFolder)

        if (!hourDir.exists()) {
            hourDir.mkdirs()
        }

        return File(hourDir, fileName).absolutePath
    }

    /**
     * 获取存储使用情况（字节）
     */
    fun getStorageUsed(): Long {
        return recordingsDir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }

    // ==================== 私有方法 ====================

    private fun getFileForRecording(fileName: String): File {
        val dayFolder = getDayFolder()
        val hourFolder = getHourFolder()
        return File(recordingsDir, "$dayFolder/$hourFolder/$fileName")
    }

    private fun getDayFolder(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }

    private fun getHourFolder(): String {
        val calendar = java.util.Calendar.getInstance()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val nextHour = (hour + 1) % 24
        return String.format("%02d:00-%02d:00", hour, nextHour)
    }

    private fun timestampToFileName(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    private suspend fun getAllRecordingsInDaySync(day: String): List<Recording> {
        return dao.getRecordingsByDaySync(day)
    }

    private fun cleanEmptyFolders(dayFolder: String, hourFolder: String?) {
        if (hourFolder != null) {
            val hourDir = File(recordingsDir, "$dayFolder/$hourFolder")
            if (hourDir.exists() && hourDir.listFiles()?.isEmpty() == true) {
                hourDir.delete()
            }
        }
        val dayDir = File(recordingsDir, dayFolder)
        if (dayDir.exists() && dayDir.listFiles()?.isEmpty() == true) {
            dayDir.delete()
        }
    }
}
