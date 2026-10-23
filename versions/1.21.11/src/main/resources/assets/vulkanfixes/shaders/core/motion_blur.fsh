#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// The world as drawn this frame, at full resolution. This pass draws at half resolution.
uniform sampler2D InSampler;

// Paired with minecraft:core/screenquad; unused, pixels are addressed with gl_FragCoord instead.
in vec2 texCoord;

out vec4 fragColor;

// MotionBlur passes its values through the DynamicTransforms block.
// Maps this frame's clip space to last frame's.
#define Reprojection ModelViewMat
// How many frames of camera motion each pixel is smeared across.
#define BlurScale ColorModulator.x
// Longest streak, in UV units.
#define MaxBlur ColorModulator.y
#define MaxSamples ColorModulator.z
// 1 / the size of the target being drawn.
#define TexelSize ModelOffset.xy

// The streak through the pixel at uv, in UV units. Kept the same as in motion_blur_composite.fsh.
vec2 blurAt(vec2 uv) {
    // VulkanMod flips the viewport so clip-space +Y lands on the first row. Depth 1 is infinitely far with its
    // projection, so only the camera's rotation moves the point, not its position.
    vec4 last = Reprojection * vec4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, 1.0, 1.0);
    if (last.w <= 0.0) {
        return vec2(0.0);
    }
    vec2 lastUv = vec2(last.x / last.w * 0.5 + 0.5, 0.5 - last.y / last.w * 0.5);
    vec2 blur = (uv - lastUv) * BlurScale;
    float blurLength = length(blur);
    return blurLength > MaxBlur ? blur * (MaxBlur / blurLength) : blur;
}

void main() {
    vec2 uv = gl_FragCoord.xy * TexelSize;
    vec2 blur = blurAt(uv);
    float blurPixels = length(blur * vec2(textureSize(InSampler, 0)));

    // About one sample per two full-resolution pixels of streak. A fixed per-pixel offset (interleaved gradient
    // noise) turns the steps between samples into fine grain instead of visible copies.
    int samples = int(clamp(ceil(blurPixels * 0.5), 1.0, MaxSamples));
    float offset = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 sum = vec3(0.0);
    for (int i = 0; i < samples; i++) {
        float t = (float(i) + offset) / float(samples) - 0.5;
        sum += texture(InSampler, uv + blur * t).rgb;
    }
    fragColor = vec4(sum / float(samples), 1.0);
}
