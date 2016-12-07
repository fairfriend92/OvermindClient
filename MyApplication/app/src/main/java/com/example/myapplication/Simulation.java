package com.example.myapplication;

import android.app.IntentService;
import android.content.Intent;

/**
 * Created by rodolfo on 07/12/16.
 */

public class Simulation extends IntentService{

    static {
        System.loadLibrary( "hello-world" );
    }

    /**
     * An IntentService must always have a constructor that calls the super constructor. The
     * string supplied to the super constructor is used to give a name to the IntentService's
     * background thread.
     */
    public Simulation() {
        super("Simulation");
    }

    /**
     * Method called by MainActivity on button press to shut down the service
     */
    static boolean shutdown = false;
    static public void shutDown () {
        shutdown = true;
    }

    @Override
    protected void onHandleIntent (Intent workIntent)
    {
        String macKernelVec4 = workIntent.getStringExtra("Kernel");
        boolean[] presynapticSpikes = workIntent.getBooleanArrayExtra("Spikes");
        // Call the native method
        boolean test = helloWorld(presynapticSpikes, macKernelVec4);
        if (shutdown) {
            shutdown = false;
            stopSelf();
        }
    }

    /**
     * Java Native Interface
     */
    public native boolean helloWorld(boolean[] presynapticSpikeTrains, String macKernel);
}



