/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 fainput Contributors
 *
 * 【fainput / L3】llama.cpp 的 JNI 薄封装。
 *
 * ## 它只干一件事的位置
 *
 * **只在后台整理时用**（充电 / 熄屏）。打字路径上永远不碰它 ——
 * 打字路径用的是位置提升（D-1'）和用户 bigram（L1）。
 *
 * ## 编译开关
 *
 * `FAINPUT_WITH_LLAMA`（由 CMake 按「源码在不在」注入 0/1）：
 * - **1**：真链接 llama.cpp
 * - **0**：整层退化成桩，所有函数返回"不可用"
 *
 * 这样**本地没有 llama.cpp 源码也能编过**，功能只是降级 ——
 * 不会出现"缺一个目录整个项目编不了"。
 *
 * ## API 名全部对着 v0.5.0 的 include/llama.h 核过
 *
 * 不靠记忆：`llama_model_load_from_file` / `llama_init_from_model` /
 * `llama_model_desc` / `llama_model_size` / `llama_model_n_params` …
 * （llama.cpp 的 API 变动极快，所以 tag 必须锁死。）
 */
#include <jni.h>
#include <android/log.h>

#include <mutex>
#include <string>

#define LOG_TAG "fainput-llm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#if FAINPUT_WITH_LLAMA
#include "llama.h"
#endif

namespace {

#if FAINPUT_WITH_LLAMA

std::mutex g_mutex;
llama_model *g_model = nullptr;
bool g_backendReady = false;

/** 后端初始化只做一次（进程内）。 */
void ensureBackendLocked() {
    if (!g_backendReady) {
        llama_backend_init();
        g_backendReady = true;
    }
}

#endif // FAINPUT_WITH_LLAMA

jstring toJString(JNIEnv *env, const std::string &s) {
    return env->NewStringUTF(s.c_str());
}

} // namespace

extern "C" {

/** APK 里到底有没有把 llama.cpp 编进来。 */
JNIEXPORT jboolean JNICALL
Java_org_fcitx_fcitx5_android_data_llm_LlmNative_available(JNIEnv *, jclass) {
#if FAINPUT_WITH_LLAMA
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

/** llama.cpp 自己打的 CPU / 后端能力串 —— 用来确认它真的跑起来了。 */
JNIEXPORT jstring JNICALL
Java_org_fcitx_fcitx5_android_data_llm_LlmNative_systemInfo(JNIEnv *env, jclass) {
#if FAINPUT_WITH_LLAMA
    const char *info = llama_print_system_info();
    return toJString(env, info ? info : "");
#else
    return toJString(env, "(llama.cpp 未编译进本 APK)");
#endif
}

/**
 * 加载模型。
 *
 * @return **null 表示成功**；非 null 是给用户看的错误描述。
 *         （用 null 表示成功，是因为失败原因必须能带回 Kotlin 侧。）
 */
JNIEXPORT jstring JNICALL
Java_org_fcitx_fcitx5_android_data_llm_LlmNative_load(
    JNIEnv *env, jclass, jstring jpath, jboolean vocabOnly) {
#if FAINPUT_WITH_LLAMA
    if (!jpath) return toJString(env, "路径为空");

    const char *cpath = env->GetStringUTFChars(jpath, nullptr);
    if (!cpath) return toJString(env, "路径读不出来");
    std::string path(cpath);
    env->ReleaseStringUTFChars(jpath, cpath);

    std::lock_guard<std::mutex> lock(g_mutex);
    ensureBackendLocked();

    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }

    llama_model_params params = llama_model_default_params();
    // 手机没有独立显存，全部走 CPU（GPU offload 在 Android 上也不稳）
    params.n_gpu_layers = 0;
    params.vocab_only = (vocabOnly == JNI_TRUE);

    g_model = llama_model_load_from_file(path.c_str(), params);
    if (!g_model) {
        LOGW("模型加载失败: %s", path.c_str());
        return toJString(env, "模型加载失败（文件损坏 / 版本不匹配 / 内存不足）");
    }
    LOGI("模型已加载: %s", path.c_str());
    return nullptr;
#else
    return toJString(env, "llama.cpp 未编译进本 APK");
#endif
}

JNIEXPORT jstring JNICALL
Java_org_fcitx_fcitx5_android_data_llm_LlmNative_modelDesc(JNIEnv *env, jclass) {
#if FAINPUT_WITH_LLAMA
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model) return toJString(env, "");
    char buf[256] = {0};
    llama_model_desc(g_model, buf, sizeof(buf));
    return toJString(env, buf);
#else
    return toJString(env, "");
#endif
}

JNIEXPORT jlong JNICALL
Java_org_fcitx_fcitx5_android_data_llm_LlmNative_modelSize(JNIEnv *, jclass) {
#if FAINPUT_WITH_LLAMA
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_model ? (jlong) llama_model_size(g_model) : 0L;
#else
    return 0L;
#endif
}

JNIEXPORT jlong JNICALL
Java_org_fcitx_fcitx5_android_data_llm_LlmNative_modelParams(JNIEnv *, jclass) {
#if FAINPUT_WITH_LLAMA
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_model ? (jlong) llama_model_n_params(g_model) : 0L;
#else
    return 0L;
#endif
}

JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_data_llm_LlmNative_unload(JNIEnv *, jclass) {
#if FAINPUT_WITH_LLAMA
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
        LOGI("模型已释放");
    }
#endif
}

} // extern "C"
