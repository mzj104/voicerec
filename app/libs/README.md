# Whisper Android AAR

## 获取方式

### 方法 1: 从 GitHub Releases 下载

访问 https://github.com/ggerganov/whisper.cpp/releases
下载最新的 `whisper.android.aar` 文件到本目录

### 方法 2: 自己编译

1. 克隆 whisper.cpp 项目
2. 按照 Android 编译说明生成 AAR

### 方法 3: 使用 JitPack (如果可用)

如果 AAR 不可用，可以在 app/build.gradle.kts 中启用 JitPack 依赖：

```kotlin
// 取消注释这行
implementation("com.github.ggerganov:whisper.android:1.0.0")
```

## 当前状态

如果 whisper.android.aar 文件不存在，应用将无法构建。
请将 AAR 文件放入本目录后再构建。
