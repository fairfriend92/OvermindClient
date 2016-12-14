package com.example.myapplication;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.util.Scanner;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    /**
     * Load the needed libraries. OpenCL lib location depends on the GPU vendor
     */
    static {
        try {
            System.load("libGLES_mali.so");
        }
        catch (UnsatisfiedLinkError linkError) {
            Log.e("Unsatisfied link", "libGLES_mali.so not found");
        }
    }


    static {
        System.loadLibrary( "hello-world" );
    }

    /**
     * Load the OpenCL kernel into a string so that it can be passed to the native method
     */
    // Get the asset in the input stream
    public InputStream getInputStream(String kernelName) {
        try {
            return getAssets().open(kernelName);
        } catch (IOException ioException) {
            Log.e("IO exception", "Cannot retrieve OpenCL kernel");
            return null;
        }
    }
    // Translate the input stream into a string
    static String loadKernelFromAsset(InputStream inputStream) {
            Scanner scanner = new Scanner(inputStream).useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : " ";
    }

    /**
     * Called when the user clicks the Start simulation button
     */
    static boolean simulationIsStarted = false;
    public void startSimulation(View view) {
       /**
       * Setup the service to receive data from the connected peers.
       */
        if (!simulationIsStarted) {
            this.startService(new Intent(MainActivity.this, DataReceiver.class));

            /**
             * Setup the service which makes the call to the native C method
             */
            Intent simultationIntent = new Intent(MainActivity.this, Simulation.class);
            // Get the content of the .cl file into a string to be passed to the native method
            String macKernelVec4 = loadKernelFromAsset(getInputStream("mac_kernel_vec4.cl"));
            simultationIntent.putExtra("Kernel", macKernelVec4);
            this.startService(simultationIntent);

            simulationIsStarted = true;
        }
    }

    /**
     * Called when the user clicks the Stop simulation button
     */
    public void stopSimulation(View view) {
       DataReceiver.shutDown();
       Simulation.shutDown();
       simulationIsStarted = false;
    }
}



