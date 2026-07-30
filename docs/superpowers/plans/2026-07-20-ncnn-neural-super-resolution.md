# NCNN 神经网络超分 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 DyLike 里集成 NCNN + Real-ESRGANv3-anime x2，对视频帧做神经网络超分，用混合策略（奇数帧 NCNN + 偶数帧 SGSR1）实现 ~26fps 实时播放。

**Architecture:** NCNN Vulkan 库通过 JNI/CMake 集成到 player-exo 模块。GlRenderView 的 SharpenVideoRenderer 加 NCNN 路径：glReadPixels 从 FBO 读像素 → NCNN 推理 → glTexImage2D 上传结果 → 渲染到屏幕。奇偶帧交替走 NCNN / SGSR1。

**Tech Stack:** NCNN 20260526 (Vulkan) · JNI/NDK · CMake · C++ · Real-ESRGANv3-anime x2 model · GLES 2.0

## Global Constraints

- NCNN 版本：`20260526-android-vulkan` 预编译包
- 模型：`realesr-animevideov3-x2`（从 RealSR-NCNN-Android APK 提取的 1.2MB 模型）
- 只打 arm64-v8a ABI（Release 限制，Debug 加 x86_64 给模拟器）
- SP 键 `labNeuralSuperResolution`（Boolean，默认 false）
- 与 `labMpvSuperResolution`（SGSR1）互斥
- 中文文案
- 不要 bump targetSdk（保持 28）

**实施分支**：`feat/ncnn-super-resolution`（从 main 切出）

---

## 文件结构

### 新建
- `lib-player/player-exo/src/main/cpp/CMakeLists.txt` — CMake 配置
- `lib-player/player-exo/src/main/cpp/ncnn_sr_jni.cpp` — JNI 层（模型加载 + 推理）
- `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/ncnn/NcnnSuperResolution.kt` — Kotlin 封装
- `lib-player/player-exo/src/main/assets/models/realesr-animevideov3-x2.param` — 模型参数
- `lib-player/player-exo/src/main/assets/models/realesr-animevideov3-x2.bin` — 模型权重
- `lib-player/player-exo/src/main/jni/` — NCNN 预编译库（.so + CMake config）

### 修改
- `lib-player/player-exo/build.gradle.kts` — 加 externalNativeBuild + ndk abiFilters
- `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/SharpenVideoRenderer.kt` — 加 NCNN 路径
- `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/GlRenderView.kt` — 传 NCNN 开关
- `dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt` — 加 labNeuralSuperResolution
- `dy-player/src/main/java/me/lingci/dy/player/core/DyPlayerCoreRegistry.kt` — NCNN 分支
- `dy-player/src/main/res/layout/fragment_lab_setting.xml` — NCNN 开关 UI
- `dy-player/src/main/res/values/strings.xml` — NCNN 文案
- `dy-player/src/main/java/me/lingci/dy/player/ui/tool/LabSettingsFragment.kt` — NCNN 开关绑定

---

## Task 1: 准备分支 + 下载 NCNN 库 + 提取模型

**Files:**
- Create: `lib-player/player-exo/src/main/jni/` (NCNN 库)
- Create: `lib-player/player-exo/src/main/assets/models/` (模型文件)

- [ ] **Step 1: 创建分支**

Run:
```bash
cd /c/Users/jiangyunfei/Desktop/DyLike
git checkout main
git checkout -b feat/ncnn-super-resolution
```

- [ ] **Step 2: 下载 NCNN 预编译包**

Run:
```bash
cd /tmp
curl -sL "https://github.com/Tencent/ncnn/releases/download/20260526/ncnn-20260526-android-vulkan.zip" -o ncnn-android-vulkan.zip
unzip -q ncnn-android-vulkan.zip
ls ncnn-20260526-android-vulkan/
```
Expected: 看到 arm64-v8a, armeabi-v7a, x86, x86_64 目录

- [ ] **Step 3: 复制 NCNN 库到项目**

Run:
```bash
cd /c/Users/jiangyunfei/Desktop/DyLike
mkdir -p lib-player/player-exo/src/main/jni
cp -r /tmp/ncnn-20260526-android-vulkan/* lib-player/player-exo/src/main/jni/ncnn/
ls lib-player/player-exo/src/main/jni/ncnn/
```

- [ ] **Step 4: 复制模型文件**

模型已在 /tmp/sr_model/（之前从 RealSR-NCNN-Android APK 提取的）：

Run:
```bash
mkdir -p lib-player/player-exo/src/main/assets/models
cp /tmp/sr_model/x2.param lib-player/player-exo/src/main/assets/models/realesr-animevideov3-x2.param
cp /tmp/sr_model/x2.bin lib-player/player-exo/src/main/assets/models/realesr-animevideov3-x2.bin
ls -la lib-player/player-exo/src/main/assets/models/
```

- [ ] **Step 5: 添加 .gitignore 避免 NCNN 库入 git（太大）**

Create `lib-player/player-exo/src/main/jni/.gitignore`:
```
ncnn/
```

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat(ncnn-sr): add NCNN prebuilt + Real-ESRGANv3 model files

NCNN 20260526 android-vulkan prebuilt in jni/ncnn/ (gitignored).
Model: realesr-animevideov3-x2 (1.2MB param + bin) in assets/models/."
```

---

## Task 2: CMake + build.gradle.kts 配置

**Files:**
- Create: `lib-player/player-exo/src/main/cpp/CMakeLists.txt`
- Modify: `lib-player/player-exo/build.gradle.kts`

- [ ] **Step 1: 写 CMakeLists.txt**

Create `lib-player/player-exo/src/main/cpp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.18)
project("ncnn-sr")

# NCNN 路径（相对于 cpp/ 目录）
set(NCNN_DIR "${CMAKE_SOURCE_DIR}/../jni/ncnn")

# 添加 NCNN 的 CMake 配置
include_directories(${NCNN_DIR}/include/${ANDROID_ABI}/include)
add_library(libncnn STATIC IMPORTED)
set_target_properties(libncnn PROPERTIES
    IMPORTED_LOCATION
    ${NCNN_DIR}/lib/${ANDROID_ABI}/libncnn.a
)

# 我们的 JNI 库
add_library(ncnn-sr SHARED
    ncnn_sr_jni.cpp
)

# 链接
find_library(log-lib log)
find_library(android-lib android)

# Vulkan（NCNN Vulkan 需要）
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    find_library(vulkan-lib vulkan)
    target_link_libraries(ncnn-sr libncnn ${log-lib} ${android-lib} ${vulkan-lib})
else()
    target_link_libraries(ncnn-sr libncnn ${log-lib} ${android-lib})
endif()

# C++ 标准
set_target_properties(ncnn-sr PROPERTIES CXX_STANDARD 14)
```

注意：需要确认 NCNN 预编译包的具体目录结构（include/arm64-v8a/include/ vs include/）。如果路径不对，根据实际 `ls` 结果调整。

- [ ] **Step 2: 修改 build.gradle.kts**

在 `lib-player/player-exo/build.gradle.kts` 的 android {} 块里加：

```kotlin
android {
    // ... 现有配置 ...
    
    defaultConfig {
        // ... 现有配置 ...
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++14", "-fexceptions", "-frtti")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
    
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
```

- [ ] **Step 3: 创建空的 JNI 文件（让编译先通过）**

Create `lib-player/player-exo/src/main/cpp/ncnn_sr_jni.cpp`:

```cpp
#include <jni.h>

extern "C" {

JNIEXPORT jboolean JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeInit(
    JNIEnv* env, jobject thiz, jobject assetManager,
    jstring paramPath, jstring binPath)
{
    return JNI_FALSE;  // 占位，Task 3 实现
}

} // extern "C"
```

- [ ] **Step 4: 编译验证**

Run:
```bash
./gradlew :lib-player:player-exo:assembleDebug 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL（NCNN 库被链接，JNI 编译通过）

如果报错"找不到 ncnn.a"，检查路径 `jni/ncnn/lib/arm64-v8a/libncnn.a` 是否存在。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(ncnn-sr): CMake + NDK build configuration"
```

---

## Task 3: JNI 实现（模型加载 + 推理）

**Files:**
- Modify: `lib-player/player-exo/src/main/cpp/ncnn_sr_jni.cpp`

- [ ] **Step 1: 写完整 JNI 实现**

替换 `lib-player/player-exo/src/main/cpp/ncnn_sr_jni.cpp`：

```cpp
#include <jni.h>
#include <android/asset_manager_jni.h>
#include <ncnn/net.h>
#include <ncnn/gpu.h>
#include <string>
#include <android/log.h>

#define TAG "NcnnSR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static ncnn::Net sr_net;
static bool sr_loaded = false;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeInit(
    JNIEnv* env, jobject thiz, jobject assetManager,
    jstring paramPath, jstring binPath)
{
    if (sr_loaded) {
        LOGI("NCNN model already loaded");
        return JNI_TRUE;
    }

    const char* param = env->GetStringUTFChars(paramPath, nullptr);
    const char* bin = env->GetStringUTFChars(binPath, nullptr);

    // 启用 Vulkan GPU
    if (ncnn::get_gpu_count() > 0) {
        sr_net.vulkan_device = ncnn::get_gpu_device(0);
        sr_net.opt.use_vulkan_compute = true;
        LOGI("Vulkan GPU available, using device 0");
    } else {
        LOGI("No Vulkan GPU, falling back to CPU");
    }
    sr_net.opt.num_threads = 4;
    sr_net.opt.use_fp16_packed = true;
    sr_net.opt.use_fp16_storage = true;

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) {
        LOGE("Failed to get AssetManager");
        return JNI_FALSE;
    }

    int ret_param = sr_net.load_param(mgr, param);
    int ret_bin = sr_net.load_model(mgr, bin);

    env->ReleaseStringUTFChars(paramPath, param);
    env->ReleaseStringUTFChars(binPath, bin);

    if (ret_param != 0 || ret_bin != 0) {
        LOGE("Failed to load model: param=%d, bin=%d", ret_param, ret_bin);
        return JNI_FALSE;
    }

    sr_loaded = true;
    LOGI("NCNN model loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeInfer(
    JNIEnv* env, jobject thiz,
    jbyteArray inputData, jint width, jint height,
    jbyteArray outputData, jint outWidth, jint outHeight)
{
    if (!sr_loaded) return JNI_FALSE;

    // 1. 输入 byte[] → ncnn::Mat (RGB, width × height)
    jbyte* in_data = env->GetByteArrayElements(inputData, nullptr);
    jsize in_size = env->GetArrayLength(inputData);

    // 期望 RGBA 输入，转 RGB
    // in_data 布局: R,G,B,A, R,G,B,A, ... (width × height × 4)
    ncnn::Mat in_mat = ncnn::Mat::from_pixels_rgba(
        (const unsigned char*)in_data, width, height);

    env->ReleaseByteArrayElements(inputData, in_data, JNI_ABORT);

    // 2. NCNN 推理
    ncnn::Extractor ex = sr_net.create_extractor();
    ex.set_num_threads(4);
    if (sr_net.vulkan_device) {
        ex.set_vulkan_compute(true);
    }
    ex.input("data", in_mat);

    ncnn::Mat out_mat;
    int ret = ex.extract("output", out_mat);
    if (ret != 0) {
        LOGE("NCNN extract failed: %d", ret);
        return JNI_FALSE;
    }

    // 3. 输出 ncnn::Mat → byte[] (RGBA)
    // out_mat 尺寸应该是 width*2 × height*2 × 3 (RGB)
    int expected_out_size = outWidth * outHeight * 4;  // RGBA
    jbyte* out_data = env->GetByteArrayElements(outputData, nullptr);
    jsize out_cap = env->GetArrayLength(outputData);

    if (out_cap < expected_out_size) {
        LOGE("Output buffer too small: %d < %d", out_cap, expected_out_size);
        env->ReleaseByteArrayElements(outputData, out_data, JNI_ABORT);
        return JNI_FALSE;
    }

    // ncnn::Mat → RGBA byte[]
    out_mat.to_pixels_rgba((unsigned char*)out_data, ncnn::Mat::PIXEL_RGBA2RGBA);

    env->ReleaseByteArrayElements(outputData, out_data, 0);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeRelease(
    JNIEnv* env, jobject thiz)
{
    sr_net.clear();
    sr_loaded = false;
    LOGI("NCNN model released");
}

} // extern "C"
```

注意：`from_pixels_rgba` / `to_pixels_rgba` / `input("data")` / `extract("output")` 的名称需要跟模型 param 文件里的层名匹配。Task 4 加载模型时如果报错，用 `head x2.param` 看实际层名。

- [ ] **Step 2: 编译验证**

Run:
```bash
./gradlew :lib-player:player-exo:assembleDebug 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add -A
git commit -m "feat(ncnn-sr): JNI implementation - model load + inference"
```

---

## Task 4: Kotlin 封装 NcnnSuperResolution

**Files:**
- Create: `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/ncnn/NcnnSuperResolution.kt`

- [ ] **Step 1: 写 NcnnSuperResolution.kt**

```kotlin
package me.lingci.lib.player.exo.ncnn

import android.content.Context
import android.content.res.AssetManager
import android.util.Log

/**
 * NCNN 神经网络超分封装。
 * 
 * 使用 Real-ESRGANv3-anime x2 模型，通过 NCNN Vulkan GPU 推理。
 * 单帧推理 ~51ms（Adreno 840）。
 * 
 * 使用方式：
 *   val sr = NcnnSuperResolution()
 *   sr.init(context)  // 加载模型
 *   val output = sr.infer(inputRgba, width, height)  // 推理
 *   sr.release()  // 释放
 */
class NcnnSuperResolution {
    companion object {
        private const val TAG = "NcnnSuperResolution"
        private const val PARAM_PATH = "models/realesr-animevideov3-x2.param"
        private const val BIN_PATH = "models/realesr-animevideov3-x2.bin"
    }

    var initialized = false
        private set

    fun init(context: Context): Boolean {
        if (initialized) return true
        try {
            initialized = nativeInit(context.assets, PARAM_PATH, BIN_PATH)
            Log.i(TAG, "init result: $initialized")
        } catch (e: Exception) {
            Log.e(TAG, "init failed: ${e.message}")
            initialized = false
        }
        return initialized
    }

    /**
     * 对一帧 RGBA 数据做 2x 超分。
     * 
     * @param input RGBA byte array (width * height * 4)
     * @param width 输入宽度
     * @param height 输入高度
     * @return 超分后的 RGBA byte array (width*2 * height*2 * 4)，或 null 如果失败
     */
    fun infer(input: ByteArray, width: Int, height: Int): ByteArray? {
        if (!initialized) return null
        val outWidth = width * 2
        val outHeight = height * 2
        val output = ByteArray(outWidth * outHeight * 4)
        val ok = nativeInfer(input, width, height, output, outWidth, outHeight)
        return if (ok) output else null
    }

    fun release() {
        if (initialized) {
            nativeRelease()
            initialized = false
        }
    }

    private external fun nativeInit(
        assetManager: AssetManager,
        paramPath: String,
        binPath: String
    ): Boolean

    private external fun nativeInfer(
        input: ByteArray, width: Int, height: Int,
        output: ByteArray, outWidth: Int, outHeight: Int
    ): Boolean

    private external fun nativeRelease()
}
```

- [ ] **Step 2: 编译验证**

Run:
```bash
./gradlew :lib-player:player-exo:compileDebugKotlin 2>&1 | tail -5
```

- [ ] **Step 3: 提交**

```bash
git add -A
git commit -m "feat(ncnn-sr): Kotlin NcnnSuperResolution wrapper"
```

---

## Task 5: SharpenVideoRenderer 加 NCNN 路径

**Files:**
- Modify: `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/SharpenVideoRenderer.kt`

这是核心改动。在现有 SGSR1 渲染管线里加 NCNN 混合路径。

- [ ] **Step 1: 在 SharpenVideoRenderer 构造函数加 useNcnn 参数**

修改 SharpenVideoRenderer 类签名：

```kotlin
class SharpenVideoRenderer(
    private val context: Context,
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit,
    private val useNcnn: Boolean = false  // 新增：NCNN 神经超分模式
) : GLSurfaceView.Renderer {
```

- [ ] **Step 2: 加 NCNN 字段和帧计数器**

在类里加：

```kotlin
    private var ncnnSr: NcnnSuperResolution? = null
    private var frameCount = 0
    // NCNN 输出纹理（超分后的 2x 分辨率纹理）
    private var ncnnOutputTexId = 0
    // 用于 glReadPixels 的缓冲
    private var pixelBuffer: java.nio.ByteBuffer? = null
```

- [ ] **Step 3: 在 onSurfaceCreated 里初始化 NCNN**

在 onSurfaceCreated 末尾加：

```kotlin
        if (useNcnn) {
            ncnnSr = NcnnSuperResolution().also {
                val ok = it.init(context)
                Log.d("SharpenVideoRenderer", "NCNN init: $ok")
            }
            // 创建 NCNN 输出纹理
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            ncnnOutputTexId = texIds[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ncnnOutputTexId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        }
```

- [ ] **Step 4: 加 NCNN 渲染方法**

```kotlin
    /**
     * NCNN 路径：从 FBO 读像素 → NCNN 推理 → 上传超分结果 → 渲染。
     * 比 SGSR1 慢（~71ms/帧），但画质明显更好。
     */
    private fun drawFrameWithNcnn(fboTexId: Int, fboWidth: Int, fboHeight: Int) {
        val sr = ncnnSr ?: return
        if (!sr.initialized) return

        // 1. 从 FBO 读像素
        val pixelCount = fboWidth * fboHeight * 4
        if (pixelBuffer == null || pixelBuffer!!.capacity() < pixelCount) {
            pixelBuffer = java.nio.ByteBuffer.allocateDirect(pixelCount)
        }
        pixelBuffer!!.rewind()
        GLES20.glReadPixels(0, 0, fboWidth, fboHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)

        // 2. byte[] → NCNN 推理
        val inputBytes = ByteArray(pixelCount)
        pixelBuffer!!.get(inputBytes)
        val output = sr.infer(inputBytes, fboWidth, fboHeight) ?: return

        // 3. 上传超分结果到纹理
        val outWidth = fboWidth * 2
        val outHeight = fboHeight * 2
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ncnnOutputTexId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            outWidth, outHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
            java.nio.ByteBuffer.wrap(output))

        // 4. 渲染超分纹理到屏幕（用 blit shader）
        // TODO: 用 blit shader 渲染 ncnnOutputTexId 到默认 framebuffer
    }

    /**
     * SGSR1 路径：现有逻辑。
     */
    private fun drawFrameWithSgsr1() {
        // 把现有 onDrawFrame 里的 program 渲染逻辑移到这里
    }
```

- [ ] **Step 5: 在 onDrawFrame 里加混合逻辑**

```kotlin
    override fun onDrawFrame(gl: GL10?) {
        // ... 现有 SurfaceTexture 更新逻辑 ...

        if (useNcnn && ncnnSr?.initialized == true) {
            if (frameCount % 2 == 0) {
                // 偶数帧：NCNN 超分
                drawFrameWithNcnn(/* FBO 参数 */)
            } else {
                // 奇数帧：SGSR1 锐化
                drawFrameWithSgsr1()
            }
        } else {
            // 无 NCNN：纯 SGSR1
            drawFrameWithSgsr1()
        }
        frameCount++
    }
```

注意：具体的 FBO 参数（fboTexId, fboWidth, fboHeight）取决于现有双 pass 管线的实现。需要参考 SharpenVideoRenderer 当前代码里 FBO 的实际变量名。

- [ ] **Step 6: 在 release 里释放 NCNN**

```kotlin
    fun release() {
        try {
            surfaceTexture?.release()
            program?.delete()
            ncnnSr?.release()
            if (ncnnOutputTexId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(ncnnOutputTexId), 0)
            }
        } catch (_: Exception) {}
    }
```

- [ ] **Step 7: 编译验证 + 提交**

```bash
./gradlew :lib-player:player-exo:compileDebugKotlin 2>&1 | tail -5
git add -A
git commit -m "feat(ncnn-sr): hybrid NCNN+SGSR1 rendering in SharpenVideoRenderer"
```

---

## Task 6: GlRenderView 传 NCNN 开关

**Files:**
- Modify: `lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/render/GlRenderView.kt`

- [ ] **Step 1: attachToPlayer 传入 useNcnn**

```kotlin
override fun attachToPlayer(player: AbstractPlayer) {
    mediaPlayer = player
    val useNcnn = try {
        val sp = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
        sp.getBoolean("labNeuralSuperResolution", false)
    } catch (_: Throwable) { false }
    
    videoRenderer = SharpenVideoRenderer(context, callback, useNcnn)
    // ...
}
```

- [ ] **Step 2: 编译 + 提交**

---

## Task 7: SP 键 + UI + DyPlayerCoreRegistry

**Files:**
- Modify: `dy-player/src/main/java/me/lingci/dy/player/util/SpUtil.kt`
- Modify: `dy-player/src/main/res/layout/fragment_lab_setting.xml`
- Modify: `dy-player/src/main/res/values/strings.xml`
- Modify: `dy-player/src/main/java/me/lingci/dy/player/ui/tool/LabSettingsFragment.kt`
- Modify: `dy-player/src/main/java/me/lingci/dy/player/core/DyPlayerCoreRegistry.kt`

- [ ] **Step 1: 加 SP 键**

在 SpUtil.kt 加：

```kotlin
var labNeuralSuperResolution by SPManager.boolean(false)
```

- [ ] **Step 2: 加 UI 开关**

在 fragment_lab_setting.xml 的「画质增强」下方加「神经网络超分」开关（复用现有 ConstraintLayout 模板）。

strings.xml 加：

```xml
<string name="hint_lab_neural_sr">神经网络超分</string>
<string name="hint_lab_neural_sr_desc">实时 AI 超分，画质更好但更耗电（仅 ExoPlayer 内核）</string>
```

LabSettingsFragment.kt 加绑定（含互斥逻辑：开 NCNN 时关 SGSR1）。

- [ ] **Step 3: DyPlayerCoreRegistry 加 NCNN 分支**

```kotlin
DyPlayerCore.EXO -> {
    videoView.setPlayerFactory(CustomExoMediaPlayerFactory.create())
    when {
        spUtil.labNeuralSuperResolution -> {
            videoView.setRenderViewFactory(GlRenderViewFactory.create())
        }
        spUtil.labMpvSuperResolution -> {
            videoView.setRenderViewFactory(GlRenderViewFactory.create())
        }
    }
}
```

- [ ] **Step 4: 编译 + 提交**

---

## Task 8: 真机验证

- [ ] **Step 1: 构建完整 APK**

```bash
./gradlew :dy-player:assembleBetaDebug
```

- [ ] **Step 2: 装到真机测试**

测试项：
1. 开关 NCNN 超分 → 播放视频 → 画面应变清晰
2. 混合策略帧率 → 应有 ~20-26fps（肉眼可接受的流畅度）
3. 开关切回 SGSR1 → 正常工作
4. 互斥逻辑 → 开 NCNN 时 SGSR1 自动关

- [ ] **Step 3: 性能数据分析**

测单帧耗时（NCNN 路径 vs SGSR1 路径），验证混合策略效果。

---

## Self-Review Checklist

- [ ] NCNN 库集成正确（CMake + .so 加载）
- [ ] JNI 层正确（模型加载 + 推理 + 释放）
- [ ] Kotlin 封装完整
- [ ] SharpenVideoRenderer 混合逻辑正确（帧计数器 + 双路径）
- [ ] GlRenderView 传参正确
- [ ] SP 键 + UI 开关 + 互斥逻辑
- [ ] DyPlayerCoreRegistry 分支正确
- [ ] 真机验证通过
