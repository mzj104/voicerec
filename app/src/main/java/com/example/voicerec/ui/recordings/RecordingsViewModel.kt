package com.example.voicerec.ui.recordings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.voicerec.data.Recording
import com.example.voicerec.data.RecordingRepository
import com.example.voicerec.service.WhisperModelManager
import com.example.voicerec.service.WhisperService
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录音列表ViewModel
 */
class RecordingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RecordingRepository(application)

    // 服务状态
    private val _serviceState = MutableLiveData<ServiceState>(ServiceState.Stopped)
    val serviceState: LiveData<ServiceState> = _serviceState

    // 所有录音
    val allRecordings: LiveData<List<Recording>> =
        repository.getAllRecordings().asLiveData()

    // 展开状态
    private val _expandedDays = MutableLiveData<Set<String>>(emptySet())
    val expandedDays: LiveData<Set<String>> = _expandedDays

    private val _expandedHours = MutableLiveData<Set<String>>(emptySet())
    val expandedHours: LiveData<Set<String>> = _expandedHours

    // 今日统计
    private val _todayStats = MutableLiveData<TodayStats>()
    val todayStats: LiveData<TodayStats> = _todayStats

    init {
        updateTodayStats()
    }

    /**
     * 更新服务状态
     */
    fun updateServiceState(isRunning: Boolean) {
        _serviceState.value = if (isRunning) {
            ServiceState.Running
        } else {
            ServiceState.Stopped
        }
    }

    /**
     * 切换日期展开状态
     */
    fun toggleDayExpansion(day: String) {
        val current = _expandedDays.value ?: emptySet()
        val newSet = if (current.contains(day)) {
            current - day
        } else {
            current + day
        }
        _expandedDays.value = newSet
    }

    /**
     * 切换小时展开状态
     */
    fun toggleHourExpansion(hourKey: String) {
        val current = _expandedHours.value ?: emptySet()
        val newSet = if (current.contains(hourKey)) {
            current - hourKey
        } else {
            current + hourKey
        }
        _expandedHours.value = newSet
    }

    /**
     * 更新今日统计
     */
    fun updateTodayStats() {
        runBlocking {
            val count = repository.getTodayCount()
            val duration = repository.getTodayDuration()
            _todayStats.value = TodayStats(count, duration)
        }
    }

    /**
     * 删除录音
     */
    suspend fun deleteRecording(recording: Recording) {
        repository.deleteRecording(recording)
    }

    /**
     * 删除某天的所有录音
     */
    suspend fun deleteDay(day: String) {
        repository.deleteByDay(day)
    }

    /**
     * 格式化时长
     */
    fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "${hours}小时${minutes % 60}分"
            minutes > 0 -> "${minutes}分${seconds % 60}秒"
            else -> "${seconds}秒"
        }
    }

    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
            bytes >= 1024 -> "${bytes / 1024}KB"
            else -> "${bytes}B"
        }
    }

    /**
     * 获取今日日期
     */
    fun getTodayString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date())
    }

    /**
     * 转写录音
     */
    suspend fun transcribeRecording(recording: Recording): Result<String> = withContext(Dispatchers.IO) {
        try {
            val modelManager = WhisperModelManager(getApplication())

            // 确保模型已准备好
            if (!modelManager.isModelReady()) {
                val copyResult = modelManager.copyModelFromAssets { }
                if (copyResult.isFailure) {
                    return@withContext Result.failure(ModelNotDownloadedException())
                }
            }

            val whisperService = WhisperService(getApplication())

            val result = whisperService.transcribeAudio(recording.filePath) { progress ->
                // 进度回调
            }

            whisperService.release()

            result.fold(
                onSuccess = { text ->
                    val updatedRecording = recording.copy(
                        transcriptionText = text,
                        transcriptionTime = System.currentTimeMillis()
                    )
                    repository.updateRecording(updatedRecording)
                    Result.success(text)
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 检查模型是否已下载
     */
    fun isModelDownloaded(): Boolean {
        val modelManager = WhisperModelManager(getApplication())
        return runBlocking {
            val model = modelManager.getSelectedModel()
            modelManager.isModelReady(model)
        }
    }

    /**
     * 获取模型信息
     */
    fun getModelInfo(): ModelInfo {
        val modelManager = WhisperModelManager(getApplication())
        return runBlocking {
            val model = modelManager.getSelectedModel()
            val modelFile = modelManager.getModelFile(model)
            ModelInfo(
                isDownloaded = modelManager.isModelReady(model),
                sizeBytes = if (modelFile.exists()) modelFile.length() else 0
            )
        }
    }
}

/**
 * 模型未下载异常
 */
class ModelNotDownloadedException : Exception("模型未下载，请先下载模型")

/**
 * 模型信息
 */
data class ModelInfo(
    val isDownloaded: Boolean,
    val sizeBytes: Long
)

/**
 * 服务状态
 */
sealed class ServiceState {
    object Stopped : ServiceState()
    object Running : ServiceState()
}

/**
 * 今日统计
 */
data class TodayStats(
    val count: Int,
    val durationMs: Long
)
