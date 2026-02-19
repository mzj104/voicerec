package com.whispercpp.demo

/**
 * Whisper Context - JNI 接口到 whisper.cpp
 *
 * 这个类通过 JNI 调用原生 C++ 代码来使用 Whisper 模型。
 * 原生代码位于 app/src/main/cpp/whisper_jni.cpp
 */
object WhisperContext {

    private var nativeContext: Long = 0
    private var isInitialized = false

    /**
     * 加载原生库
     */
    init {
        try {
            System.loadLibrary("whisper-jni")
            android.util.Log.d("WhisperContext", "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("WhisperContext", "Failed to load native library: ${e.message}")
        }
    }

    /**
     * 创建 Whisper 上下文并加载模型
     * @param modelPath ggml 模型文件路径
     * @return NativeContext 如果成功，null 如果失败
     */
    fun createContext(modelPath: String): NativeContext? {
        return try {
            val result = nativeInit(modelPath)
            if (result == 0) {
                isInitialized = true
                android.util.Log.d("WhisperContext", "Model loaded successfully")
                NativeContext()
            } else {
                android.util.Log.e("WhisperContext", "Failed to load model, code: $result")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("WhisperContext", "Exception loading model: ${e.message}")
            null
        }
    }

    /**
     * 原生方法：初始化模型
     * @return 0 成功，-1 失败
     */
    private external fun nativeInit(modelPath: String): Int

    /**
     * 原生方法：释放资源
     */
    private external fun nativeFree()

    /**
     * 原生方法：转写音频
     */
    private external fun nativeTranscribe(
        audioSamples: FloatArray,
        language: String
    ): String

    /**
     * 原生方法：检查是否已加载
     */
    private external fun nativeIsLoaded(): Boolean

    /**
     * 原生方法：获取模型信息
     */
    private external fun nativeGetInfo(): String

    /**
     * 原生上下文类
     */
    class NativeContext {
        /**
         * 执行完整转写
         * @param params FullParams 转写参数
         * @param audioSamples PCM 音频数据 (16kHz, 单声道, FloatArray)
         * @return 转写结果文本
         */
        fun fullTranscribe(params: FullParams, audioSamples: FloatArray): String {
            return try {
                nativeTranscribe(audioSamples, params.language)
            } catch (e: Exception) {
                android.util.Log.e("WhisperContext", "Transcribe error: ${e.message}")
                "转写失败: ${e.message}"
            }
        }

        /**
         * 获取文本段落数量
         */
        fun getTextSegmentCount(): Int {
            return 1 // 简化实现
        }

        /**
         * 获取指定索引的文本段落
         */
        fun getTextSegment(index: Int): Segment {
            return Segment(text = "")
        }

        /**
         * 释放上下文
         */
        fun release() {
            try {
                nativeFree()
                isInitialized = false
            } catch (e: Exception) {
                android.util.Log.e("WhisperContext", "Release error: ${e.message}")
            }
        }
    }

    /**
     * 转写参数 (FullParams)
     */
    class FullParams {
        var language: String = "zh"      // 语言代码 (zh=中文)
        var translate: Boolean = false   // 是否翻译为英文
        var nThreads: Int = 4           // 线程数
    }

    /**
     * 文本段落
     */
    class Segment(var text: String = "")

    /**
     * 检查模型是否已加载
     */
    fun isLoaded(): Boolean {
        return try {
            nativeIsLoaded()
        } catch (e: Exception) {
            false
        }
    }
}
