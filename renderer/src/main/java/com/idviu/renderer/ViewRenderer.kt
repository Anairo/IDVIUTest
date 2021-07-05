package com.idviu.renderer

import android.content.Context
import android.opengl.GLSurfaceView

class ViewRenderer(context: Context?) : GLSurfaceView(context) {

    private val renderer: Renderer
    init {

        // Create an OpenGL ES 2.0 context
        setEGLContextClientVersion(2)

        renderer = MovieRenderer()

        // Set the Renderer for drawing on the GLSurfaceView
        setRenderer(renderer)
    }
}