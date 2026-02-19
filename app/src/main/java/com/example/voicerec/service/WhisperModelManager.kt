package com.example.voicerec.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Whisper模型管理器
 * 管理ggml-large-v3-turbo-q8_0.bin模型文件
 */
class WhisperModelManager(private val context: Context) {

    companion object {
        private const val MODEL_FILE_NAME = "ggml-large-v3-turbo-q8_0.bin"
        private const val MIN_MODEL_SIZE = 500 * 1024 * 1024 // 500MB
    }

    /**
     * 获取模型文件路径
     */
    fun getModelFile(): File {
        return File(context.filesDir, MODEL_FILE_NAME)
    }

    /**
     * 检查模型是否已准备好
     */
    fun isModelReady(): Boolean {
        val modelFile = getModelFile()
        if (!modelFile.exists()) {
            return false
        }
        // 检查文件大小是否合理
        return modelFile.length() > MIN_MODEL_SIZE
    }

    /**
     * 从assets复制模型到内部存储
     */
    suspend fun copyModelFromAssets(
        onProgress: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val modelFile = getModelFile()

            // 如果文件已存在且大小合理，直接返回
            if (modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE) {
                onProgress("模型文件已存在")
                return@withContext Result.success(modelFile)
            }

            onProgress("正在复制模型文件...")

            // 删除旧文件
            if (modelFile.exists()) {
                modelFile.delete()
            }

            // 确保父目录存在
            modelFile.parentFile?.mkdirs()

            // 从assets复制
            val assetManager = context.assets
            assetManager.open(MODEL_FILE_NAME).use { input ->
                FileOutputStream(modelFile).use { output ->
                    val buffer = ByteArray(8192)
                    var totalCopied = 0L
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalCopied += bytesRead

                        // 每10MB更新一次进度
                        if (totalCopied % (10 * 1024 * 1024) < 8192) {
                            val mb = totalCopied / (1024 * 1024)
                            onProgress("正在复制: ${mb}MB")
                        }
                    }
                    output.flush()
                }
            }

            // 验证文件
            if (!modelFile.exists()) {
                return@withContext Result.failure(Exception("模型文件复制失败"))
            }

            if (modelFile.length() < MIN_MODEL_SIZE) {
                modelFile.delete()
                return@withContext Result.failure(Exception("模型文件大小异常: ${modelFile.length()} bytes"))
            }

            onProgress("模型复制完成")
            Result.success(modelFile)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取模型大小
     */
    fun getModelSize(): Long {
        val modelFile = getModelFile()
        return if (modelFile.exists()) modelFile.length() else 0
    }

    /**
     * 获取格式化的模型大小
     */
    fun getFormattedSize(): String {
        val bytes = getModelSize()
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.1fGB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / (1024.0 * 1024))
            bytes >= 1024 -> "%.1fKB".format(bytes / 1024.0)
            else -> "${bytes}B"
        }
    }

    /**
     * 删除模型
     */
    fun deleteModel() {
        getModelFile().delete()
    }
}
