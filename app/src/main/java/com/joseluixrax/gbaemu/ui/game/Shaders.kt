package com.joseluixrax.gbaemu.ui.game

/** Screen filters, built from small GLSL pieces (OpenGL ES 2.0). */
object Shaders {
    enum class Filter { NEAREST, LINEAR, SHARP }
    enum class Effect { NONE, SCANLINES, LCD }

    fun filterFrom(value: String) = when (value) {
        "nearest" -> Filter.NEAREST
        "linear" -> Filter.LINEAR
        else -> Filter.SHARP
    }

    fun effectFrom(value: String) = when (value) {
        "scanlines" -> Effect.SCANLINES
        "lcd" -> Effect.LCD
        else -> Effect.NONE
    }

    const val VERTEX = """
attribute vec2 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
    vTexCoord = aTexCoord;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

    fun fragment(filter: Filter, effect: Effect, colorCorrection: Boolean): String = buildString {
        append(
            """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexture;
uniform vec2 uTextureSize;
uniform vec2 uOutputSize;
varying vec2 vTexCoord;
""",
        )
        when (filter) {
            Filter.NEAREST, Filter.LINEAR -> append(
                """
vec3 sampleGame(vec2 uv) {
    return texture2D(uTexture, uv).rgb;
}
""",
            )
            // "Sharp bilinear": integer-scale nearest, then a thin smooth band
            // between pixels. Crisp like nearest but without uneven pixels.
            Filter.SHARP -> append(
                """
vec3 sampleGame(vec2 uv) {
    vec2 texel = uv * uTextureSize;
    vec2 scale = max(floor(uOutputSize / uTextureSize), vec2(1.0));
    vec2 texelFloored = floor(texel);
    vec2 s = fract(texel);
    vec2 regionRange = 0.5 - 0.5 / scale;
    vec2 centerDist = s - 0.5;
    vec2 f = (centerDist - clamp(centerDist, -regionRange, regionRange)) * scale + 0.5;
    return texture2D(uTexture, (texelFloored + f) / uTextureSize).rgb;
}
""",
            )
        }
        if (colorCorrection) {
            // GBA LCD colours (based on Pokefan531's "gba-color" shader).
            append(
                """
vec3 correctColor(vec3 c) {
    vec3 screen = pow(c, vec3(2.7));
    screen = clamp(screen * 0.94, 0.0, 1.0);
    mat3 lcd = mat3(0.82, 0.125, 0.195,
                    0.24, 0.665, 0.075,
                    -0.06, 0.21, 0.73);
    return pow(clamp(lcd * screen, 0.0, 1.0), vec3(1.0 / 2.2));
}
""",
            )
        }
        append(
            """
void main() {
    vec3 rgb = sampleGame(vTexCoord);
""",
        )
        when (effect) {
            Effect.NONE -> Unit
            // Both effects darken the last screen pixels of every game pixel,
            // measured in real screen pixels so they look right at any scale.
            Effect.SCANLINES -> append(
                """
    float scaleY = uOutputSize.y / uTextureSize.y;
    float fromBottom = (1.0 - fract(vTexCoord.y * uTextureSize.y)) * scaleY;
    float lineWidth = max(1.0, floor(scaleY / 3.0));
    rgb *= 1.0 - 0.45 * step(fromBottom, lineWidth);
""",
            )
            Effect.LCD -> append(
                """
    vec2 scale = uOutputSize / uTextureSize;
    vec2 fromEnd = (1.0 - fract(vTexCoord * uTextureSize)) * scale;
    vec2 lineWidth = max(vec2(1.0), floor(scale / 5.0));
    vec2 grid = step(fromEnd, lineWidth);
    rgb *= 1.0 - 0.3 * max(grid.x, grid.y);
""",
            )
        }
        if (colorCorrection) append("    rgb = correctColor(rgb);\n")
        append(
            """
    gl_FragColor = vec4(rgb, 1.0);
}
""",
        )
    }
}
