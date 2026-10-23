#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// Paired with minecraft:core/screenquad, which outputs texCoord.
in vec2 texCoord;

out vec4 fragColor;

// Writes the fog color, alpha included, to every pixel: the same values the vanilla clear writes.
void main() {
    fragColor = ColorModulator;
}
