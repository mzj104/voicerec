# Whisper Android 集成说明

## 当前状态

`com.github.ggerganov:whisper.android:1.0.0` 在 JitPack 上不可用。

## 解决方案

### 方案 1: 使用预编译的 AAR

1. 从以下地址下载 `whisper.android.aar`:
   - https://github.com/ggerganov/whisper.cpp/releases
   - 或从 CI artifacts 获取

2. 将文件放到 `app/libs/` 目录

3. 修改 `app/build.gradle.kts`:
```kotlin
dependencies {
    // 注释掉 JitPack 依赖
    // implementation("com.github.ggerganov:whisper.android:1.0.0")

    // 使用本地 AAR
    implementation(files("libs/whisper.android.aar"))
}
```

4. 在 `settings.gradle.kts` 中允许项目仓库:
```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    // ...
}
```

### 方案 2: 使用 JNI 直接集成 whisper.cpp

需要配置 NDK 和 CMake，将 whisper.cpp 编译为原生库。

### 方案 3: 使用其他库

- **Vosk** - 可用但中文效果较差
- **云端 API** - 讯飞、阿里云等在线服务

### 方案 4: 等待官方支持

关注 whisper.cpp 项目获取 Android 支持更新。

## 临时方案

暂时注释掉 Whisper 相关代码，应用可以正常运行，只是转文字功能不可用。
