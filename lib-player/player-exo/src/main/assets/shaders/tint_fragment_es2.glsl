// Phase 1 验证 shader：把画面染红，证明 GLSurfaceView pipeline 能跑通。
// 验证通过后会被 unsharp_fragment_es2.glsl 替换。
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uVideoTex;
varying vec2 vTexCoords;
void main() {
    vec4 c = texture2D(uVideoTex, vTexCoords);
    // R 通道拉满，画面整体变红
    gl_FragColor = vec4(1.0, c.g, c.b, c.a);
}
