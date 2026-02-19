# Whisper语音识别集成设计文档

## 概述

完全替换现有Vosk实现，使用whisper.cpp + Q8量化模型实现离线中文语音识别。

## 需求

- **功能范围**: 完全替换Vosk，仅使用Whisper
- **模型选择**: Q8量化模型 (ggml-base-q8_0.bin, ~78MB)
- **实现方式**: 录音后批量识别
- **语言支持**: 仅中文

## 架构设计

```
UI Layer (RecordingsFragment)
         │
         ▼
ViewModel Layer (RecordingsViewModel)
         │
         ▼
Service Layer (WhisperService)
         │
         ▼
JNI Layer (native-lib.cpp)
         │
         ▼
Native Layer (whisper.cpp + audio_utils.cpp)
```

## 组件设计

### 1. 原生代码 (C++)

新增 `app/src/main/cpp/` 目录：

| 文件 | 功能 |
|------|------|
| `CMakeLists.txt` | 构建配置，集成whisper.cpp |
| `native-lib.cpp` | JNI接口实现 |
| `audio_utils.cpp` | 音频预处理工具 |
| `audio_utils.h` | 音频处理头文件 |

### 2. Kotlin服务层

新建 `WhisperService.kt` (参考demo项目):

```kotlin
class WhisperService(context: Context) {
    // Native methods
    private external fun getVersion(): String
    private external fun loadModel(modelPath: String): Boolean
    private external fun transcribe(audioData: FloatArray, sampleRate: Int): String
    private external fun releaseModel()

    suspend fun loadWhisperModel(): Boolean
    suspend fun transcribeAudio(audioPath: String, onProgress: (String) -> Unit): String
    fun release()
}
```

### 3. 模型管理

更新 `WhisperModelManager.kt`:
- 改为管理 `ggml-base-q8_0.bin` 模型
- 从assets复制模型到内部存储

### 4. 依赖变更

**build.gradle.kts** 移除:
- `com.alphacephei:vosk-android:0.3.32`
- `net.java.dev.jna:jna:5.13.0@aar`
- TensorFlow Lite相关依赖

## 数据流程

```
录音文件 (M4A/3GP)
    │
    ▼
MediaCodec解码 → FloatArray (16kHz)
    │
    ▼
JNI层传递
    │
    ▼
C++音频预处理 (归一化/滤波/降噪/增强)
    │
    ▼
whisper_full() → 中文识别结果
    │
    ▼
返回UI显示
```

## 文件变更清单

### 新增文件

- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/native-lib.cpp`
- `app/src/main/cpp/audio_utils.cpp`
- `app/src/main/cpp/audio_utils.h`
- `app/src/main/assets/ggml-base-q8_0.bin`

### 修改文件

- `app/build.gradle.kts`
- `app/src/main/java/com/example/voicerec/service/WhisperModelManager.kt`
- `app/src/main/java/com/example/voicerec/service/WhisperService.kt` (重命名)
- `app/src/main/java/com/example/voicerec/ui/recordings/RecordingsViewModel.kt`

### 删除内容

- Vosk相关依赖和代码
- TensorFlow Lite相关代码
- `transcribeWithVosk()` 方法
- `tryTranscribeWithTFLite()` 方法

## Whisper参数配置

```cpp
wparams.language      = "zh";      // 中文
wparams.n_threads     = 4;         // 4线程
wparams.no_context    = true;      // 不使用上下文
wparams.single_segment = false;    // 多段处理
```

## 参考

参考项目: `F:\whisper-android-demo`
