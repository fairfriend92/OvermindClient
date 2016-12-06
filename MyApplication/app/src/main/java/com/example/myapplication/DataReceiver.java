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

    @Override
    protected void onHandleIntent (Intent workIntent) {
        boolean[] presynapticSpikes = workIntent.getBooleanArrayExtra("Spikes");
        /**
         * For testing purposes we randomly generate the spikes instead of reading them from the connected peers
         */
        Random random = new Random();
        while (true) {
            for (int index = 0; index < 4; index++) {
                presynapticSpikes[index] = random.nextBoolean();
            }
            // Wait before generating the new batch of presynaptic spikes
            try {
                TimeUnit.MICROSECONDS.sleep(100);
            } catch (InterruptedException e) {
                Log.e("DataReceiver", "Sleep has been interrupted");
            }
        }
    }
}