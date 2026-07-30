#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <ncnn/net.h>
#include <ncnn/gpu.h>
#include <string>
#include <cstring>
#include <cstdlib>
#include <mutex>

#define TAG "NcnnSR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static ncnn::Net sr_net;
static bool sr_loaded = false;
static std::mutex infer_mutex;  // 保护 NCNN 推理的线程安全

extern "C" {

JNIEXPORT jboolean JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeInit(
    JNIEnv* env, jobject, jobject assetManager,
    jstring paramPath, jstring binPath)
{
    if (sr_loaded) return JNI_TRUE;

    // 禁用 OpenMP affinity（荣耀设备上 __kmp_affinity_initialize 会崩溃）
    setenv("KMP_AFFINITY", "disabled", 1);
    setenv("OMP_PROC_BIND", "false", 1);
    setenv("KMP_TOPOLOGY_METHOD", "0", 1);
    setenv("OMP_WAIT_POLICY", "PASSIVE", 1);

    const char* param = env->GetStringUTFChars(paramPath, nullptr);
    const char* bin   = env->GetStringUTFChars(binPath,   nullptr);

    // 禁用 OpenMP affinity（荣耀设备上会崩溃）
    setenv("KMP_AFFINITY", "disabled", 1);
    setenv("OMP_PROC_BIND", "false", 1);

    sr_net.opt.num_threads = 4;
    sr_net.opt.use_fp16_packed     = true;
    sr_net.opt.use_winograd_convolution = true;
    sr_net.opt.use_sgemm_convolution = true;
    sr_net.opt.use_vulkan_compute = false;

    LOGI("Using 4-thread CPU inference (NCNN_OPENMP=ON, NDK r27c OpenMP, KMP_AFFINITY=disabled)");

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) {
        LOGE("Failed to get AssetManager");
        return JNI_FALSE;
    }

    int rp = sr_net.load_param(mgr, param);
    int rb = sr_net.load_model(mgr, bin);

    env->ReleaseStringUTFChars(paramPath, param);
    env->ReleaseStringUTFChars(binPath,   bin);

    if (rp != 0 || rb != 0) {
        LOGE("Load model failed: param=%d bin=%d", rp, rb);
        return JNI_FALSE;
    }

    sr_loaded = true;
    LOGI("NCNN model loaded OK");
    return JNI_TRUE;
}

/**
 * 推理。JNI 内部分配正确大小的 buffer。
 * 返回 ByteArray: [outW(int32)][outH(int32)][RGBA pixels...]
 */
JNIEXPORT jbyteArray JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeInferAlloc(
    JNIEnv* env, jobject,
    jbyteArray inputData, jint width, jint height)
{
    if (!sr_loaded) return nullptr;

    std::lock_guard<std::mutex> lock(infer_mutex);

    try {
    // 1. RGBA → ncnn::Mat (RGB) + 归一化到 [0,1]
    // Real-ESRGAN 模型期望 [0,1] float32 输入，ncnn from_pixels 输出 [0,255]
    // 需要：value * (1/255) → 用 norm 参数实现
    jbyte* in = env->GetByteArrayElements(inputData, nullptr);
    ncnn::Mat in_mat = ncnn::Mat::from_pixels(
        (const unsigned char*)in, ncnn::Mat::PIXEL_RGBA2RGB, width, height);
    env->ReleaseByteArrayElements(inputData, in, JNI_ABORT);

    // 归一化：x = (x - 0) * (1/255) → [0,1]
    const float norm_vals[3] = { 1.f / 255.f, 1.f / 255.f, 1.f / 255.f };
    in_mat.substract_mean_normalize(nullptr, norm_vals);

    // 2. NCNN 推理
    ncnn::Extractor ex = sr_net.create_extractor();
    ex.set_light_mode(false);

    int ret_in = ex.input("data", in_mat);
    if (ret_in != 0) {
        LOGE("input() failed: %d", ret_in);
        return nullptr;
    }

    ncnn::Mat out_mat;
    int ret_ex = ex.extract("output", out_mat);
    if (ret_ex != 0) {
        LOGE("extract() failed: %d", ret_ex);
        return nullptr;
    }

    LOGI("infer: %dx%d → %dx%d (c=%d)", width, height, out_mat.w, out_mat.h, out_mat.c);

    // 3. 输出 RGB → RGBA
    int outW = out_mat.w;
    int outH = out_mat.h;
    int pixelSize = outW * outH * 4;

    // 返回格式: [outW 4 bytes][outH 4 bytes][RGBA data]
    int totalSize = 8 + pixelSize;
    jbyteArray result = env->NewByteArray(totalSize);

    // 写入宽高
    unsigned char header[8];
    memcpy(header, &outW, 4);
    memcpy(header + 4, &outH, 4);
    env->SetByteArrayRegion(result, 0, 8, (jbyte*)header);

    // 反归一化：[0,1] → [0,255]，然后转 RGBA
    const float denorm_vals[3] = { 255.f, 255.f, 255.f };
    out_mat.substract_mean_normalize(nullptr, denorm_vals);

    // 诊断：输出 float 值前几个像素
    const float* pout = (const float*)out_mat.data;
    LOGI("infer output float: [%.3f,%.3f,%.3f] [%.3f,%.3f,%.3f]",
        pout[0], pout[1], pout[2], pout[3], pout[4], pout[5]);

    // 转换并写入像素
    unsigned char* rgbaBuf = new unsigned char[pixelSize];
    out_mat.to_pixels(rgbaBuf, ncnn::Mat::PIXEL_RGB2RGBA);
    env->SetByteArrayRegion(result, 8, pixelSize, (jbyte*)rgbaBuf);
    delete[] rgbaBuf;

    LOGI("infer OK: returning %dx%d RGBA", outW, outH);
    return result;

    } catch (const std::exception& e) {
        LOGE("NCNN infer exception: %s", e.what());
        return nullptr;
    } catch (...) {
        LOGE("NCNN infer unknown exception");
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeRelease(
    JNIEnv*, jobject)
{
    sr_net.clear();
    sr_loaded = false;
    LOGI("NCNN released");
}

} // extern "C"
