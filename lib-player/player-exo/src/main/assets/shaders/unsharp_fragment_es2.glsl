// Phase 2 锐化 shader：Unsharp Mask（3x3 高斯模糊 + 减法）
// sharp = original + amount * (original - blur)
// 验证通过后可考虑升级为 adaptive-sharpen（更精细的边缘自适应）
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uVideoTex;
uniform vec2 uTexelSize;       // vec2(1.0/videoWidth, 1.0/videoHeight)
uniform float uSharpenAmount;  // 0.0 off; 0.5-1.5 typical
varying vec2 vTexCoords;
void main() {
    vec2 tc = vTexCoords;
    vec3 c  = texture2D(uVideoTex, tc).rgb;
    vec3 l  = texture2D(uVideoTex, tc + vec2(-uTexelSize.x, 0.0)).rgb;
    vec3 r  = texture2D(uVideoTex, tc + vec2( uTexelSize.x, 0.0)).rgb;
    vec3 u  = texture2D(uVideoTex, tc + vec2(0.0, -uTexelSize.y)).rgb;
    vec3 d  = texture2D(uVideoTex, tc + vec2(0.0,  uTexelSize.y)).rgb;
    vec3 ul = texture2D(uVideoTex, tc + vec2(-uTexelSize.x, -uTexelSize.y)).rgb;
    vec3 ur = texture2D(uVideoTex, tc + vec2( uTexelSize.x, -uTexelSize.y)).rgb;
    vec3 dl = texture2D(uVideoTex, tc + vec2(-uTexelSize.x,  uTexelSize.y)).rgb;
    vec3 dr = texture2D(uVideoTex, tc + vec2( uTexelSize.x,  uTexelSize.y)).rgb;
    // 3x3 高斯模糊（权重 4-2-1）
    vec3 blurred = (4.0*c + 2.0*(l+r+u+d) + (ul+ur+dl+dr)) / 16.0;
    // Unsharp mask
    vec3 sharpened = c + uSharpenAmount * (c - blurred);
    gl_FragColor = vec4(clamp(sharpened, 0.0, 1.0), 1.0);
}
