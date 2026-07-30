// SGSR1 (Snapdragon Game Super Resolution 1) - GLES 2.0 双 pass 版
// 原版: https://github.com/SnapdragonGameStudios/snapdragon-gsr
//      sgsr/v1/include/glsl/sgsr1_shader_mobile.frag
// 许可: BSD-3-Clause (Qualcomm Innovation Center)
//
// 双 pass 优化（Mali GPU / 麒麟处理器）:
//   Pass 1: OES 外部纹理 → RGBA FBO（1 次采样，YUV→RGB 只做 1 次）
//   Pass 2: RGBA FBO → SGSR1 → 屏幕（25 次采样读快速 sampler2D）
//   本 shader 是 Pass 2，从 sampler2D 采样而不是 samplerExternalOES

precision mediump float;
precision highp int;

uniform sampler2D uVideoTex;  // 从 RGBA FBO 采样（Pass 1 blit 的输出）
// ViewportInfo: .xy = 1/sourceSize (texel size), .zw = sourceSize (pixels)
uniform vec4 uViewportInfo;  // = vec4(1.0/srcW, 1.0/srcH, srcW, srcH)

varying vec2 vTexCoords;

// USER CONFIGURATION
#define OperationMode 1    // RGBA mode
#define EdgeThreshold (8.0/255.0)
// EdgeSharpness 改为运行时 uniform，由 SharpenVideoRenderer 从 SP 读取传入
// 原版默认 2.0，我们默认 1.0（更温和），用户可在 Lab 设置页 0~3 范围调节
uniform float uSharpenAmount;  // 即 EdgeSharpness，复用现有强度输入框

// fastLanczos2 近似（原版逐行复制）
float fastLanczos2(float x)
{
    float wA = x - 4.0;
    float wB = x * wA - wA;
    wA *= wA;
    return wB * wA;
}

vec2 weightY(float dx, float dy, float c, float std)
{
    float x = ((dx * dx) + (dy * dy)) * 0.55 + clamp(abs(c) * std, 0.0, 1.0);
    float w = fastLanczos2(x);
    return vec2(w, w * c);
}

// 模拟 textureGather(s, coord, mode) for mode=1 (R 通道)
// 返回 vec4(右下, 左下, 右上, 左上) 对应原版 .xyzw
vec4 gatherR(vec2 coord)
{
    vec2 ts = uViewportInfo.xy;  // 单像素步长
    float r00 = texture2D(uVideoTex, coord + vec2(0.0, 0.0)).r;
    float r10 = texture2D(uVideoTex, coord + vec2(ts.x, 0.0)).r;
    float r01 = texture2D(uVideoTex, coord + vec2(0.0, ts.y)).r;
    float r11 = texture2D(uVideoTex, coord + vec2(ts.x, ts.y)).r;
    // textureGather 返回顺序: (右下, 左下, 右上, 左上) = (r11, r01, r10, r00)
    return vec4(r11, r01, r10, r00);
}

void main()
{
    int mode = OperationMode;
    float edgeThreshold = EdgeThreshold;
    float edgeSharpness = uSharpenAmount;  // 从 uniform 读取（原版是 #define）

    // 当前像素采样
    vec4 color;
    color.xyz = texture2D(uVideoTex, vTexCoords).xyz;

    highp float xCenter = abs(vTexCoords.x - 0.5);
    highp float yCenter = abs(vTexCoords.y - 0.5);

    // SGSR1 的坐标系统:
    // imgCoord = uv * sourceSize + (-0.5, 0.5)  (转到源像素坐标，Y 翻转)
    // imgCoordPixel = floor(imgCoord)  (整数像素坐标)
    // coord = imgCoordPixel * texelSize  (转回 UV，对齐到整数像素)
    // pl = imgCoord - imgCoordPixel  (子像素小数位置)
    highp vec2 imgCoord = (vTexCoords * uViewportInfo.zw) + vec2(-0.5, 0.5);
    highp vec2 imgCoordPixel = floor(imgCoord);
    highp vec2 coord = imgCoordPixel * uViewportInfo.xy;
    vec2 pl = imgCoord - imgCoordPixel;

    // left = gather(coord) - 4 个像素的 luma/R 通道
    vec4 left = gatherR(coord);

    // 边缘投票
    float edgeVote = abs(left.z - left.y) + abs(color[mode] - left.y) + abs(color[mode] - left.z);

    if (edgeVote > edgeThreshold)
    {
        coord.x += uViewportInfo.x;  // 右移一个像素

        // right = gather(coord + (1px, 0))
        vec4 right = gatherR(coord + vec2(uViewportInfo.x, 0.0));

        // upDown:
        //   upDown.xy = gather(coord + (0, -1px)).wz
        //   upDown.zw = gather(coord + (0, +1px)).yx
        vec4 gatherUp = gatherR(coord + vec2(0.0, -uViewportInfo.y));
        vec4 gatherDown = gatherR(coord + vec2(0.0, uViewportInfo.y));
        vec4 upDown;
        upDown.xy = gatherUp.wz;
        upDown.zw = gatherDown.yx;

        // 计算均值
        float mean = (left.y + left.z + right.x + right.w) * 0.25;
        left = left - vec4(mean);
        right = right - vec4(mean);
        upDown = upDown - vec4(mean);
        color.w = color[mode] - mean;

        // 标准差
        float sum = abs(left.x) + abs(left.y) + abs(left.z) + abs(left.w)
                  + abs(right.x) + abs(right.y) + abs(right.z) + abs(right.w)
                  + abs(upDown.x) + abs(upDown.y) + abs(upDown.z) + abs(upDown.w);
        float std = 2.181818 / sum;

        // Lanczos2 加权重建（12 个邻居，原版逐行复制）
        vec2 aWY = weightY(pl.x, pl.y + 1.0, upDown.x, std);
        aWY += weightY(pl.x - 1.0, pl.y + 1.0, upDown.y, std);
        aWY += weightY(pl.x - 1.0, pl.y - 2.0, upDown.z, std);
        aWY += weightY(pl.x, pl.y - 2.0, upDown.w, std);
        aWY += weightY(pl.x + 1.0, pl.y - 1.0, left.x, std);
        aWY += weightY(pl.x, pl.y - 1.0, left.y, std);
        aWY += weightY(pl.x, pl.y, left.z, std);
        aWY += weightY(pl.x + 1.0, pl.y, left.w, std);
        aWY += weightY(pl.x - 1.0, pl.y - 1.0, right.x, std);
        aWY += weightY(pl.x - 2.0, pl.y - 1.0, right.y, std);
        aWY += weightY(pl.x - 2.0, pl.y, right.z, std);
        aWY += weightY(pl.x - 1.0, pl.y, right.w, std);

        float finalY = aWY.y / aWY.x;

        // clamp 到邻域范围（防止过冲）
        float maxY = max(max(left.y, left.z), max(right.x, right.w));
        float minY = min(min(left.y, left.z), min(right.x, right.w));
        finalY = clamp(edgeSharpness * finalY, minY, maxY);

        float deltaY = finalY - color.w;
        // 平滑高对比度
        deltaY = clamp(deltaY, -23.0 / 255.0, 23.0 / 255.0);

        color.x = clamp(color.x + deltaY, 0.0, 1.0);
        color.y = clamp(color.y + deltaY, 0.0, 1.0);
        color.z = clamp(color.z + deltaY, 0.0, 1.0);
    }

    color.w = 1.0;
    gl_FragColor = color;
}
