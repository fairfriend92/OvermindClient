package com.example.myapplication;

import android.app.IntentService;
import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;
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

    long startTime = 0, endTime = 0, elapsedTime = 0;

    @Override
    protected void onHandleIntent (Intent workIntent) {

        boolean[] presynapticSpikes = new boolean[Constants.NUMBER_OF_EXC_SYNAPSES + Constants.NUMBER_OF_INH_SYNAPSES];
        Intent broadcastSpikes = new Intent("com.example.BROADCAST_SPIKES");
        broadcastSpikes.putExtra("Presynaptic spikes", presynapticSpikes);
        Random random = new Random();
        int[] waitARP = new int[Constants.NUMBER_OF_EXC_SYNAPSES + Constants.NUMBER_OF_INH_SYNAPSES];

        while (!shutdown) {

            startTime = System.nanoTime();

            /**
             * For testing purposes we randomly generate the spikes instead of reading them from the connected peers
             */
            for (int index = 0; index < Constants.NUMBER_OF_EXC_SYNAPSES + Constants.NUMBER_OF_INH_SYNAPSES; index++) {
                // A new spike is randomly generated only if the wait for the ARP has ended
                if (waitARP[index] == 0) {
                    presynapticSpikes[index] = random.nextBoolean();
                    // Reset the ARP counter if a new spike is emitted
                    if (presynapticSpikes[index]) {
                        waitARP[index] = (int) (Constants.ABSOLUTE_REFRACTORY_PERIOD / Constants.SAMPLING_RATE);
                    }
                } else {
                    presynapticSpikes[index] = false;
                    // We account for the Absolute Refractory Period by decreasing a counter, one for each synapse
                    waitARP[index] = waitARP[index] - 1;
                }
            }

            // Broadcast the received spikes to the Simulation service
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastSpikes);

            endTime = System.nanoTime();
            elapsedTime = endTime - startTime;

            Log.d("DataReceiver", "Elapsed time in nanoseconds: " + Long.toString(elapsedTime));

            // Wait before generating the new batch of presynaptic spikes
            if (elapsedTime / 1000 < 100) {
                try {
                    TimeUnit.MICROSECONDS.sleep((int)(100 - elapsedTime));
                } catch (InterruptedException interruptedException) {
                    Log.e("DataReceiver", "Sleep has been interrupted");
                }
            } else { Log.e("DataReceiver", "Error: could not send presynaptic spikes in time to the Simulation service"); }
        }

        shutdown = false;
        stopSelf();
    }
}