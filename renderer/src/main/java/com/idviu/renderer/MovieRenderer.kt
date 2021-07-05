package com.idviu.renderer

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.opengles.GL10

internal inline fun <T> glRun(message: String = "", block: (() -> T)): T {
    return block().also {
        var error: Int = GLES20.glGetError()
        while (error != GLES20.GL_NO_ERROR) {
            error = GLES20.glGetError()
            Log.d("MOVIE_GL_ERROR", "$message: $error")
            throw RuntimeException("GL Error: $message")
        }
    }
}

class MovieRenderer: GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private var program = 0
    private var textureId = 0

    // Handles
    private var mvpMatrixHandle = 0
    private var stMatrixHandle = 0
    private var positionHandle = 0
    private var textureHandle = 0

    // Surface Texture
    private var updateSurface = false
    private lateinit var surfaceTexture: SurfaceTexture

    // Matrices
    private val viewMatrix = FloatArray(16)
    private val rotationMatrix = FloatArray(16)

    private var mvpMatrix = FloatArray(16)
    private var stMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    // float buffer
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(VERTICES_DATA.size * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer().also {
                it.put(VERTICES_DATA).position(0)
            }

    // initialize byte buffer for the draw list
    private val drawListBuffer: ShortBuffer =
            // (# of coordinate values * 2 bytes per short)
            ByteBuffer.allocateDirect(indices.size * 2).run {
                order(ByteOrder.nativeOrder())
                asShortBuffer().apply {
                    put(indices)
                    position(0)
                }
            }

    var mediaPlayer: MediaPlayer? = null

    @Synchronized
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        updateSurface = true
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(this) {
            if (updateSurface) {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(stMatrix)
                updateSurface = false
            }
        }


        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glCullFace(GLES20.GL_FRONT)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        glRun("glUseProgram: $program") {
            GLES20.glUseProgram(program)
        }

        vertices.position(VERTICES_POS_OFFSET);

        glRun("glVertexAttribPointer: Stride bytes") {
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false,
                    VERTICES_STRIDE_BYTES, vertices)
        }

        glRun("glEnableVertexAttribArray") {
            GLES20.glEnableVertexAttribArray(positionHandle)
        }

        vertices.position(VERTICES_UV_OFFSET)

        glRun("glVertexAttribPointer: texture handle") {
            GLES20.glVertexAttribPointer(textureHandle, 3, GLES20.GL_FLOAT, false,
                    VERTICES_STRIDE_BYTES, vertices)
        }

        glRun("glEnableVertexAttribArray") {
            GLES20.glEnableVertexAttribArray(textureHandle)
        }



        glRun("glUniformMatrix4fv: mvpMatrix") {
            val scratch = FloatArray(16)

            // Create a rotation transformation for the triangle
            val time = SystemClock.uptimeMillis() % 4000L
            val angle = 0.090f * time.toInt()
            Matrix.setRotateM(rotationMatrix, 0, angle, 0f, 0.5f, -1.0f)

            // Combine the rotation matrix with the projection and camera view
            // Note that the vPMatrix factor *must be first* in order
            // for the matrix multiplication product to be correct.
            Matrix.multiplyMM(scratch, 0, mvpMatrix, 0, rotationMatrix, 0)
            // Set the camera position (View matrix)
            Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, -3f, 0f, 0f, 0f, 0f, 1.0f, 0.0f)
            // Calculate the projection and view transformation
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, scratch, 0)
        }

        glRun("glUniformMatrix4fv: stMatrix") {
            GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)
        }

        glRun("glDrawArrays: GL_TRIANGLE_STRIP") {
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, drawListBuffer)
        }

        GLES20.glFinish()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio: Float = width.toFloat() / height.toFloat()

        // this projection matrix is applied to object coordinates
        // in the onDrawFrame() method
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 1f, 100f)
    }

    override fun onSurfaceCreated(gl: GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        program = createProgram()
        positionHandle = "aPosition".attr()
        textureHandle = "aTextureCoord".attr()
        mvpMatrixHandle = "uMVPMatrix".uniform()
        stMatrixHandle = "uSTMatrix".uniform()
        createTexture()
    }

    private fun createTexture() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures.first()
        glRun("glBindTexture textureId") { GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId) }

        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener(this)

        val surface = Surface(surfaceTexture)
        mediaPlayer = MediaPlayer()
        mediaPlayer?.setSurface(surface)
        mediaPlayer?.setDataSource("https://bitmovin-a.akamaihd.net/content/MI201109210084_1/m3u8s/f08e80da-bf1d-4e3d-8899-f0f6155f6efa.m3u8")
        mediaPlayer?.isLooping = true
        surface.release()

        try {
            mediaPlayer?.prepare()
        } catch (error: IOException) {
            Log.e("MovieRenderer", "media player prepare failed");
            throw error
        }

        synchronized(this) {
            updateSurface = false
        }

        mediaPlayer?.start()
    }

    private fun String.attr(): Int {
        return glRun("Get attribute location: $this") {
            GLES20.glGetAttribLocation(program, this).also {
                if (it == -1) fail("Error Attribute: $this not found!")
            }
        }
    }

    private fun String.uniform(): Int {
        return glRun("Get uniform location: $this") {
            GLES20.glGetUniformLocation(program, this).also {
                if (it == -1) fail("Error Uniform: $this not found!")
            }
        }
    }

    companion object {
        private const val GL_TEXTURE_EXTERNAL_OES = 0x8D65

        private const val FLOAT_SIZE_BYTES = 4
        private const val VERTICES_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
        private const val VERTICES_POS_OFFSET = 0
        private const val VERTICES_UV_OFFSET = 3

        private val VERTICES_DATA_RAW = floatArrayOf(
                1.0f, -1.0f, 1.0f, 1.0f, 0.0f, // 0
                1.0f, 1.0f, 1.0f, 1.0f, 1.0f, // 1
                -1.0f, 1.0f, 1.0f, 0.0f, 1.0f, // 2
                -1.0f, -1.0f, 1.0f, 0.0f, 0.0f, // 3

                // Back
                -1.0f, -1.0f, -1.0f, 1.0f, 0.0f, // 4
                -1.0f, 1.0f, -1.0f, 1.0f, 1.0f, // 5
                1.0f, 1.0f, -1.0f, 0.0f, 1.0f, // 6
                1.0f, -1.0f, -1.0f, 0.0f, 0.0f, // 7

                // Left
                -1.0f, -1.0f, 1.0f, 1.0f, 0.0f, // 8
                -1.0f, 1.0f, 1.0f, 1.0f, 1.0f, // 9
                -1.0f, 1.0f, -1.0f, 0.0f, 1.0f, // 10
                -1.0f, -1.0f, -1.0f, 0.0f, 0.0f, // 11

                // Right
                1.0f, -1.0f, -1.0f, 1.0f, 0.0f, // 12
                1.0f, 1.0f, -1.0f, 1.0f, 1.0f, // 13
                1.0f, 1.0f, 1.0f, 0.0f, 1.0f, // 14
                1.0f, -1.0f, 1.0f, 0.0f, 0.0f, // 15

                // Top
                1.0f, 1.0f, 1.0f, 1.0f, 0.0f, // 16
                1.0f, 1.0f, -1.0f, 1.0f, 1.0f, // 17
                -1.0f, 1.0f, -1.0f, 0.0f, 1.0f, // 18
                -1.0f, 1.0f, 1.0f, 0.0f, 0.0f, // 19

                // Bottom
                1.0f, -1.0f, -1.0f, 1.0f, 0.0f, // 20
                1.0f, -1.0f, 1.0f, 1.0f, 1.0f, // 21
                -1.0f, -1.0f, 1.0f, 0.0f, 1.0f, // 22
                -1.0f, -1.0f, -1.0f, 0.0f, 0.0f // 23
        )

        private val indices = shortArrayOf(
            // Front
            0, 1, 2,
            2, 3, 0,
            // Back
            4, 5, 6,
            6, 7, 4,
            // Left
            8, 9, 10,
            10, 11, 8,
            // Right
            12, 13, 14,
            14, 15, 12,
            // Top
            16, 17, 18,
            18, 19, 16,
            // Bottom
            20, 21, 22,
            22, 23, 20
        )

        private val VERTICES_DATA = VERTICES_DATA_RAW.map { it * 0.8f }.toFloatArray()

        /*private val VERTICES_DATA = floatArrayOf(
                -1.0f, -1.0f, 0f, 0.0f, 0.0f,
                1.0f, -1.0f, 0f, 1.0f, 0.0f,
                -1.0f, 1.0f, 0f, 0.0f, 1.0f,
                1.0f, 1.0f, 0f, 1.0f, 1.0f
        )*/

        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
              gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """

        private fun createShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            if (shader == 0) throw RuntimeException("Cannot create shader $type\n$source")
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)

            val args = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, args, 0)
            if (args.first() == 0) {
                Log.e("MOVIE_SHADER", "Failed to compile shader source")
                Log.e("MOVIE_SHADER", GLES20.glGetShaderInfoLog(shader))
                GLES20.glDeleteShader(shader)
                throw RuntimeException("Could not compile shader $source\n$type")
            }

            return shader
        }

        private fun createProgram(vertexShaderSource: String = VERTEX_SHADER,
                                  fragmentShaderSource: String = FRAGMENT_SHADER): Int {

            val vertexShader = createShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource)
            val fragmentShader = createShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource)

            val program = GLES20.glCreateProgram()
            if (program == 0) throw RuntimeException("Cannot create program")

            glRun("Attach vertex shader to program") {
                GLES20.glAttachShader(program, vertexShader)
            }

            glRun("Attach fragment shader to program") {
                GLES20.glAttachShader(program, fragmentShader)
            }

            GLES20.glLinkProgram(program)
            val args = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, args, 0)

            if (args.first() != GLES20.GL_TRUE) {
                val info = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                throw RuntimeException("Cannot link program $program, Info: $info")
            }

            return program
        }


        private fun fail(message: String): Nothing {
            throw RuntimeException(message)
        }

    }
}