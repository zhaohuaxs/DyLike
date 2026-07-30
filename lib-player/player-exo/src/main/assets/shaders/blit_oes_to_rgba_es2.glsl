// OES → RGBA blit shader（Pass 1）
// 简单地把 OES 外部纹理拷贝到 RGBA FBO，只采样 1 次。
// 这样后续 SGSR1 的 ~25 次采样读快速 sampler2D 而不是慢速 samplerExternalOES。
// 对 Mali GPU（麒麟处理器）性能提升尤其明显。

#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uVideoTex;
uniform mat4 uTexTransform;
varying vec2 vTexCoords;

void main() {
    gl_FragColor = texture2D(uVideoTex, vTexCoords);
}
