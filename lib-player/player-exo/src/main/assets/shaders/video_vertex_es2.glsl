attribute vec4 aFramePosition;
attribute vec4 aTexCoords;
uniform mat4 uTexTransform;
varying vec2 vTexCoords;
void main() {
 gl_Position = aFramePosition;
 vTexCoords = (uTexTransform * aTexCoords).xy;
}
