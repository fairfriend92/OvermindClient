package com.example.overmind;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MyGLRenderer implements GLSurfaceView.Renderer {
    public String mVendor;

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // Get various GPU and GL information
        Log.d("GL info", "gl renderer: "+gl.glGetString(GL10.GL_RENDERER));
        Log.d("GL info", "gl vendor: "+gl.glGetString(GL10.GL_VENDOR));
        Log.d("GL info", "gl version: "+gl.glGetString(GL10.GL_VERSION));
        Log.d("GL info", "gl extensions: "+gl.glGetString(GL10.GL_EXTENSIONS));

        // Store relevant info
        mVendor = gl.glGetString(GL10.GL_VENDOR);

        // Set the background frame color
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // Redraw background color
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    }
}