package com.example.myapplication;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    static boolean openclLibraryFound = true;
    static {
        System.loadLibrary( "hello-world" );
        try {
            System.load("/system/lib64/egl/libGLES_mali.so");
        }
        catch (UnsatisfiedLinkError linkError) {
            Log.e("Unsatsfied link", "libGLES_mali.so not found");
            openclLibraryFound = false;
        }
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
   public void startSimulation(View view) {

       /**
        * Setup the service to receive data from the connected peers.
        */
       // Allocate memory to hold the presynaptic spikes
       boolean[] presynapticSpikes = new boolean[4];
       // Create the intent to start the service which reads the incoming spikes and write them in presynapticSpikes
       Intent serviceIntent = new Intent(this, DataReceiver.class);
       serviceIntent.putExtra("Spikes", presynapticSpikes);
       // Start the service
       this.startService(serviceIntent);

       /**
        * Native method call
        */
       // Get the content of the .cl file into a string to be passed to the native method
       String macKernelVec4 = loadKernelFromAsset(getInputStream("mac_kernel_vec4.cl"));
       // Call the native method
       boolean test = helloWorld(presynapticSpikes, macKernelVec4);
    }

    /**
     * Java Native Interface
     */
    public native boolean helloWorld(boolean[] presynapticSpikeTrains, String macKernel);
}



