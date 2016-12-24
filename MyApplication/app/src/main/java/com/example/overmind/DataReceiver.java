package com.example.overmind;

import android.app.IntentService;
import android.content.Intent;
import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;

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
        //byte[] oldPresynapticSpikes;

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

        while (!shutdown) {
            //oldPresynapticSpikes = presynapticSpikes.clone();
            try {
                input.readFully(presynapticSpikes, 0, (Constants.NUMBER_OF_EXC_SYNAPSES + Constants.NUMBER_OF_INH_SYNAPSES) / 8);
            } catch (IOException e) {
                stackTrace = Log.getStackTraceString(e);
                Log.e("DataReceiver", stackTrace);
            }

            /**
             * Debugging code
             */
            /*
            if (Arrays.equals(oldPresynapticSpikes, presynapticSpikes)) {
                Log.d("DataReceiver", "New data is not different");
            } else {
                Log.d("DataReceiver", "New data is different");
            }
            Log.d("DataReceiver", bytesToHex(oldPresynapticSpikes));
            Log.d("DataReceiver", bytesToHex(presynapticSpikes));
            lastTime = newTime;
            newTime = System.nanoTime();
            Log.d("DataReceiver", "Elapsed time in nanoseconds: " + Long.toString(newTime - lastTime));
            */

            // Broadcast the received spikes to the Simulation service
            //LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastSpikes);
        }

        shutdown = false;
        stopSelf();
    }
}