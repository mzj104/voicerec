package com.example.voicerec.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import com.example.voicerec.data.RecordingRepository
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking

/**
 * 设置ViewModel
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsDataStore = SettingsDataStore(application)
    private val repository = RecordingRepository(application)

    // 设置项
    val volumeThreshold = settingsDataStore.volumeThreshold.asLiveData()
    val silenceTimeout = settingsDataStore.silenceTimeout.asLiveData()

    /**
     * 获取存储使用情况
     */
    fun getStorageUsed(): String {
        val bytes = repository.getStorageUsed()
        return formatFileSize(bytes)
    }

    /**
     * 获取存储可用情况（简化版本）
     */
    fun getStorageAvailable(): String {
        // 简化处理，实际应该获取可用空间
        return "可用"
    }

    /**
     * 更新音量阈值
     */
    suspend fun updateVolumeThreshold(value: Int) {
        settingsDataStore.updateVolumeThreshold(value)
    }

    /**
     * 更新静音超时
     */
    suspend fun updateSilenceTimeout(value: Int) {
        settingsDataStore.updateSilenceTimeout(value)
    }

    /**
     * 获取当前静音超时选项索引
     */
    fun getSilenceTimeoutIndex(): Int {
        val timeout = runBlocking {
            settingsDataStore.silenceTimeout.firstOrNull()
                ?: SettingsDataStore.DEFAULT_SILENCE_TIMEOUT
        }
        return SettingsDataStore.SILENCE_TIMEOUT_OPTIONS.indexOf(timeout).coerceAtLeast(0)
    }

    /**
     * 格式化文件大小
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format("%.1fMB", bytes / (1024.0 * 1024))
            bytes >= 1024 -> String.format("%.1fKB", bytes / 1024.0)
            else -> "${bytes}B"
        }
    }
}
