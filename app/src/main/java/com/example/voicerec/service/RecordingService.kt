package com.example.voicerec.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.voicerec.MainActivity
import com.example.voicerec.R
import com.example.voicerec.data.Recording
import com.example.voicerec.data.RecordingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import android.util.Log

/**
 * 录音服务
 * 负责后台声音检测和录音
 */
class RecordingService : Service() {
    companion object {
        private const val TAG = "RecordingService"
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.example.voicerec.START_RECORDING"
        const val ACTION_STOP = "com.example.voicerec.STOP_RECORDING"
        const val ACTION_UPDATE = "com.example.voicerec.UPDATE_STATUS"
        const val ACTION_TRANSCRIPTION_COMPLETE = "com.example.voicerec.TRANSCRIPTION_COMPLETE"

        // 状态常量
        const val STATE_IDLE = 0
        const val STATE_MONITORING = 1
        const val STATE_RECORDING = 2

        // 录音参数
        private const val SAMPLE_RATE = 16000
        private const val OUTPUT_FORMAT = MediaRecorder.OutputFormat.MPEG_4
        private const val AUDIO_ENCODER = MediaRecorder.AudioEncoder.AAC
        private const val AUDIO_ENCODING_BIT_RATE = 64000
        private const val AUDIO_CHANNEL = 1 // 单声道

        // 检测参数
        private const val VOLUME_THRESHOLD = 100 // 音量阈值 (0-32767)
        private const val SILENCE_THRESHOLD_MS = 600000L // 连续静音10分钟后停止
        private const val MIN_RECORDING_MS = 2000L // 最短录音2秒
        private const val CHECK_INTERVAL_MS = 200L // 检测间隔200ms
    }

    // Binder
    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    // 状态
    private var currentState = STATE_IDLE
    private var recordingStartTime = 0L
    private var lastSoundTime = 0L
    private var volumeCheckJob: Job? = null
    private var titleGenerationJob: Job? = null

    // 组件
    private var mediaRecorder: MediaRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var repository: RecordingRepository
    private var currentOutputFile: File? = null

    // 回调
    var statusCallback: ((state: Int, message: String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        repository = RecordingRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()
        }
        return START_STICKY
    }

    /**
     * 开始监听声音
     */
    fun startMonitoring() {
        if (currentState != STATE_IDLE) return

        currentState = STATE_MONITORING
        startForeground(NOTIFICATION_ID, createNotification("监听中...", 0, 0))
        notifyStatus(STATE_MONITORING, "开始监听")

        acquireWakeLock()

        // 启动音量检测协程
        volumeCheckJob = CoroutineScope(Dispatchers.IO).launch {
            checkAudioAndRecord()
        }
    }

    /**
     * 停止监听
     */
    fun stopMonitoring() {
        if (currentState == STATE_IDLE) return

        volumeCheckJob?.cancel()
        titleGenerationJob?.cancel()
        titleGenerationJob = null
        stopCurrentRecorder()
        currentState = STATE_IDLE
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        notifyStatus(STATE_IDLE, "已停止")
    }

    /**
     * 检查音频并录音
     */
    private suspend fun checkAudioAndRecord() {
        while (currentState != STATE_IDLE) {
            try {
                when (currentState) {
                    STATE_MONITORING -> {
                        // 监听状态下，持续录音到临时文件用于检测音量
                        startRecordingToTemp()

                        // 监听音量变化
                        val startTime = System.currentTimeMillis()
                        var soundDetected = false

                        while (currentState == STATE_MONITORING) {
                            val volume = mediaRecorder?.maxAmplitude ?: 0

                            if (volume > VOLUME_THRESHOLD) {
                                soundDetected = true
                            }

                            // 如果检测到声音且持续超过500ms，开始正式录音
                            if (soundDetected && (System.currentTimeMillis() - startTime) > 500) {
                                stopCurrentRecorder()
                                currentState = STATE_RECORDING
                                startActualRecording()
                                break
                            }

                            // 如果超过5秒没检测到声音，重新开始
                            if (!soundDetected && (System.currentTimeMillis() - startTime) > 5000) {
                                stopCurrentRecorder()
                                startRecordingToTemp()
                            }

                            delay(CHECK_INTERVAL_MS)
                        }
                    }

                    STATE_RECORDING -> {
                        // 录音状态下，持续检测音量
                        lastSoundTime = System.currentTimeMillis()
                        var hasSound = false

                        // 检查最近几次音量读数
                        repeat(10) {
                            if (currentState != STATE_RECORDING) return@repeat
                            val volume = mediaRecorder?.maxAmplitude ?: 0
                            if (volume > VOLUME_THRESHOLD) {
                                hasSound = true
                                lastSoundTime = System.currentTimeMillis()
                            }
                            delay(CHECK_INTERVAL_MS)
                        }

                        val now = System.currentTimeMillis()
                        val recordingDuration = now - recordingStartTime
                        val silenceDuration = now - lastSoundTime

                        // 更新通知
                        if (recordingDuration >= MIN_RECORDING_MS) {
                            val duration = (recordingDuration / 1000).toInt()
                            updateNotification("录音中... ${duration}秒", 0, 0)
                        }

                        // 检查是否需要停止录音
                        if (recordingDuration >= MIN_RECORDING_MS && silenceDuration >= SILENCE_THRESHOLD_MS) {
                            stopCurrentRecorder()
                            currentState = STATE_MONITORING
                            updateNotification("监听中...", 0, 0)
                            notifyStatus(STATE_MONITORING, "返回监听")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 开始录音到临时文件（用于音量检测）
     */
    private fun startRecordingToTemp() {
        try {
            val tempFile = File(cacheDir, "temp_${System.currentTimeMillis()}.m4a")

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(OUTPUT_FORMAT)
                setOutputFile(tempFile.absolutePath)
                setAudioEncoder(AUDIO_ENCODER)
                setAudioEncodingBitRate(AUDIO_ENCODING_BIT_RATE)
                setAudioChannels(AUDIO_CHANNEL)
                setAudioSamplingRate(SAMPLE_RATE)
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 开始实际录音
     */
    private fun startActualRecording() {
        try {
            // 获取新的文件路径
            val filePath = repository.getNewRecordingFilePath()
            currentOutputFile = File(filePath)

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(OUTPUT_FORMAT)
                setOutputFile(filePath)
                setAudioEncoder(AUDIO_ENCODER)
                setAudioEncodingBitRate(AUDIO_ENCODING_BIT_RATE)
                setAudioChannels(AUDIO_CHANNEL)
                setAudioSamplingRate(SAMPLE_RATE)
                prepare()
                start()
            }

            recordingStartTime = System.currentTimeMillis()
            lastSoundTime = recordingStartTime

            updateNotification("录音中...", 0, 0)
            notifyStatus(STATE_RECORDING, "开始录音")

        } catch (e: Exception) {
            e.printStackTrace()
            currentState = STATE_MONITORING
        }
    }

    /**
     * 停止当前录音器并保存
     */
    private fun stopCurrentRecorder() {
        try {
            mediaRecorder?.let { recorder ->
                // 获取文件路径（如果是实际录音）
                val isActualRecording = currentOutputFile != null
                val recordingFile = currentOutputFile

                recorder.stop()
                recorder.release()

                // 保存录音信息
                if (isActualRecording && recordingFile != null) {
                    val duration = System.currentTimeMillis() - recordingStartTime
                    val fileSize = recordingFile.length()

                    if (duration >= MIN_RECORDING_MS && fileSize > 0) {
                        // 使用协程保存，避免阻塞
                        CoroutineScope(Dispatchers.IO).launch {
                            val savedRecording = repository.saveRecording(
                                fileName = recordingFile.name,
                                durationMs = duration,
                                fileSizeBytes = fileSize
                            )

                            // 保存成功后，自动开始转写
                            savedRecording?.let { recording ->
                                Log.i(TAG, "Recording saved, starting auto-transcription: ${recording.id}")
                                startAutoTranscription(recording)
                            }
                        }
                    } else {
                        // 录音太短或文件为空，删除
                        recordingFile.delete()
                    }
                } else {
                    // 临时文件，直接删除
                    cacheDir.listFiles()?.filter {
                        it.name.startsWith("temp_") && it.name.endsWith(".m4a")
                    }?.forEach { it.delete() }
                }
            }

            mediaRecorder = null
            currentOutputFile = null

        } catch (e: Exception) {
            e.printStackTrace()
            mediaRecorder = null
            currentOutputFile = null
        }
    }

    /**
     * 自动转写录音（后台运行）
     */
    private fun startAutoTranscription(recording: Recording) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 检查模型是否准备好
                val modelManager = WhisperModelManager(applicationContext)
                if (!modelManager.isModelReady()) {
                    Log.w(TAG, "Model not ready, skipping auto-transcription")
                    return@launch
                }

                Log.i(TAG, "Starting auto-transcription for recording: ${recording.id}")

                // 创建WhisperService并转写
                val whisperService = WhisperService(applicationContext)
                val result = whisperService.transcribeAudio(recording.filePath) { message ->
                    Log.d(TAG, "Transcription progress: $message")
                }
                whisperService.release()

                result.fold(
                    onSuccess = { text ->
                        Log.i(TAG, "Auto-transcription success for recording: ${recording.id}")
                        // 更新数据库
                        val updatedRecording = recording.copy(
                            transcriptionText = text,
                            transcriptionTime = System.currentTimeMillis()
                        )
                        repository.updateRecording(updatedRecording)

                        // Generate AI title after transcription
                        generateAiTitle(recording.id, text)

                        // 发送转写完成广播
                        val intent = Intent(ACTION_TRANSCRIPTION_COMPLETE).apply {
                            putExtra("recordingId", recording.id)
                            putExtra("text", text)
                        }
                        sendBroadcast(intent)
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Auto-transcription failed for recording: ${recording.id}, error: ${error.message}")
                    }
                )

            } catch (e: Exception) {
                Log.e(TAG, "Auto-transcription error: ${e.message}")
            }
        }
    }

    /**
     * 生成 AI 标题
     */
    private fun generateAiTitle(recordingId: Long, transcriptionText: String) {
        titleGenerationJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val llamaService = LlamaService(applicationContext)
                try {
                    val aiTitle = llamaService.generateAiTitle(transcriptionText).getOrNull()
                    aiTitle?.let { title ->
                        Log.i(TAG, "AI title generated: $title for recording: $recordingId")
                        // Update database with AI title
                        repository.updateAiTitle(recordingId, title, System.currentTimeMillis())
                    }
                } finally {
                    llamaService.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AI title generation failed, not blocking", e)
                // Don't throw - title generation failure shouldn't block transcription
            }
        }
    }

    /**
     * 获取WakeLock
     */
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "voicerec:recording_wakelock"
            ).apply {
                acquire(10 * 60 * 1000L)
            }
        }
    }

    /**
     * 释放WakeLock
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "录音服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "持续监听声音并录音"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建通知
     */
    private fun createNotification(
        statusText: String,
        count: Int,
        duration: Int
    ): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("声音录音")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "停止", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * 更新通知
     */
    private fun updateNotification(statusText: String, count: Int, duration: Int) {
        val notification = createNotification(statusText, count, duration)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 通知状态变化
     */
    private fun notifyStatus(state: Int, message: String) {
        statusCallback?.invoke(state, message)

        val intent = Intent(ACTION_UPDATE).apply {
            putExtra("state", state)
            putExtra("message", message)
        }
        sendBroadcast(intent)
    }

    /**
     * 获取当前状态
     */
    fun getCurrentState(): Int = currentState

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
    }
}
