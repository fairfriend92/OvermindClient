package com.example.myapplication;

import android.app.IntentService;
import android.content.Intent;
import android.util.Log;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Created by rodolfo on 05/12/16.
 */

public class DataReceiver extends IntentService {

    /**
     * An IntentService must always have a constructor that calls the super constructor. The
     * string supplied to the super constructor is used to give a name to the IntentService's
     * background thread.
     */
    public DataReceiver() {
        super("DataReceiver");
    }

    /**
     * Method called by MainActivity on button press to shut down the service
     */
    static boolean shutdown = false;
    static public void shutDown () {
        shutdown = true;
    }

    @Override
    protected void onHandleIntent (Intent workIntent) {
        boolean[] presynapticSpikes = new boolean[4];

        /**
         * For testing purposes we randomly generate the spikes instead of reading them from the connected peers
         */
        Random random = new Random();
        while (!shutdown) {
            for (int index = 0; index < 4; index++) {
                //presynapticSpikes[index] = random.nextBoolean();
                presynapticSpikes[index] = true;
            }

            // Broadcast the received spikes to the Simulation service
            Intent broadcastSpikes = new Intent("com.example.BROADCAST_SPIKES");
            broadcastSpikes.putExtra("Presynaptic spikes", presynapticSpikes);
            sendBroadcast(broadcastSpikes);

            // Wait before generating the new batch of presynaptic spikes
            try {
                TimeUnit.MICROSECONDS.sleep(100);
                Log.d("DataReceiver", "Package received");
            } catch (InterruptedException interruptedException) {
                Log.e("DataReceiver", "Sleep has been interrupted");
            }
        }

        shutdown = false;
        stopSelf();
    }
}