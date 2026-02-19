# Whisper 离线语音识别设计文档

**日期**: 2025-02-19
**作者**: Claude
**状态**: 已批准

## 概述

将现有的讯飞在线语音识别替换为基于 Whisper 的离线语音识别，实现完全本地化的转录功能。

## 需求

1. 使用 OpenAI Whisper 模型进行离线语音转文字
2. 使用 small 模型 (~250MB)，平衡准确率和性能
3. 模型文件存储在应用内部存储
4. 首次使用时提示用户下载模型
5. 转录结果保存到数据库
6. 完全移除讯飞相关代码

## 架构

```
┌─────────────────────────────────────────────┐
│               UI Layer                       │
│  RecordingsFragment → RecordingsViewModel    │
└──────────────────┬──────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────┐
│         WhisperTranscribeService             │
│  - 模型管理 (下载/检查)                       │
│  - 离线转录                                  │
│  - 进度回调                                  │
└─────────────────────────────────────────────┘
```

## 核心组件

### WhisperTranscribeService

```kotlin
class WhisperTranscribeService(
    private val context: Context,
    private val modelManager: WhisperModelManager
) {
    suspend fun transcribe(
        audioPath: String,
        onProgress: (String) -> Unit
    ): Result<String>
}
```

**职责**:
- 检查模型是否可用
- 加载 TFLite 模型
- 处理音频文件
- 运行推理
- 返回转录结果

### WhisperModelManager

```kotlin
class WhisperModelManager(private val context: Context) {
    fun isModelDownloaded(): Boolean
    suspend fun downloadModel(onProgress: (Int) -> Unit): Result<File>
    fun getModelFile(): File
    fun deleteModel()
}
```

**职责**:
- 检查模型是否存在
- 从 GitHub 下载模型
- 管理模型文件
- 提供下载进度

## 数据库变更

### Recording 表新增字段

```kotlin
var transcriptionText: String? = null   // 转录结果
var transcriptionTime: Long? = null     // 转录时间戳
```

## 依赖变更

### 删除
- `IFlytekTranscribeService.kt`
- Gson (仅用于讯飞)
- Retrofit (仅用于讯飞)

### 新增
```kotlin
// Whisper TFLite
implementation("com.github.mr0xfac:whisper-tflite:1.0.0")

// 模型下载 (保留 OkHttp)
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

## 数据流

```
用户点击"转文字"
       │
       ▼
检查模型是否存在
       │
       ├─ 不存在 → 显示下载对话框 → 下载模型 → 保存到本地
       │
       └─ 存在 → 继续
       │
       ▼
显示"正在处理..."对话框
       │
       ▼
WhisperTranscribeService.transcribe()
       │
       ▼
加载 TFLite 模型
       │
       ▼
处理音频文件 (M4A → PCM → Whisper输入)
       │
       ▼
运行推理获取文本
       │
       ▼
保存到数据库 (transcriptionText, transcriptionTime)
       │
       ▼
更新UI显示结果
```

## 错误处理

| 场景 | 处理方式 |
|------|----------|
| 模型下载失败 | 显示错误，提供重试按钮 |
| 模型文件损坏 | 重新下载 |
| 音频格式不支持 | 提示用户，或自动转换 |
| 推理失败 | 显示错误信息，记录日志 |
| 存储空间不足 | 提示用户清理空间 |

## 文件结构

```
app/src/main/java/com/example/voicerec/
├── service/
│   ├── WhisperTranscribeService.kt     (新建)
│   ├── WhisperModelManager.kt          (新建)
│   └── IFlytekTranscribeService.kt     (删除)
├── ui/
│   └── recordings/
│       ├── RecordingsViewModel.kt      (修改)
│       └── RecordingsAdapter.kt        (修改)
└── data/
    └── Recording.kt                    (修改 - 新增字段)
```

## UI 变更

1. **转录按钮**
   - 未转录：显示"转文字"
   - 已转录：显示"查看"

2. **转录结果对话框**
   - 显示转录文本
   - 显示转录时间
   - 复制按钮

3. **下载进度对话框**
   - 显示下载进度百分比
   - 取消按钮

## 模型信息

- **模型**: Whisper Small (Chinese)
- **大小**: ~250MB
- **来源**: https://huggingface.co/ggerganov/whisper.cpp
- **存储位置**: `{context.filesDir}/models/whisper/small.tflite`
- **格式**: TFLite

## 后续考虑

1. 在设置中添加模型管理功能
2. 支持多种模型选择 (tiny/base/small)
3. 支持批量转录
4. 添加搜索功能
