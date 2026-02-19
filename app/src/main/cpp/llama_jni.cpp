#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#include "llama.h"

// Global engine state
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static llama_sampler* g_sampler = nullptr;
static int g_n_threads = 4;

extern "C" JNIEXPORT jint JNICALL
Java_com_example_voicerec_service_LlamaService_initModel(
    JNIEnv* env,
    jobject /* this */,
    jstring model_path,
    jint n_threads) {

    const char* model_path_cstr = env->GetStringUTFChars(model_path, nullptr);
    g_n_threads = n_threads;

    LOGI("Initializing llama model from: %s", model_path_cstr);

    // Initialize backend
    llama_backend_init();

    // Initialize model parameters
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;  // CPU only for now

    // Load model
    g_model = llama_load_model_from_file(model_path_cstr, model_params);
    env->ReleaseStringUTFChars(model_path, model_path_cstr);

    if (g_model == nullptr) {
        LOGE("Failed to load llama model");
        return -1;
    }

    LOGI("Model loaded successfully");

    // Initialize context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;  // Context size
    ctx_params.n_threads = g_n_threads;

    g_ctx = llama_new_context_with_model(g_model, ctx_params);
    if (g_ctx == nullptr) {
        LOGE("Failed to create llama context");
        llama_free_model(g_model);
        g_model = nullptr;
        return -2;
    }

    LOGI("Llama context initialized");

    // Initialize sampler chain
    struct llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(sparams);

    // Add samplers to the chain
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(1234));

    LOGI("Llama sampler initialized");
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_voicerec_service_LlamaService_generateTitle(
    JNIEnv* env,
    jobject /* this */,
    jstring prompt_jstring) {

    if (g_model == nullptr || g_ctx == nullptr || g_sampler == nullptr) {
        LOGE("Model, context, or sampler not initialized");
        return env->NewStringUTF("");
    }

    std::string prompt = env->GetStringUTFChars(prompt_jstring, nullptr);

    // Allocate buffer for tokens
    std::vector<llama_token> tokens;
    tokens.resize(prompt.size() + 64);  // Reserve extra space

    // Tokenize prompt
    int32_t n_tokens = llama_tokenize(
        g_model,
        prompt.c_str(),
        prompt.size(),
        tokens.data(),
        tokens.size(),
        true,   // add_special
        true    // parse_special
    );

    if (n_tokens < 0) {
        LOGE("Tokenization failed");
        return env->NewStringUTF("");
    }

    tokens.resize(n_tokens);
    LOGI("Prompt tokens: %d", n_tokens);

    // Clear the KV cache
    llama_kv_cache_clear(g_ctx);

    // Reset sampler
    llama_sampler_reset(g_sampler);

    // Evaluate prompt tokens
    int n_evaluated = 0;
    for (int i = 0; i < n_tokens; i++) {
        llama_batch batch = llama_batch_get_one(&tokens[i], 1, n_evaluated, 0);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Failed to decode prompt token at position %d", i);
            return env->NewStringUTF("");
        }
        n_evaluated++;
    }

    // Generate response
    std::string result;
    int max_tokens = 50;
    llama_token new_token;

    for (int i = 0; i < max_tokens; i++) {
        // Sample next token
        new_token = llama_sampler_sample(g_sampler, g_ctx, -1);
        llama_sampler_accept(g_sampler, new_token);

        // Check for EOS
        if (new_token == llama_token_eos(g_model)) {
            LOGI("EOS token received");
            break;
        }

        // Convert token to string
        char token_buf[256];
        int n_chars = llama_token_to_piece(g_model, new_token, token_buf, sizeof(token_buf), 0, true);
        if (n_chars > 0) {
            result.append(token_buf, n_chars);
        }

        // Evaluate the new token
        llama_batch batch = llama_batch_get_one(&new_token, 1, n_evaluated + i, 0);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Failed to decode generated token");
            break;
        }
    }

    LOGI("Generated title: %s", result.c_str());

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_voicerec_service_LlamaService_cleanup(
    JNIEnv* /* env */,
    jobject /* this */) {

    LOGI("Cleaning up llama resources");

    if (g_sampler != nullptr) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }

    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }

    if (g_model != nullptr) {
        llama_free_model(g_model);
        g_model = nullptr;
    }

    llama_backend_free();

    LOGI("Llama cleanup complete");
}
