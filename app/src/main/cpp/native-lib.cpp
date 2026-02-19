#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

// Include whisper headers
#ifdef __cplusplus
extern "C" {
#endif
#include "whisper.h"
#ifdef __cplusplus
}
#endif

#include "audio_utils.h"

#define LOG_TAG "WhisperNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static struct whisper_context* g_whisper_context = nullptr;

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_voicerec_service_WhisperService_getVersion(JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF("Whisper.cpp for VoiceRec 1.0");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_voicerec_service_WhisperService_loadModel(JNIEnv *env, jobject thiz, jstring model_path) {
    const char* path = env->GetStringUTFChars(model_path, nullptr);

    LOGI("Loading model from: %s", path);

    g_whisper_context = whisper_init_from_file(path);

    env->ReleaseStringUTFChars(model_path, path);

    if (g_whisper_context == nullptr) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_voicerec_service_WhisperService_transcribe(JNIEnv *env, jobject thiz, jfloatArray audio_data, jint sample_rate) {
    if (g_whisper_context == nullptr) {
        LOGE("Model not loaded");
        return env->NewStringUTF("");
    }

    // Get audio data from Java array
    jsize length = env->GetArrayLength(audio_data);
    jfloat* data = env->GetFloatArrayElements(audio_data, nullptr);

    std::vector<float> pcmf32(data, data + length);

    // Apply audio preprocessing
    LOGI("Applying audio preprocessing...");

    normalizeAudio(pcmf32);
    highPassFilter(pcmf32, 80.0f, sample_rate);
    spectralSubtractionDenoise(pcmf32, sample_rate);
    voiceEnhancementFilter(pcmf32, sample_rate);
    adaptiveNoiseGate(pcmf32, 0.05f);

    LOGI("Audio preprocessing completed, processed %zu samples", pcmf32.size());

    // Create whisper parameters
    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_realtime   = false;
    wparams.print_progress   = false;
    wparams.print_timestamps = false;
    wparams.print_special    = false;
    wparams.translate        = false;
    wparams.language         = "zh";
    wparams.n_threads        = 4;
    wparams.offset_ms        = 0;
    wparams.no_context       = true;
    wparams.single_segment   = false;

    // Process audio
    if (whisper_full(g_whisper_context, wparams, pcmf32.data(), pcmf32.size()) != 0) {
        LOGE("Whisper transcription failed");
        env->ReleaseFloatArrayElements(audio_data, data, JNI_ABORT);
        return env->NewStringUTF("");
    }

    // Get transcription result
    std::string result;
    const int n_segments = whisper_full_n_segments(g_whisper_context);
    for (int i = 0; i < n_segments; ++i) {
        const char* text = whisper_full_get_segment_text(g_whisper_context, i);
        result += text;
    }

    env->ReleaseFloatArrayElements(audio_data, data, JNI_ABORT);

    LOGI("Transcription result: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_voicerec_service_WhisperService_releaseModel(JNIEnv *env, jobject thiz) {
    if (g_whisper_context != nullptr) {
        whisper_free(g_whisper_context);
        g_whisper_context = nullptr;
        LOGI("Model released");
    }
}
