package com.example.voicerec.service

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Qwen2.5 LLM 模型管理器
 */
class LlamaModelManager(private val context: Context) {

    companion object {
        private const val TAG = "LlamaModelManager"
        const val MODEL_FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        private const val MIN_MODEL_SIZE = 100_000_000L  // 100MB minimum
    }

    /**
     * 获取模型文件路径
     */
    fun getModelFile(): File {
        val modelsDir = File(context.filesDir, "llm_models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        return File(modelsDir, MODEL_FILE)
    }

    /**
     * 检查模型是否已准备好
     */
    fun isModelReady(): Boolean {
        val file = getModelFile()
        val exists = file.exists()
        val sizeOk = file.length() > MIN_MODEL_SIZE
        Log.d(TAG, "Model ready check: exists=$exists, sizeOk=$sizeOk")
        return exists && sizeOk
    }

    /**
     * 从 assets 复制模型文件
     */
    fun copyModelFromAssets(onProgress: ((String) -> Unit)? = null): Result<Unit> {
        return try {
            onProgress?.invoke("正在准备模型...")

            val assetFile = context.assets.open(MODEL_FILE)
            val targetFile = getModelFile()

            // Check if already exists and valid
            if (targetFile.exists() && targetFile.length() > MIN_MODEL_SIZE) {
                Log.i(TAG, "Model already exists, skipping copy")
                return Result.success(Unit)
            }

            // Delete partial file if exists
            if (targetFile.exists()) {
                Log.i(TAG, "Deleting partial/incomplete model file")
                targetFile.delete()
            }

            val totalSize = assetFile.available().toLong()
            var copiedBytes = 0L
            val buffer = ByteArray(1024 * 1024)  // 1MB buffer

            targetFile.outputStream().use { output ->
                var bytesRead: Int
                while (assetFile.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    copiedBytes += bytesRead

                    val progress = (copiedBytes * 100 / totalSize).toInt()
                    onProgress?.invoke("正在复制模型... $progress%")
                }
            }

            assetFile.close()
            Log.i(TAG, "Model copied successfully: ${targetFile.length()} bytes")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model", e)
            // Clean up partial file on failure
            try {
                val targetFile = getModelFile()
                if (targetFile.exists()) {
                    Log.i(TAG, "Cleaning up partial model file after failure")
                    targetFile.delete()
                }
            } catch (cleanupException: Exception) {
                Log.e(TAG, "Failed to clean up partial file", cleanupException)
            }
            Result.failure(e)
        }
    }

    /**
     * 获取模型大小（字节）
     */
    fun getModelSizeBytes(): Long {
        return if (getModelFile().exists()) {
            getModelFile().length()
        } else {
            0L
        }
    }

    /**
     * 获取模型大小（格式化字符串）
     */
    fun getModelSizeFormatted(): String {
        val bytes = getModelSizeBytes()
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.1fGB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / (1024.0 * 1024))
            else -> "%.1fKB".format(bytes / 1024.0)
        }
    }
}
