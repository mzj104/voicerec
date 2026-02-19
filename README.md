# VoiceRec

[![Android API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=24)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

> android studio 项目，集成whisper和qwen2.5 1.5B，实现离线语音记录
> 
> 智能语音录音应用 - 自动检测声音、录音并转录为文字，由 AI 驱动本地处理，保护您的隐私。

## 功能特性

- **智能语音检测**
  - 后台持续监测环境声音
  - 检测到语音时自动开始录音（可配置阈值）
  - 静音 10 分钟后自动停止录音
  - 最短 2 秒录音时长，避免误触发

- **实时语音转文字**
  - 集成 Whisper（OpenAI 语音识别模型）
  - 录音结束后自动转录
  - 支持多种 Whisper 模型尺寸（Base、Small、Large V3 Turbo）
  - 完全离线处理，无需网络连接

- **AI 标题生成**
  - 使用 Llama.cpp（本地大语言模型）智能生成标题
  - 转录完成后自动生成标题
  - 完全在设备本地运行

- **录音管理**
  - 按日期和时间组织的文件夹结构
  - 使用 Room 数据库存储元数据
  - 列表视图与播放功能
  - 一键复制转录文本

- **后台服务**
  - 前台服务持续监控
  - 录音期间保持设备唤醒
  - 通知栏显示当前状态
  - 完善的资源管理

## 技术栈

### 核心技术
- **Kotlin** - 主要编程语言
- **AndroidX** - 现代 Android 支持库
- **Room** - SQLite ORM
- **DataStore** - 偏好设置存储
- **Navigation Component** - Fragment 导航
- **View Binding** - 类型安全视图访问
- **Coroutines** - 异步编程

### AI/ML 库
- **Whisper.cpp** - Whisper 语音识别 C++ 实现
- **Llama.cpp** - LLaMA 大语言模型 C++ 实现
- **JNI** - Native C++ 集成

### 其他库
- **OkHttp** - 网络请求（模型下载）
- **RecyclerView** - 列表展示
- **Material Design** - UI 组件

## 系统要求

- Android Studio Arctic Fox 或更高版本
- Android SDK API 24+ (Android 7.0)
- Android NDK 26.1.10909125
- CMake 3.22.1
- 支持 arm64-v8a 或 armeabi-v7a 架构的设备

## 快速开始

### 克隆项目

```bash
git clone https://github.com/yourusername/voicerec.git
cd voicerec
```

### 构建项目

1. 在 Android Studio 中打开项目
2. 同步 Gradle 依赖
3. 连接 Android 设备或启动模拟器
4. 点击运行按钮或执行：

```bash
./gradlew assembleDebug
```

### 安装

```bash
./gradlew installDebug
```

## 项目结构

```
voicerec/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/voicerec/
│   │   │   ├── MainActivity.kt                 # 主入口
│   │   │   ├── service/                        # 后台服务
│   │   │   │   ├── RecordingService.kt        # 核心录音服务
│   │   │   │   ├── WhisperService.kt          # Whisper 转录服务
│   │   │   │   ├── WhisperModelManager.kt     # 模型管理
│   │   │   │   ├── LlamaService.kt            # LLM 标题生成
│   │   │   │   └── WhisperModel.kt            # 模型定义
│   │   │   ├── data/                           # 数据层
│   │   │   │   ├── Recording.kt               # 实体类
│   │   │   │   ├── RecordingDao.kt            # DAO
│   │   │   │   ├── AppDatabase.kt              # 数据库
│   │   │   │   └── RecordingRepository.kt     # 仓库
│   │   │   └── ui/                             # UI 组件
│   │   │       ├── recordings/                 # 录音列表
│   │   │       ├── player/                     # 音频播放器
│   │   │       └── settings/                  # 设置页面
│   │   ├── cpp/                                # Native C++ 代码
│   │   │   ├── whisper_jni.cpp                # Whisper JNI 桥接
│   │   │   ├── llama_jni.cpp                  # LLaMA JNI 桥接
│   │   │   ├── audio_utils.cpp                # 音频工具
│   │   │   └── CMakeLists.txt                 # Native 构建配置
│   │   └── res/                                # 资源文件
│   └── libs/                                   # 第三方库
├── gradle/                                     # Gradle 配置
├── WHISPER_INTEGRATION.md                      # Whisper 集成文档
└── README.md
```

## 架构设计

- **MVVM 架构** - ViewModel + LiveData 模式
- **Clean Architecture** - 仓库模式实现关注点分离
- **依赖注入** - 手动注入（未使用 Hilt/Dagger）
- **后台处理** - 具有完整生命周期管理的前台服务
- **离线优先** - 所有处理在本地完成，无需网络

## 权限说明

应用需要以下权限：

- `RECORD_AUDIO` - 录制音频
- `FOREGROUND_SERVICE` - 前台服务
- `FOREGROUND_SERVICE_MICROPHONE` - 麦克风前台服务
- `POST_NOTIFICATIONS` - 显示通知
- `WRITE_EXTERNAL_STORAGE` - 保存录音文件

## 使用指南

### 首次使用

1. 启动应用并授予录音和存储权限
2. 应用将在后台开始监测环境声音
3. 检测到语音时自动开始录音

### 查看录音

1. 点击录音列表查看所有录音
2. 点击录音项播放音频
3. 长按复制转录文本

### 设置

- 调整语音检测阈值
- 选择 Whisper 模型尺寸
- 配置录音参数

## 模型配置

### Whisper 模型

支持的模型：
- `ggml-base.bin` - 基础模型，快速
- `ggml-small.bin` - 小型模型，平衡
- `ggml-large-v3-turbo.bin` - 大型模型，高精度

将模型文件放置在 `app/src/main/assets/` 目录下。

### Llama 模型

用于标题生成的轻量级模型文件也需放置在 assets 目录。

## 开发指南

### 构建 Native 库

```bash
cd app/src/main/cpp
cmake -B build
cmake --build build
```

### 运行测试

```bash
./gradlew test
./gradlew connectedAndroidTest
```

## 效果展示

<p align="center">
  <img src="https://github.com/user-attachments/assets/82dba7da-6fb4-44e0-bec6-4105c4398b78" width="30%">
  <img src="https://github.com/user-attachments/assets/a5ce4f32-5cc2-421d-84fd-6de425ce477b" width="30%">
  <img src="https://github.com/user-attachments/assets/8d5b8d58-bc84-4bf5-92cc-a14d0d6c140a" width="30%">
</p>



## 贡献

欢迎贡献！请随时提交 Pull Request。

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件

## 致谢

- [Whisper.cpp](https://github.com/ggerganov/whisper.cpp) - OpenAI Whisper 的 C++ 实现
- [Llama.cpp](https://github.com/ggerganov/llama.cpp) - LLaMA 的 C++ 实现
- [OpenAI](https://openai.com/) - Whisper 模型

## 联系方式

如有问题或建议，请提交 [Issue](https://github.com/yourusername/voicerec/issues)

---

**注意**: 本应用仅用于个人学习研究目的。请遵守当地法律法规，在使用录音功能前征得相关人员同意。
