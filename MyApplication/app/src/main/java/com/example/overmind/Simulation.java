package com.example.overmind;

import android.app.IntentService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

/**
 * Created by rodolfo on 07/12/16.
 */

public class Simulation extends IntentService{

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

    static boolean[] presynapticSpikes = new boolean[Constants.NUMBER_OF_EXC_SYNAPSES + Constants.NUMBER_OF_INH_SYNAPSES];


    @Override
    public void onCreate() {
        /**
         * Register an observer (mMessageReceiver) to receive Intents with actions named
         * "com.example.BROADCAST_SPIKES"
         */
        LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver,
                new IntentFilter("com.example.BROADCAST_SPIKES"));
        super.onCreate();
    }

    /**
     * Handler for received Intents: called wheneve an Intent  with action named
     * "com.example.BROADCAST_SPIKES" is broadcast.
     */
    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Get data included in the Intent
            presynapticSpikes = intent.getBooleanArrayExtra("Presynaptic spikes");
        }
    };

    @Override
    public void onDestroy() {
        // Unregister the receiver since the service is about to be closed
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver);
        super.onDestroy();
    }

    @Override
    protected void onHandleIntent (Intent workIntent)
    {
        String synapseKernelVec4 = workIntent.getStringExtra("Kernel");
        // Create the object used to hold the information passed from one native call to the other
        long openCLObject = initializeOpenCL(synapseKernelVec4);
        if (openCLObject == -1) {
            Log.e("Simulation service", "Failed to initialize OpenCL");
            shutdown = true;
        }
        long startTime = 0, endTime = 0, elapsedTime = 0;
        while (!shutdown) {
            //startTime = System.nanoTime();
            openCLObject = simulateNetwork(presynapticSpikes, openCLObject);
            /*endTime = System.nanoTime();
            elapsedTime = endTime - startTime;
            Log.d("Simulation", "Elapsed time in nanoseconds: " + Long.toString(elapsedTime));*/
            if(openCLObject == -1) shutDown();
        }
        if(openCLObject != -1 ) { closeOpenCL(openCLObject); }
        shutdown = false;
        stopSelf();
    }

    /**
     * Java Native Interface
     */
    public native long simulateNetwork(boolean[] presynapticSpikes, long openCLObject);
    public native long initializeOpenCL(String synapseKernel);
    public native int closeOpenCL(long openCLObject);
}



