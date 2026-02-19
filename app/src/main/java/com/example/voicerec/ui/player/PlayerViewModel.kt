package com.example.voicerec.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import com.example.voicerec.data.Recording
import com.example.voicerec.data.RecordingRepository
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking

/**
 * 播放器ViewModel
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RecordingRepository(application)

    // 当前录音
    private val _currentRecording = MutableLiveData<Recording?>()
    val currentRecording: LiveData<Recording?> = _currentRecording

    // 播放状态
    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    // 播放进度
    private val _currentPosition = MutableLiveData(0)
    val currentPosition: LiveData<Int> = _currentPosition

    private val _duration = MutableLiveData(0)
    val duration: LiveData<Int> = _duration

    /**
     * 加载录音
     */
    fun loadRecording(recordingId: Long) {
        runBlocking {
            val recording = repository.getRecordingById(recordingId)
            _currentRecording.value = recording
            recording?.let {
                _duration.value = (it.durationMs / 1000).toInt()
            }
        }
    }

    /**
     * 更新播放状态
     */
    fun updatePlayingState(playing: Boolean) {
        _isPlaying.value = playing
    }

    /**
     * 更新播放进度
     */
    fun updateProgress(position: Int) {
        _currentPosition.value = position
    }

    /**
     * 格式化时长
     */
    fun formatTime(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
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
     * 删除当前录音
     */
    suspend fun deleteCurrentRecording() {
        _currentRecording.value?.let {
            repository.deleteRecording(it)
        }
    }

    /**
     * 获取当前录音
     */
    fun getCurrentRecording(): Recording? = _currentRecording.value
}
