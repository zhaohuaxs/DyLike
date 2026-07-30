# 设计文档：NCNN 神经网络超分集成

**日期**：2026-07-20
**分支**：`feat/ncnn-super-resolution`（从 main 切出）
**状态**：🔄 设计中

## 背景

DyLike 已有 SGSR1 锐化功能（基于 GLSL shader），但用户反馈"画质不明显"。经真机验证（Adreno 840），**Real-ESRGANv3-anime x2** 神经网络超分：
- 单帧推理 **51ms**（~19fps）
- 效果明显优于 SGSR1

19fps 不够 30fps，采用**混合策略**：奇数帧 NCNN 超分 + 偶数帧 SGSR1 锐化。

## 目标

在 Lab 设置页新增「神经网络超分」开关，开启后用 NCNN + Real-ESRGANv3 模型对视频帧做实时超分，与现有「画质增强」（SGSR1）并存。

## 非目标

- ❌ 不替换 SGSR1（两者并存，用户自选）
- ❌ 不支持多模型选择（先用 v3-anime x2 验证）
- ❌ 不做离线超分（实时播放路线）
- ❌ 不做 NPU 加速（HiAI/QNN），先用 NCNN Vulkan GPU（后续优化方向）

## 用户界面

**位置**：Lab 设置页，在「画质增强」开关下方新增。

| 文案 | 内容 |
|---|---|
| 标题 | 神经网络超分 |
| 副标题 | 实时 AI 超分，画质更好但更耗电（仅 ExoPlayer 内核） |

**与现有开关的关系**：
- 「画质增强」（SGSR1）和「神经网络超分」（NCNN）是互斥的
- 开 NCNN 时自动关 SGSR1，反之亦然

## 数据层

### 新增 SP 键

`labNeuralSuperResolution`，Boolean，默认 false。

### 现有键不变

- `labMpvSuperResolution`（SGSR1 开关）
- `labSuperResolutionStrength`（SGSR1 强度）

## NCNN 集成

### 依赖

| 组件 | 来源 | 大小 |
|---|---|---|
| `libncnn.so`（Vulkan） | ncnn-android-vulkan 预编译包 | ~10MB per ABI |
| `x2.param` + `x2.bin` | Real-ESRGANv3-anime 模型 | 1.2MB |

### CMake 配置

`lib-player/player-exo/src/main/cpp/CMakeLists.txt`：

```cmake
cmake_minimum_required(VERSION 3.18)

# NCNN 预编译包路径
set(ncnn_DIR "${CMAKE_SOURCE_DIR}/../../../jni/ncnn-20231027-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn")

add_library(ncnn-sr SHARED
    ncnn_sr_jni.cpp
)

find_package(ncnn REQUIRED)
target_link_libraries(ncnn-sr ncnn log android)

# Vulkan
find_library(vulkan-lib vulkan)
target_link_libraries(ncnn-sr ${vulkan-lib})
```

### JNI 接口

`lib-player/player-exo/src/main/cpp/ncnn_sr_jni.cpp`：

```cpp
#include <jni.h>
#include <ncnn/net.h>
#include <ncnn/gpu.h>

static ncnn::Net sr_net;
static ncnn::VulkanDevice* vkdev = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeInit(
    JNIEnv* env, jobject thiz, jobject assetManager,
    jstring paramPath, jstring binPath)
{
    // 初始化 Vulkan GPU
    if (ncnn::get_gpu_count() > 0) {
        vkdev = ncnn::get_gpu_device(0);
        sr_net.vulkan_device = vkdev;
    }
    sr_net.opt.use_vulkan_compute = true;
    sr_net.opt.num_threads = 4;

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    // 从 assets 加载模型
    // ...
    return sr_net.load_param(mgr, "x2.param") == 0 &&
           sr_net.load_model(mgr, "x2.bin") == 0;
}

JNIEXPORT jboolean JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeInfer(
    JNIEnv* env, jobject thiz,
    jbyteArray inputData, jint width, jint height,
    jbyteArray outputData, jobjectArray outputSize)
{
    // 1. 输入 byte[] → ncnn::Mat (RGB, width×height)
    // 2. NCNN 推理 → 输出 ncnn::Mat (2× width × 2× height)
    // 3. 输出 ncnn::Mat → byte[]
    // 返回推理是否成功
}

JNIEXPORT void JNICALL
Java_me_lingci_lib_player_exo_ncnn_NcnnSuperResolution_nativeRelease(
    JNIEnv* env, jobject thiz)
{
    sr_net.clear();
}

} // extern "C"
```

### Kotlin 封装

`lib-player/player-exo/src/main/java/me/lingci/lib/player/exo/ncnn/NcnnSuperResolution.kt`：

```kotlin
class NcnnSuperResolution {
    private var initialized = false

    fun init(context: Context): Boolean {
        initialized = nativeInit(context.assets, "x2.param", "x2.bin")
        return initialized
    }

    /**
     * 对一帧 RGB 数据做超分。
     * @param input RGBA byte array (width * height * 4)
     * @param width 输入宽度
     * @param height 输入高度
     * @return 超分后的 RGBA byte array (width*2 * height*2 * 4)，或 null 如果失败
     */
    fun infer(input: ByteArray, width: Int, height: Int): ByteArray? {
        if (!initialized) return null
        return nativeInfer(input, width, height)
    }

    fun release() {
        if (initialized) {
            nativeRelease()
            initialized = false
        }
    }

    private external fun nativeInit(assetManager: AssetManager, paramPath: String, binPath: String): Boolean
    private external fun nativeInfer(input: ByteArray, width: Int, height: Int): ByteArray?
    private external fun nativeRelease()
}
```

## 渲染管线改造

### 混合策略架构

```
ExoPlayer → SurfaceTexture (OES)
                ↓
     GlRenderView Pass 1: blit OES → FBO_A (RGBA, 源分辨率)
                ↓
     ┌── 帧计数器 (frameCount % 2)
     │
     ├── 奇数帧 (NCNN 路径):
     │   1. glReadPixels 从 FBO_A 读出 RGBA byte[]
     │   2. NcnnSuperResolution.infer(byte[], w, h) → 超分 byte[] (2w × 2h)
     │   3. glTexImage2D 上传超分结果到 FBO_B (2× 分辨率)
     │   4. 渲染 FBO_B → 屏幕
     │
     └── 偶数帧 (SGSR1 路径):
         1. SGSR1 shader 直接处理 FBO_A → FBO_C (源分辨率)
         2. 渲染 FBO_C → 屏幕
```

### SharpenVideoRenderer 改造

在现有 SharpenVideoRenderer 里加 NCNN 路径分支：

```kotlin
class SharpenVideoRenderer(
    private val context: Context,
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit,
    private val useNcnnSuperResolution: Boolean = false  // 新增
) : GLSurfaceView.Renderer {

    private var ncnnSr: NcnnSuperResolution? = null
    private var frameCount = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // ... 现有初始化 ...
        if (useNcnnSuperResolution) {
            ncnnSr = NcnnSuperResolution().also { it.init(context) }
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        // ... 更新 SurfaceTexture ...

        if (useNcnnSuperResolution && ncnnSr?.initialized == true) {
            if (frameCount % 2 == 0) {
                // 偶数帧：NCNN 超分
                drawFrameWithNcnn()
            } else {
                // 奇数帧：SGSR1 锐化（现有逻辑）
                drawFrameWithSgsr1()
            }
        } else {
            // 无 NCNN：纯 SGSR1
            drawFrameWithSgsr1()
        }
        frameCount++
    }

    private fun drawFrameWithNcnn() {
        // 1. 先 blit OES → FBO
        // 2. glReadPixels 从 FBO 读像素
        // 3. ncnnSr.infer() 超分
        // 4. glTexImage2D 上传结果
        // 5. 渲染到屏幕
    }
}
```

### GlRenderView 改造

attachToPlayer 传入 NCNN 开关：

```kotlin
override fun attachToPlayer(player: AbstractPlayer) {
    val useNcnn = spUtil.labNeuralSuperResolution
    videoRenderer = SharpenVideoRenderer(context, callback, useNcnn)
    // ...
}
```

## DyPlayerCoreRegistry 接入

```kotlin
DyPlayerCore.EXO -> {
    videoView.setPlayerFactory(CustomExoMediaPlayerFactory.create())
    when {
        spUtil.labNeuralSuperResolution -> {
            // NCNN 超分：用 GlRenderView + NCNN 模式
            videoView.setRenderViewFactory(GlRenderViewFactory.create())
            // 互斥：关掉 SGSR1
            spUtil.labMpvSuperResolution = false
        }
        spUtil.labMpvSuperResolution -> {
            videoView.setRenderViewFactory(GlRenderViewFactory.create())
        }
    }
}
```

## 边界情况

| 场景 | 行为 |
|---|---|
| 设备不支持 Vulkan | NCNN 降级到 CPU 模式（慢但能用），或回退到 SGSR1 |
| NCNN 初始化失败 | 回退到 SGSR1，Toast 提示"神经超分不可用" |
| 视频分辨率 > 720p | NCNN 超分耗时增加，可能掉帧严重；自动降级为每 3 帧超分一次 |
| 开关切换 | 重建 player（跟 SGSR1 一样的 replay 机制） |
| 短视频 | 跟 SGSR1 一样支持 |

## 性能预期

| 帧类型 | 耗时 | 来源 |
|---|---|---|
| NCNN 超分帧 | ~51ms + ~20ms I/O = ~71ms | Real-ESRGANv3 + glReadPixels/upload |
| SGSR1 锐化帧 | ~5ms | 现有 GLSL shader |
| 混合平均 | ~38ms/帧 ≈ 26fps | (71+5)/2 |
| 丢帧保护 | 如果连续 3 帧 >100ms，自动降为每 3 帧超分 1 次 | 自适应 |

## 工作量估计

| 任务 | 时间 |
|---|---|
| NCNN 库集成 + CMake + JNI | 4-6h |
| NcnnSuperResolution Kotlin 封装 | 1h |
| SharpenVideoRenderer 混合策略 | 2-3h |
| GlRenderView + DyPlayerCoreRegistry 接入 | 1h |
| UI 开关 + 互斥逻辑 | 1h |
| 真机验证 + 调优 | 2-3h |
| **总计** | **11-15h（1.5-2 天）** |

## 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| glReadPixels 性能差 | I/O 可能 50ms+ | 用 PBO (Pixel Buffer Object) 异步读 |
| NCNN Vulkan 初始化慢 | 首帧延迟 1-2s | 预加载模型 |
| 混合策略画面闪烁 | 奇偶帧质量不一致 | 可能需要时序平滑 |
| APK 体积增加 | +10MB/ABI | 只打 arm64（release 已是 arm64） |
