package com.example.voicerec.service

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Whisper语音转文字服务
 * 使用whisper.cpp原生实现
 */
class WhisperService(private val context: Context) {

    companion object {
        private const val TAG = "WhisperService"

        init {
            try {
                System.loadLibrary("voicerec")
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }
    }

    private var isModelLoaded = false

    // Native methods
    private external fun getVersion(): String
    private external fun loadModel(modelPath: String): Boolean
    private external fun transcribe(audioData: FloatArray, sampleRate: Int): String
    private external fun releaseModel()

    /**
     * 加载Whisper模型
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelManager = WhisperModelManager(context)
            val selectedModel = modelManager.getSelectedModel()

            Log.i(TAG, "Loading model: ${selectedModel.displayName}")

            if (!modelManager.isModelReady(selectedModel)) {
                Log.w(TAG, "Model not ready, copying from assets...")
                val copyResult = modelManager.copyModelFromAssets(selectedModel) { }
                if (copyResult.isFailure) {
                    Log.e(TAG, "Failed to copy model: ${copyResult.exceptionOrNull()?.message}")
                    return@withContext false
                }
            }

            val modelFile = modelManager.getModelFile(selectedModel)
            Log.i(TAG, "Loading model from: ${modelFile.absolutePath}")

            isModelLoaded = loadModel(modelFile.absolutePath)

            if (isModelLoaded) {
                Log.i(TAG, "Model loaded successfully: ${selectedModel.displayName}")
            } else {
                Log.e(TAG, "Failed to load model")
            }

            return@withContext isModelLoaded
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            return@withContext false
        }
    }

    /**
     * 转录音频文件
     */
    suspend fun transcribeAudio(
        audioPath: String,
        onProgress: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isModelLoaded) {
                Log.w(TAG, "Model not loaded, loading now...")
                val loaded = loadModel()
                if (!loaded) {
                    return@withContext Result.failure(Exception("模型加载失败"))
                }
            }

            onProgress("正在解码音频...")
            val audioSamples = decodeAudioToFloat(audioPath)
            Log.i(TAG, "Audio decoded, samples: ${audioSamples.size}")

            onProgress("正在进行语音识别...")
            val result = transcribe(audioSamples, 16000)
            Log.i(TAG, "Transcription result: $result")

            if (result.isNotBlank()) {
                Result.success(result.trim())
            } else {
                Result.failure(Exception("识别结果为空"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            Result.failure(e)
        }
    }

    /**
     * 释放模型资源
     */
    fun release() {
        if (isModelLoaded) {
            releaseModel()
            isModelLoaded = false
            Log.i(TAG, "Model released")
        }
    }

    /**
     * 获取版本信息
     */
    fun getVersionInfo(): String {
        return try {
            getVersion()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting version", e)
            "Unknown"
        }
    }

    /**
     * 解码音频文件到FloatArray
     */
    private fun decodeAudioToFloat(audioPath: String): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(audioPath)
            val trackIndex = findAudioTrack(extractor)
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 16000)

            Log.d(TAG, "Audio format: $mime, sampleRate: $sampleRate Hz")

            val pcm = decodeWithMediaCodec(extractor, mime, sampleRate)

            return if (sampleRate != 16000) {
                resample(pcm, sampleRate.toFloat(), 16000f)
            } else {
                pcm
            }

        } finally {
            extractor.release()
        }
    }

    private fun decodeWithMediaCodec(
        extractor: MediaExtractor,
        mime: String,
        sampleRate: Int
    ): FloatArray {
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(extractor.getTrackFormat(findAudioTrack(extractor)), null, null, 0)
        codec.start()

        val output = mutableListOf<Float>()
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            var inputEOS = false
            var outputEOS = false

            while (!outputEOS) {
                if (!inputEOS) {
                    val idx = codec.dequeueInputBuffer(10000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)
                        val size = extractor.readSampleData(buf!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEOS = true
                        } else {
                            codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val idx = codec.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        val floats = bufferToFloatArray(buf!!, bufferInfo.size)
                        output.addAll(floats.toList())
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEOS = true
                        }
                        codec.releaseOutputBuffer(idx, false)
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }

        return output.toFloatArray()
    }

    private fun bufferToFloatArray(buffer: ByteBuffer, size: Int): FloatArray {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(size / 2)
        for (i in floats.indices) {
            val short = buffer.short.toShort().toInt()
            floats[i] = short / 32768.0f
        }
        return floats
    }

    private fun resample(input: FloatArray, fromRate: Float, toRate: Float): FloatArray {
        if (fromRate == toRate) return input
        val ratio = fromRate / toRate
        val outLen = (input.size / ratio).toInt()
        val output = FloatArray(outLen)
        for (i in output.indices) {
            output[i] = input[(i * ratio).toInt()]
        }
        return output
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                return i
            }
        }
        return -1
    }
}
