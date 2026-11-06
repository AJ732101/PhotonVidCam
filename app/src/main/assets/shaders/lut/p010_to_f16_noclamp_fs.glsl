#version 300 es
precision highp float;
precision highp usampler2D;

in vec2 v_texCoord;

uniform usampler2D y_texture;
uniform usampler2D u_texture;
uniform float uGamma;

out vec4 FragColor;

void main() {
    float y_10bit = float(texture(y_texture, v_texCoord).r >> 6);
    uvec2 uv_raw = texture(u_texture, v_texCoord).rg;
    float u_10bit = float(uv_raw.r >> 6);
    float v_10bit = float(uv_raw.g >> 6);

    // Full Range 10-bit normalization
    float y_prime = y_10bit / 1023.0;
    float cb_prime = (u_10bit - 512.0) / 1023.0;
    float cr_prime = (v_10bit - 512.0) / 1023.0;

    // BT.2020 Full Range Matrix
    // Often used as the container matrix for 10-bit WCG/HDR data on Android
    float r = y_prime + 1.4746 * cr_prime;
    float g = y_prime - 0.16455 * cb_prime - 0.57135 * cr_prime;
    float b = y_prime + 1.8814 * cb_prime;

    vec3 rgb = vec3(r, g, b);
    if (uGamma > 0.0) {
        rgb = pow(max(rgb, vec3(0.0)), vec3(1.0 / uGamma));
    }

    FragColor = vec4(rgb, 1.0);
}
