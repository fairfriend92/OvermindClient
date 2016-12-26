package com.example.overmind;

import android.app.IntentService;
import android.content.Intent;
import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by rodolfo on 05/12/16.
 */

public class SimulationService extends IntentService {

    public SimulationService() {
        super("SimulationService");
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
        char[] synapseInput = new char[(Constants.NUMBER_OF_EXC_SYNAPSES + Constants.NUMBER_OF_INH_SYNAPSES) * Constants.MAX_MULTIPLICATIONS];

        BlockingQueue initKernelQueue = new ArrayBlockingQueue(4);
        ThreadPoolExecutor initKernelExecutor = new ThreadPoolExecutor(1, 1, 10, TimeUnit.MILLISECONDS, initKernelQueue);
        Future<char[]> futureSynapseInput;

        /**
         * Get the string holding the kernel and initialize the OpenCL implementation
         */
        String synapseKernelVec4 = workIntent.getStringExtra("Kernel");
        long openCLObject = initializeOpenCL(synapseKernelVec4);

        try {
            clientSocket = new Socket(SERVER_IP, SERVER_PORT);
        } catch (IOException e) {
            stackTrace = Log.getStackTraceString(e);
            Log.e("SimulationService", stackTrace);
        }

        try {
            input = new DataInputStream(clientSocket.getInputStream());
        } catch (IOException e) {
            stackTrace = Log.getStackTraceString(e);
            Log.e("SimulationService", stackTrace);
        }

        while (!shutdown) {
            //oldPresynapticSpikes = presynapticSpikes.clone();

            try {
                input.readFully(presynapticSpikes, 0, (Constants.NUMBER_OF_EXC_SYNAPSES + Constants.NUMBER_OF_INH_SYNAPSES) / 8);
            } catch (IOException e) {
                stackTrace = Log.getStackTraceString(e);
                Log.e("SimulationService", stackTrace);
            }

            try {
                futureSynapseInput = initKernelExecutor.submit(new InitKernelWorkerThread(presynapticSpikes, synapseInput));
                synapseInput = futureSynapseInput.get();
            } catch (RejectedExecutionException|InterruptedException|ExecutionException e) {
                stackTrace = Log.getStackTraceString(e);
                Log.e("SimulationService", stackTrace);
            }

            lastTime = newTime;
            newTime = System.nanoTime();
            Log.d("SimulationService", "Elapsed time in nanoseconds: " + Long.toString(newTime - lastTime));

            /**
             * Debugging code
             */
            /*
            if (Arrays.equals(oldPresynapticSpikes, presynapticSpikes)) {
                Log.d("SimulationService", "New data is not different");
            } else {
                Log.d("SimulationService", "New data is different");
            }
            Log.d("SimulationService", bytesToHex(oldPresynapticSpikes));
            Log.d("SimulationService", bytesToHex(presynapticSpikes));
            */

        }

        closeOpenCL(openCLObject);
        shutdown = false;
        stopSelf();
    }

    public native long initializeOpenCL(String synapseKernel);
    public native int closeOpenCL(long openCLObject);
}