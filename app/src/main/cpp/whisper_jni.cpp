#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <cstdio>
#include <cmath>

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 简化的 Whisper 上下文（实际需要集成完整的 whisper.cpp）
struct WhisperContext {
    bool initialized = false;
    std::string modelPath;
};

// 全局上下文
static WhisperContext g_context = {false, ""};

// 检查文件是否存在
bool fileExists(const char* path) {
    FILE* file = fopen(path, "rb");
    if (file) {
        fclose(file);
        return true;
    }
    return false;
}

extern "C" {

/**
 * 初始化 Whisper 模型
 * 返回: 0 成功, -1 失败
 */
JNIEXPORT jint JNICALL
Java_com_whispercpp_demo_WhisperContext_nativeInit(
    JNIEnv* env,
    jobject thiz,
    jstring modelPath) {

    const char* modelPathStr = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Initializing Whisper with model: %s", modelPathStr);

    // 检查模型文件是否存在
    if (!fileExists(modelPathStr)) {
        LOGE("Model file not found: %s", modelPathStr);
        env->ReleaseStringUTFChars(modelPath, modelPathStr);
        return -1;
    }

    // 保存模型路径
    g_context.modelPath = modelPathStr;
    g_context.initialized = true;

    env->ReleaseStringUTFChars(modelPath, modelPathStr);

    LOGI("Whisper initialized successfully (placeholder)");
    return 0;
}

/**
 * 释放 Whisper 上下文
 */
JNIEXPORT void JNICALL
Java_com_whispercpp_demo_WhisperContext_nativeFree(
    JNIEnv* env,
    jobject thiz) {

    g_context.initialized = false;
    g_context.modelPath.clear();
    LOGI("Whisper context freed");
}

/**
 * 执行语音转文字
 * 这是一个简化的实现，真正的 Whisper 需要完整的 whisper.cpp 库
 */
JNIEXPORT jstring JNICALL
Java_com_whispercpp_demo_WhisperContext_nativeTranscribe(
    JNIEnv* env,
    jobject thiz,
    jfloatArray audioSamples,
    jstring language) {

    if (!g_context.initialized) {
        return env->NewStringUTF("错误: 模型未初始化");
    }

    jsize length = env->GetArrayLength(audioSamples);
    jfloat* samples = env->GetFloatArrayElements(audioSamples, nullptr);

    jstring langStr = language;
    const char* lang = env->GetStringUTFChars(langStr, nullptr);

    LOGI("Transcribing %d samples, language: %s", length, lang);

    // 简化的音频分析
    double sum = 0.0;
    double sumSq = 0.0;
    for (int i = 0; i < length && i < 10000; i++) {
        sum += samples[i];
        sumSq += samples[i] * samples[i];
    }
    double avg = sum / (length < 10000 ? length : 10000);
    double rms = sqrt(sumSq / (length < 10000 ? length : 10000) - avg * avg);

    env->ReleaseFloatArrayElements(audioSamples, samples, 0);
    env->ReleaseStringUTFChars(language, lang);

    // 基于音频能量的简单"检测"
    std::string result;

    if (rms < 0.01) {
        result = "（检测到静音或极低音量）";
    } else if (rms < 0.05) {
        result = "（检测到较低音量的语音）";
    } else {
        result = "检测到语音信号，时长: ";
        char duration[32];
        snprintf(duration, sizeof(duration), "%.1f 秒", length / 16000.0);
        result += duration;
    }

    result += "\n\n[这是 Whisper JNI 的简化实现]\n";
    result += "要启用完整的 Whisper 转写，需要:\n";
    result += "1. 下载 whisper.cpp 源码\n";
    result += "2. 在 CMakeLists.txt 中添加 whisper.cpp\n";
    result += "3. 调用 whisper_full() 函数\n";
    result += "\n当前模型文件: " + g_context.modelPath;

    return env->NewStringUTF(result.c_str());
}

/**
 * 检查模型是否已加载
 */
JNIEXPORT jboolean JNICALL
Java_com_whispercpp_demo_WhisperContext_nativeIsLoaded(
    JNIEnv* env,
    jobject thiz) {

    return g_context.initialized ? JNI_TRUE : JNI_FALSE;
}

/**
 * 获取模型信息
 */
JNIEXPORT jstring JNICALL
Java_com_whispercpp_demo_WhisperContext_nativeGetInfo(
    JNIEnv* env,
    jobject thiz) {

    if (!g_context.initialized) {
        return env->NewStringUTF("模型未加载");
    }

    char info[256];
    snprintf(info, sizeof(info),
        "Whisper 模型信息\n"
        "路径: %s\n"
        "状态: 已加载",
        g_context.modelPath.c_str());

    return env->NewStringUTF(info);
}

} // extern "C"
