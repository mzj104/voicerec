package com.example.voicerec.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

/**
 * Qwen2.5 LLM 标题生成服务
 * 使用 llama.cpp 进行本地推理
 */
class LlamaService(private val context: Context) {

    companion object {
        private const val TAG = "LlamaService"

        init {
            try {
                System.loadLibrary("voicerec_llama")
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }
    }

    private var isModelLoaded = false

    // Native methods
    private external fun initModel(modelPath: String, nThreads: Int): Int
    private external fun generateTitle(prompt: String): String
    private external fun cleanup()

    /**
     * 加载模型
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelManager = LlamaModelManager(context)

            if (!modelManager.isModelReady()) {
                Log.w(TAG, "Model not ready, copying from assets...")
                val copyResult = modelManager.copyModelFromAssets { message ->
                    Log.i(TAG, "Copy progress: $message")
                }
                if (copyResult.isFailure) {
                    Log.e(TAG, "Failed to copy model: ${copyResult.exceptionOrNull()?.message}")
                    return@withContext false
                }
            }

            val modelFile = modelManager.getModelFile()
            Log.i(TAG, "Loading model from: ${modelFile.absolutePath}, size: ${modelFile.length()} bytes")

            val result = initModel(modelFile.absolutePath, 4)
            isModelLoaded = (result == 0)

            if (isModelLoaded) {
                Log.i(TAG, "Model loaded successfully")
            } else {
                Log.e(TAG, "Failed to load model, result code: $result")
            }

            return@withContext isModelLoaded

        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            return@withContext false
        }
    }

    /**
     * 生成 AI 标题
     */
    suspend fun generateAiTitle(transcription: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Validate input
            if (transcription.isBlank()) {
                return@withContext Result.failure(Exception("转写文本为空"))
            }

            // Limit input length
            val truncated = transcription.take(500)

            // Load model if needed
            if (!isModelLoaded) {
                Log.w(TAG, "Model not loaded, loading now...")
                val loaded = loadModel()
                if (!loaded) {
                    return@withContext Result.failure(Exception("LLM模型加载失败"))
                }
            }

            // Build prompt for Qwen2.5 Instruct
            val prompt = buildPrompt(truncated)

            Log.i(TAG, "Generating title, prompt length: ${prompt.length}")

            val rawOutput = generateTitle(prompt)
            val cleanedTitle = cleanOutput(rawOutput)

            if (cleanedTitle.isNotBlank()) {
                Log.i(TAG, "Title generated: '$cleanedTitle'")
                Result.success(cleanedTitle)
            } else {
                Result.failure(Exception("生成的标题为空"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate AI title", e)
            return@withContext Result.failure(e)
        }
    }

    /**
     * 构建 Qwen2.5 Instruct 格式的 prompt
     */
    private fun buildPrompt(transcription: String): String {
        return """<|im_start|>system
你是一个录音标题生成助手。请根据录音转写文本，生成一个5-10个中文字符的简短标题，概括主旨。只输出标题，不要加引号或其他符号。
<|im_end|>
<|im_start|>user
$transcription
<|im_end|>
<|im_start|>assistant
"""
    }

    /**
     * 清理模型输出
     */
    private fun cleanOutput(output: String): String {
        return output
            .replace("<|im_start|>assistant\n", "")
            .replace("<|im_end|>", "")
            .replace("\"", "")
            .trim()
            .take(20)  // Safety limit
    }

    /**
     * 释放模型资源
     */
    fun release() {
        if (isModelLoaded) {
            try {
                cleanup()
                isModelLoaded = false
                Log.i(TAG, "Model resources released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing model", e)
            }
        }
    }
}
