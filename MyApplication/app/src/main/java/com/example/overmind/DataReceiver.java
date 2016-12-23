package com.example.overmind;

import android.app.IntentService;
import android.content.Intent;
import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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

    static boolean shutdown = false;
    static public void shutDown () {
        shutdown = true;
    }

    long lastTime = 0, newTime = 0;

    private static final String SERVER_IP = "192.168.1.213";
    private static final int SERVER_PORT = 4194;

    /**
     * Used to print a byte[] as a hex string
     */
    final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    @Override
    protected void onHandleIntent (Intent workIntent) {
        String stackTrace;
        Socket clientSocket = null;
        DataInputStream input = null;
        byte[] presynapticSpikes = new byte[(Constants.NUMBER_OF_EXC_SYNAPSES + Constants.NUMBER_OF_INH_SYNAPSES) / 8];

        try {
            clientSocket = new Socket(SERVER_IP, SERVER_PORT);
        } catch (IOException e) {
            stackTrace = Log.getStackTraceString(e);
            Log.e("DataReceiver", stackTrace);
        }

        try {
            input = new DataInputStream(clientSocket.getInputStream());
        } catch (IOException e) {
            stackTrace = Log.getStackTraceString(e);
            Log.e("DataReceiver", stackTrace);
        }

        //boolean[] presynapticSpikes = new boolean[Constants.NUMBER_OF_EXC_SYNAPSES + Constants.NUMBER_OF_INH_SYNAPSES];
        /*
        Intent broadcastSpikes = new Intent("com.example.BROADCAST_SPIKES");
        broadcastSpikes.putExtra("Presynaptic spikes", presynapticSpikes);
        */
        //Random random = new Random();
        //int[] waitARP = new int[Constants.NUMBER_OF_EXC_SYNAPSES + Constants.NUMBER_OF_INH_SYNAPSES];

        while (!shutdown) {
            try {
                input.readFully(presynapticSpikes, 0, (Constants.NUMBER_OF_EXC_SYNAPSES + Constants.NUMBER_OF_INH_SYNAPSES) / 8);
            } catch (IOException e) {
                stackTrace = Log.getStackTraceString(e);
                Log.e("DataReceiver", stackTrace);
            }

            Log.d("DataReceiver", this.bytesToHex(presynapticSpikes));
            lastTime = newTime;
            newTime = System.nanoTime();
            Log.d("DataReceiver", "Elapsed time in nanoseconds: " + Long.toString(newTime - lastTime));

            //startTime = System.nanoTime();

            /**
             * For testing purposes we randomly generate the spikes instead of reading them from the connected peers
             */
            /*
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
            */



            // Broadcast the received spikes to the Simulation service
            //LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastSpikes);

            /*
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
            */
        }

        shutdown = false;
        stopSelf();
    }
}