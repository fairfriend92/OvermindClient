package com.example.overmind;

import android.content.Context;
import android.content.Intent;
import android.opengl.GLSurfaceView;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.util.Scanner;

public class MainActivity extends AppCompatActivity {

    class MyGLSurfaceView extends GLSurfaceView {
        public MyGLRenderer mRenderer;

        public MyGLSurfaceView(Context context){
            super(context);

            // Create an OpenGL ES 2.0 context
            setEGLContextClientVersion(2);

            mRenderer = new MyGLRenderer();

            // Set the Renderer for drawing on the GLSurfaceView
            setRenderer(mRenderer);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //MyGLSurfaceView mGlSurfaceView = new MyGLSurfaceView(this);

        GLSurfaceView mGlSurfaceView = new GLSurfaceView(this);
        MyGLRenderer mGLRenderer = new MyGLRenderer();
        mGlSurfaceView.setRenderer(mGLRenderer);

        setContentView(mGlSurfaceView);

        //while (mGlSurfaceView.mRenderer.mVendor == null) { }
        //setContentView(R.layout.activity_main);

        Log.d("robin", " " +  mGLRenderer.mVendor);
    }

    static {
        try {
            System.load("libGLES_mali.so");
        }
        catch (UnsatisfiedLinkError linkError) {
            Log.e("Unsatisfied link", "libGLES_mali.so not found");
        }
        /*
        try {
            System.load("libOpenCL.so");
        } catch (UnsatisfiedLinkError linkError) {
            Log.e("Unsatisfied link", "libGLES_mali.so not found");
        }
        */
    }

    static {
        System.loadLibrary( "hello-world" );
    }

    public InputStream getInputStream(String kernelName) {
        try {
            return getAssets().open(kernelName);
        } catch (IOException ioException) {
            Log.e("IO exception", "Cannot retrieve OpenCL kernel");
            return null;
        }
    }

    static String loadKernelFromAsset(InputStream inputStream) {
            Scanner scanner = new Scanner(inputStream).useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : " ";
    }

    /**
     * Called when the user clicks the Start simulation button
     */
    static boolean simulationIsStarted = false;
    public void startSimulation(View view) {
        if (!simulationIsStarted) {
            // The SimulationService Intent
            Intent simulationIntent = new Intent(MainActivity.this, SimulationService.class);
            // The string used to hold the .cl kernel file
            String synapseKernelVec4 = loadKernelFromAsset(getInputStream("synapse_vec4.cl"));
            // Put the string holding the kernel in the simulation Intent
            simulationIntent.putExtra("Kernel", synapseKernelVec4);
            // Start the service
            //this.startService(simulationIntent);
            simulationIsStarted = true;
        }
    }

    /**
     * Called when the user clicks the Stop simulation button
     */
    public void stopSimulation(View view) {
       SimulationService.shutDown();
       simulationIsStarted = false;
    }
}



