package com.example.voicerec.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.example.voicerec.service.WhisperModel

/**
 * 设置数据存储
 */
class SettingsDataStore(private val context: Context) {
    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

        val KEY_VOLUME_THRESHOLD = intPreferencesKey("volume_threshold")
        val KEY_SILENCE_TIMEOUT = intPreferencesKey("silence_timeout")
        val KEY_WHISPER_MODEL = stringPreferencesKey("whisper_model")

        // 默认值
        const val DEFAULT_VOLUME_THRESHOLD = 100
        const val DEFAULT_SILENCE_TIMEOUT = 10 // 秒
        private const val DEFAULT_WHISPER_MODEL = "base" // 默认使用base模型

        // 静音超时选项
        val SILENCE_TIMEOUT_OPTIONS = listOf(5, 10, 30)
    }

    /**
     * 音量阈值设置
     */
    val volumeThreshold: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_VOLUME_THRESHOLD] ?: DEFAULT_VOLUME_THRESHOLD
    }

    /**
     * 静音超时设置（秒）
     */
    val silenceTimeout: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_SILENCE_TIMEOUT] ?: DEFAULT_SILENCE_TIMEOUT
    }

    /**
     * 更新音量阈值
     */
    suspend fun updateVolumeThreshold(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_VOLUME_THRESHOLD] = value
        }
    }

    /**
     * 更新静音超时
     */
    suspend fun updateSilenceTimeout(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SILENCE_TIMEOUT] = value
        }
    }

    /**
     * 获取Whisper模型
     */
    val whisperModel: Flow<WhisperModel> = context.dataStore.data.map { preferences ->
        val modelId = preferences[KEY_WHISPER_MODEL] ?: DEFAULT_WHISPER_MODEL
        WhisperModel.fromId(modelId)
    }

    /**
     * 更新Whisper模型
     */
    suspend fun updateWhisperModel(model: WhisperModel) {
        context.dataStore.edit { preferences ->
            preferences[KEY_WHISPER_MODEL] = model.id
        }
    }
}
