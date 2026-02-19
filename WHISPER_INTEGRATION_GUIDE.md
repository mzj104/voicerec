# Whisper 完整集成指南

## 当前状态

应用框架已完成，使用 **Vosk** 作为后备（效果较差）。

## 启用真正 Whisper 的步骤

### 方法 1: 使用 whisper.cpp AAR（最简单）

1. 下载预编译的 `whisper.aar`：
   - 访问 https://github.com/ggerganov/whisper.android/releases
   - 下载 `whisper.aar` 文件

2. 将文件放到：`app/libs/whisper.aar`

3. 在 `app/build.gradle.kts` 中添加：
```kotlin
dependencies {
    implementation(files("libs/whisper.aar"))
}
```

4. 修改 `WhisperTranscribeService.kt` 使用 whisper.android API

### 方法 2: 集成 whisper.cpp 源码（完整）

1. 下载 whisper.cpp 源码：
```bash
cd app/src/main/cpp
git clone https://github.com/ggerganov/whisper.cpp.git
```

2. 复制必要的文件到 cpp 目录：
```
whisper.cpp/
├── whisper.cpp
├── whisper.h
├── ggml.c
├── ggml.h
├── ggml-alloc.c
├── ggml-backend.c
└── ggml-quants.c
```

3. 下载 ggml 模型：
   - https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin
   - 放到 `{context.filesDir}/models/whisper/`

### 方法 3: 使用 Docker 预编译 AAR

```bash
docker run --rm -v $(pwd):/work -w /work ggerganov/whisper.android \
    ./gradlew assembleRelease
```

---

## 临时方案

目前应用使用 **Vosk** 进行语音识别，功能完全可用，但效果不如 Whisper。

## 推荐方案

**最简单**: 方法 1 - 使用预编译的 AAR
**最灵活**: 方法 2 - 源码集成
