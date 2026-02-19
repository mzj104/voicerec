# 24小时声音检测录音应用 - 设计文档

**日期:** 2025-02-19
**用途:** 个人会议/课堂记录

## 1. 概述

一个Android后台录音应用，持续监听环境声音，仅在检测到声音活动时进行录制。录音按天/小时文件夹层级组织存储，用户可浏览历史录音并播放回放。

## 2. 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                        MainActivity                          │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐   │
│  │ 录音列表页面   │  │ 播放详情页面   │  │  设置页面      │   │
│  │ (Recordings)  │  │  (Player)     │  │ (Settings)    │   │
│  └───────────────┘  └───────────────┘  └───────────────┘   │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                   Foreground Recording Service               │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │AudioMonitor │─▶│MediaRecorder│  │  Notification       │ │
│  │(声音检测)    │  │ (实际录音)   │  │  (前台服务通知)     │ │
│  └─────────────┘  └─────────────┘  └─────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                        数据层                                 │
│  ┌─────────────┐              ┌─────────────────────────┐   │
│  │ Room Database│              │   文件存储（层级结构）    │   │
│  │- Recording   │              │ /recordings/            │   │
│  │- DayFolder   │              │   ├─ 2025-02-19/        │   │
│  │- HourFolder  │              │   │   ├─ 14:00/         │   │
│  └─────────────┘              │   │   │   ├─ 143025.m4a  │   │
│                                └─────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## 3. 核心组件

### 3.1 RecordingService（前台录音服务）

**状态机：**
- `MONITORING`：监听状态，检测音量，不录音
- `RECORDING`：录音状态

**关键参数：**
- 音量阈值：100（0-255）
- 静音超时：10秒
- 最短录音：1秒

**生命周期：**
1. 启动 → 初始化 MediaRecorder 和 Visualizer
2. 进入 MONITORING 状态 → 持续检测音量
3. 检测到声音 > 阈值 → 开始录音
4. 检测到静音 → 启动10秒倒计时
5. 10秒内无声音 → 停止录音，保存文件，返回 MONITORING

### 3.2 数据层

**Recording 实体：**
```kotlin
@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey val id: Long = 0,
    val fileName: String,           // "20250219_143025.m4a"
    val filePath: String,           // 完整路径
    val dayFolder: String,          // "2025-02-19"
    val hourFolder: String,         // "14:00"
    val timestamp: Long,            // 录音开始时间
    val durationMs: Long,           // 时长（毫秒）
    val fileSizeBytes: Long,        // 文件大小
)
```

**文件结构：**
- `/recordings/yyyy-MM-dd/HH:mm/yyyMMdd_HHmmss.m4a`

### 3.3 UI 结构

**三个页面：**

1. **RecordingsFragment（录音列表）**
   - 可展开/折叠的树形结构
   - Day → Hour → Recording 三级
   - FAB 控制服务开关

2. **PlayerFragment（播放详情）**
   - 音频波形显示
   - 播放控制（播放/暂停/进度条）
   - 文件信息和操作按钮

3. **SettingsFragment（设置）**
   - 录音服务开关
   - 音量阈值调节
   - 静音超时设置
   - 存储空间显示

## 4. 录音配置

| 参数 | 值 |
|------|-----|
| 格式 | AAC (.m4a) |
| 采样率 | 16000 Hz |
| 声道 | 单声道 (Mono) |
| 比特率 | 64000 bps |
| 音频源 | MediaRecorder.AudioSource.MIC |

## 5. 权限

- `RECORD_AUDIO`：录音权限
- `FOREGROUND_SERVICE`：前台服务
- `POST_NOTIFICATIONS`：通知权限（Android 13+）
- `FOREGROUND_SERVICE_SPECIAL_USE`：后台服务（Android 14+）

## 6. UI 设计风格

- Material Design 3
- 渐变色、圆角卡片、阴影效果
- 平滑的展开/折叠动画
- 精美的图标和配色

## 7. 错误处理

| 场景 | 处理方式 |
|------|----------|
| 存储空间不足 | 通知提示，暂停录音 |
| 录音设备被占用 | 显示提示，等待释放 |
| 服务被系统杀死 | 自动重启 |
| 权限被撤销 | 停止服务，引导重新授权 |

## 8. 技术栈

- Kotlin
- Jetpack (ViewModel, LiveData, Navigation)
- Room Database
- MediaRecorder + Visualizer
- Material Design 3
- DataStore (设置存储)
