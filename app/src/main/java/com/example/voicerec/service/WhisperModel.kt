package com.example.voicerec.service

/**
 * Whisper模型类型
 */
enum class WhisperModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val minSizeBytes: Long,
    val description: String,
    val recommendedRamMB: Int
) {
    BASE(
        id = "base",
        displayName = "Base (快速)",
        fileName = "ggml-base-q8_0.bin",
        minSizeBytes = 50 * 1024 * 1024,
        description = "识别速度快，适合日常使用 (~78MB)",
        recommendedRamMB = 3_000
    ),
    LARGE_V3_TURBO(
        id = "large_v3_turbo",
        displayName = "Large V3 Turbo (精准)",
        fileName = "ggml-large-v3-turbo-q8_0.bin",
        minSizeBytes = 500 * 1024 * 1024,
        description = "识别准确率最高，需要更多内存 (~834MB)",
        recommendedRamMB = 6_000
    );

    companion object {
        fun fromId(id: String): WhisperModel {
            return values().find { it.id == id } ?: BASE
        }

        fun getAvailableModels(): List<WhisperModel> {
            return values().toList()
        }
    }
}
