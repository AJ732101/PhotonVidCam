#version 300 es
precision mediump float;

in vec2 vTexCoord;

uniform sampler2D y_texture;
uniform sampler2D u_texture;
uniform sampler2D v_texture;
uniform float uGamma;

out vec4 outColor;

void main() {
    float y = texture(y_texture, vTexCoord).r;
    float u = texture(u_texture, vTexCoord).r - 0.5;
    float v = texture(v_texture, vTexCoord).r - 0.5;

    float r = y + 1.402 * v;
    float g = y - 0.344 * u - 0.714 * v;
    float b = y + 1.772 * u;

    vec3 rgb = clamp(vec3(r, g, b), 0.0, 1.0);
    if (uGamma > 0.0) {
        rgb = pow(rgb, vec3(1.0 / uGamma));
    }

    outColor = vec4(rgb, 1.0);
}
