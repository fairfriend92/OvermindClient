package com.example.overmind;

import android.app.IntentService;
import android.content.Intent;
import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Background service used to initialize the OpenCL implementation and execute the Thread pool managers
 * for data reception, Kernel initialization, Kernel execution and data sending.
 */

public class SimulationService extends IntentService {

    public SimulationService() {
        super("SimulationService");
    }

    static boolean shutdown = false;
    static public void shutDown () {
        shutdown = true;
    }

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
        Socket clientSocket = null;
        DataInputStream input = null;

        BlockingQueue<byte[]> dataReceiverQueue = new ArrayBlockingQueue<>(4);
        ExecutorService dataReceiverExecutor = Executors.newSingleThreadExecutor();
        BlockingQueue<char[]> kernelInitQueue = new ArrayBlockingQueue<>(4);
        ExecutorService kernelInitExecutor = Executors.newSingleThreadExecutor();
        BlockingQueue<byte[]> kernelExcQueue = new ArrayBlockingQueue<>(4);
        ExecutorService kernelExcExecutor = Executors.newSingleThreadExecutor();
        Future<Long> newOpenCLObject;

        /**
         * Get the string holding the kernel and initialize the OpenCL implementation
         */
        String synapseKernelVec4 = workIntent.getStringExtra("Kernel");
        long openCLObject = initializeOpenCL(synapseKernelVec4);

        try {
            clientSocket = new Socket(SERVER_IP, SERVER_PORT);
        } catch (IOException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("SimulationService", stackTrace);
        }

        if (clientSocket != null) {
            try {
                input = new DataInputStream(clientSocket.getInputStream());
            } catch (IOException | NullPointerException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("SimulationService", stackTrace);
            }
        }

        dataReceiverExecutor.execute(new DataReceiver(dataReceiverQueue, input));
        kernelInitExecutor.execute(new KernelInitializer(dataReceiverQueue, kernelInitQueue));
        newOpenCLObject = kernelExcExecutor.submit(new KernelExecutor(kernelInitQueue, kernelExcQueue, openCLObject));

        while (!shutdown) { boolean a = true; }

        try {
            openCLObject = newOpenCLObject.get();
        } catch (InterruptedException|ExecutionException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("SimulationService", stackTrace);
        }

        closeOpenCL(openCLObject);
        dataReceiverExecutor.shutdown();
        kernelInitExecutor.shutdown();
        if (clientSocket != null && input != null) {
            try {
                clientSocket.close();
                input.close();
            } catch (IOException | NullPointerException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("SimulationService", stackTrace);
            }
        }
        shutdown = false;
        stopSelf();
    }

    public class DataReceiver implements Runnable {

        private BlockingQueue<byte[]> dataReceiverQueue;
        private DataInputStream input;
        private byte[] presynapticSpikes = new byte[(Constants.NUMBER_OF_EXC_SYNAPSES + Constants.NUMBER_OF_INH_SYNAPSES) / 8];

        public DataReceiver (BlockingQueue<byte[]> b, DataInputStream d) {
            this.dataReceiverQueue = b;
            this.input = d;
        }

        @Override
        public void run () {
            while (!shutdown) {
                try {
                    input.readFully(presynapticSpikes, 0, (Constants.NUMBER_OF_EXC_SYNAPSES + Constants.NUMBER_OF_INH_SYNAPSES) / 8);
                    //Log.d("DataReceiver", "SS " + SimulationService.bytesToHex(presynapticSpikes));
                    dataReceiverQueue.put(presynapticSpikes);
                } catch (IOException | InterruptedException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("DataReceiver", stackTrace);
                }
            }
        }
    }

    public class KernelExecutor implements Callable<Long> {

        private BlockingQueue<char[]> kernelInitQueue;
        private BlockingQueue<byte[]> kernelExcQueue;
        private long openCLObject;
        private char[] synapseInput = new char[(Constants.NUMBER_OF_EXC_SYNAPSES + Constants.NUMBER_OF_INH_SYNAPSES) * Constants.MAX_MULTIPLICATIONS];

        public KernelExecutor(BlockingQueue<char[]> b, BlockingQueue<byte[]> b1, long l) {
            this.kernelInitQueue = b;
            this.kernelExcQueue = b1;
            this.openCLObject = l;
        }

        @Override
        public Long call () {
            while (!shutdown) {
                try {
                    synapseInput = kernelInitQueue.take();
                } catch (InterruptedException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("SimulationService", stackTrace);
                }
                openCLObject = simulateDynamics(synapseInput, openCLObject);
            }
            return openCLObject;
        }
    }

    public native long initializeOpenCL(String synapseKernel);
    public native long simulateDynamics(char[] synapseInput, long openCLObject);
    public native int closeOpenCL(long openCLObject);
}