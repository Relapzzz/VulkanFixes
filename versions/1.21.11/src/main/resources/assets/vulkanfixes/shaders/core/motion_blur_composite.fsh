#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// The half-resolution blurred frame from motion_blur.fsh.
uniform sampler2D InSampler;

// Paired with minecraft:core/screenquad; unused, pixels are addressed with gl_FragCoord instead.
in vec2 texCoord;

out vec4 fragColor;

// Same values as motion_blur.fsh; TexelSize is 1 / the full resolution here.
#define Reprojection ModelViewMat
#define BlurScale ColorModulator.x
#define MaxBlur ColorModulator.y
#define TexelSize ModelOffset.xy

// The streak through the pixel at uv, in UV units. Kept the same as in motion_blur.fsh.
vec2 blurAt(vec2 uv) {
    vec4 last = Reprojection * vec4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, 1.0, 1.0);
    if (last.w <= 0.0) {
        return vec2(0.0);
    }
    vec2 lastUv = vec2(last.x / last.w * 0.5 + 0.5, 0.5 - last.y / last.w * 0.5);
    vec2 blur = (uv - lastUv) * BlurScale;
    float blurLength = length(blur);
    return blurLength > MaxBlur ? blur * (MaxBlur / blurLength) : blur;
}

// Blended over the sharp frame: pixels whose streak is under a pixel are left untouched, and the blur fades
// in up to two and a half, so slow camera movement doesn't soften the image.
void main() {
    vec2 uv = gl_FragCoord.xy * TexelSize;
    float blurPixels = length(blurAt(uv) / TexelSize);
    float alpha = clamp((blurPixels - 1.0) / 1.5, 0.0, 1.0);
    if (alpha <= 0.0) {
        discard;
    }
    fragColor = vec4(texture(InSampler, uv).rgb, alpha);
}
