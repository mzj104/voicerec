package com.example.voicerec.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 录音实体类
 * @param id 唯一标识
 * @param fileName 文件名 "20250219_143025.m4a"
 * @param filePath 完整文件路径
 * @param dayFolder 日期文件夹 "2025-02-19"
 * @param hourFolder 小时文件夹 "14:00"
 * @param timestamp 录音开始时间戳
 * @param durationMs 录音时长（毫秒）
 * @param fileSizeBytes 文件大小（字节）
 */
@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val filePath: String,
    val dayFolder: String,
    val hourFolder: String,
    val timestamp: Long,
    val durationMs: Long,
    val fileSizeBytes: Long
)
