// 占位文件：对没有 NCNN 库的 ABI（如 armeabi-v7a），生成空 .so
// native 方法调用会返回默认值（JNI_FALSE / nullptr）
#include <jni.h>

extern "C" {

JNIEXPORT jboolean JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeInit(
    JNIEnv*, jobject, jobject, jstring, jstring) { return JNI_FALSE; }

JNIEXPORT jbyteArray JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeInferAlloc(
    JNIEnv*, jobject, jbyteArray, jint, jint) { return nullptr; }

JNIEXPORT void JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeRelease(
    JNIEnv*, jobject) {}

}
