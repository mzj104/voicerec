package com.example.voicerec.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Whisper模型管理器
 * 管理用户选择的Whisper模型文件
 */
class WhisperModelManager(private val context: Context) {

    /**
     * 获取用户当前选择的模型
     */
    suspend fun getSelectedModel(): WhisperModel {
        val settingsDataStore = com.example.voicerec.ui.settings.SettingsDataStore(context)
        return settingsDataStore.whisperModel.firstOrNull() ?: WhisperModel.BASE
    }

    /**
     * 获取模型文件路径
     */
    fun getModelFile(model: WhisperModel = WhisperModel.BASE): File {
        return File(context.filesDir, model.fileName)
    }

    /**
     * 获取当前选择模型的文件路径
     */
    suspend fun getCurrentModelFile(): File {
        val model = getSelectedModel()
        return getModelFile(model)
    }

    /**
     * 检查模型是否已准备好
     */
    suspend fun isModelReady(): Boolean {
        val model = getSelectedModel()
        return isModelReady(model)
    }

    /**
     * 检查指定模型是否已准备好
     */
    fun isModelReady(model: WhisperModel): Boolean {
        val modelFile = getModelFile(model)
        if (!modelFile.exists()) {
            return false
        }
        // 检查文件大小是否合理
        return modelFile.length() > model.minSizeBytes
    }

    /**
     * 从assets复制模型到内部存储
     */
    suspend fun copyModelFromAssets(
        onProgress: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        copyModelFromAssets(getSelectedModel(), onProgress)
    }

    /**
     * 从assets复制指定模型到内部存储
     */
    suspend fun copyModelFromAssets(
        model: WhisperModel,
        onProgress: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val modelFile = getModelFile(model)

            // 如果文件已存在且大小合理，直接返回
            if (modelFile.exists() && modelFile.length() > model.minSizeBytes) {
                onProgress("${model.displayName} 模型文件已存在")
                return@withContext Result.success(modelFile)
            }

            onProgress("正在复制 ${model.displayName} 模型...")

            // 删除旧文件
            if (modelFile.exists()) {
                modelFile.delete()
            }

            // 确保父目录存在
            modelFile.parentFile?.mkdirs()

            // 从assets复制
            val assetManager = context.assets
            try {
                assetManager.open(model.fileName).use { input ->
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

                if (modelFile.length() < model.minSizeBytes) {
                    modelFile.delete()
                    return@withContext Result.failure(Exception("模型文件大小异常: ${modelFile.length()} bytes"))
                }

                onProgress("${model.displayName} 模型复制完成")
                Result.success(modelFile)

            } catch (e: Exception) {
                // Assets中不存在该模型文件
                return@withContext Result.failure(Exception("Assets中找不到 ${model.fileName}，请确保模型文件已放入assets目录"))
            }

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取模型大小
     */
    fun getModelSize(): Long {
        // 获取当前选择的模型文件大小
        return runBlocking {
            val model = getSelectedModel()
            val modelFile = getModelFile(model)
            if (modelFile.exists()) modelFile.length() else 0
        }
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
        runBlocking {
            val model = getSelectedModel()
            getModelFile(model).delete()
        }
    }
}
