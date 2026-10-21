package com.joseluixrax.gbaemu.ui.game

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.joseluixrax.gbaemu.emulator.FrameBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the latest emulator frame as a textured quad, scaled to the screen
 * with the selected filter. Runs on GLSurfaceView's render thread.
 */
class GameRenderer(private val frameBuffer: FrameBuffer) : GLSurfaceView.Renderer {

    enum class Aspect { KEEP, INTEGER, STRETCH }

    data class Settings(
        val filter: Shaders.Filter = Shaders.Filter.SHARP,
        val effect: Shaders.Effect = Shaders.Effect.NONE,
        val colorCorrection: Boolean = false,
        val aspect: Aspect = Aspect.KEEP,
    )

    @Volatile private var settings = Settings()
    @Volatile private var settingsChanged = true

    /** Pixels reserved at the top in portrait (notch). */
    @Volatile var topInset = 0

    private var program = 0
    private var texture = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var textureSizeHandle = 0
    private var outputSizeHandle = 0
    private var textureWidth = 0
    private var textureHeight = 0
    private var uploadedVersion = -1L
    private var surfaceWidth = 1
    private var surfaceHeight = 1

    private val quad: FloatBuffer = floatBuffer(
        // x, y, u, v. Texture row 0 is the top of the game screen.
        -1f, -1f, 0f, 1f,
        1f, -1f, 1f, 1f,
        -1f, 1f, 0f, 0f,
        1f, 1f, 1f, 0f,
    )

    fun updateSettings(newSettings: Settings) {
        if (newSettings != settings) {
            settings = newSettings
            settingsChanged = true
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new EGL context means all GL objects are gone.
        program = 0
        texture = 0
        textureWidth = 0
        textureHeight = 0
        uploadedVersion = -1L
        settingsChanged = true
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = max(width, 1)
        surfaceHeight = max(height, 1)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (settingsChanged || program == 0) rebuildProgram()

        if (frameBuffer.version != uploadedVersion) {
            frameBuffer.withPixels { buffer, w, h ->
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
                if (w != textureWidth || h != textureHeight) {
                    GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer,
                    )
                    textureWidth = w
                    textureHeight = h
                } else {
                    GLES20.glTexSubImage2D(
                        GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer,
                    )
                }
                uploadedVersion = frameBuffer.version
            }
        }

        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (textureWidth == 0 || program == 0) return

        val (x, y, w, h) = viewport(textureWidth, textureHeight)
        GLES20.glViewport(x, y, w, h)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform2f(textureSizeHandle, textureWidth.toFloat(), textureHeight.toFloat())
        GLES20.glUniform2f(outputSizeHandle, w.toFloat(), h.toFloat())

        quad.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(positionHandle)
        quad.position(2)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Where the game goes on screen, in GL viewport coordinates (origin bottom-left). */
    private fun viewport(gameW: Int, gameH: Int): IntArray {
        val portrait = surfaceHeight > surfaceWidth
        val top = if (portrait) topInset else 0
        val availW = surfaceWidth
        val availH = if (portrait) {
            // Upper part of the screen; the controls own the rest.
            min(surfaceHeight - top, (surfaceWidth * gameH / gameW.toFloat() * 1.0f).toInt())
        } else {
            surfaceHeight
        }

        var w: Int
        var h: Int
        when (settings.aspect) {
            Aspect.STRETCH -> {
                w = availW
                h = availH
            }
            Aspect.INTEGER -> {
                val scale = max(1f, floor(min(availW / gameW.toFloat(), availH / gameH.toFloat())))
                w = (gameW * scale).toInt()
                h = (gameH * scale).toInt()
            }
            Aspect.KEEP -> {
                val scale = min(availW / gameW.toFloat(), availH / gameH.toFloat())
                w = (gameW * scale).toInt()
                h = (gameH * scale).toInt()
            }
        }
        w = min(w, availW)
        h = min(h, availH)
        val x = (surfaceWidth - w) / 2
        // In portrait stick to the top; in landscape center vertically.
        val yFromTop = if (portrait) top + (availH - h) / 2 else (surfaceHeight - h) / 2
        val y = surfaceHeight - yFromTop - h
        return intArrayOf(x, y, w, h)
    }

    private fun rebuildProgram() {
        settingsChanged = false
        val s = settings
        if (texture == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texture = ids[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        val filter = if (s.filter == Shaders.Filter.NEAREST) GLES20.GL_NEAREST else GLES20.GL_LINEAR
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val newProgram = buildProgram(Shaders.VERTEX, Shaders.fragment(s.filter, s.effect, s.colorCorrection))
            ?: buildProgram(Shaders.VERTEX, Shaders.fragment(Shaders.Filter.LINEAR, Shaders.Effect.NONE, false))
            ?: return
        if (program != 0) GLES20.glDeleteProgram(program)
        program = newProgram
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        textureSizeHandle = GLES20.glGetUniformLocation(program, "uTextureSize")
        outputSizeHandle = GLES20.glGetUniformLocation(program, "uOutputSize")
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int? {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource) ?: return null
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragment == null) {
            GLES20.glDeleteShader(vertex)
            return null
        }
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertex)
        GLES20.glAttachShader(id, fragment)
        GLES20.glLinkProgram(id)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Link failed: " + GLES20.glGetProgramInfoLog(id))
            GLES20.glDeleteProgram(id)
            return null
        }
        return id
    }

    private fun compile(type: Int, source: String): Int? {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(id))
            GLES20.glDeleteShader(id)
            return null
        }
        return id
    }

    private companion object {
        const val TAG = "GameRenderer"

        fun floatBuffer(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }
    }
}
