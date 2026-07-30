// RGBA → 屏幕 blit shader（用于渲染 NCNN 超分输出到屏幕）
// 跟 blit_oes_to_rgba_es2.glsl 类似，但读普通 sampler2D（不是 OES）
precision mediump float;
uniform sampler2D uTexSampler;
uniform mat4 uTexTransform;
varying vec2 vTexCoords;

void main() {
    gl_FragColor = texture2D(uTexSampler, vTexCoords);
}
