package com.example.overmind;

import android.app.IntentService;
import android.content.Intent;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
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

        // Socket and streams used for TCP communications with the OverMind Server
        Socket clientSocket = MainActivity.thisClient;
        ObjectInputStream input = null;
        DataOutputStream output = null;

        /**
         * Get the string holding the kernel and initialize the OpenCL implementation
         */
        String synapseKernelVec4 = workIntent.getStringExtra("Kernel");
        long openCLObject = initializeOpenCL(synapseKernelVec4);

        // Object used to hold all the relevant info pertaining this device
        LocalNetwork thisDevice = null;

        /**
         * Retrieve info regarding the other devices connected to this one
         */

        try {

            // Open input stream through TCP socket
            input = new ObjectInputStream(clientSocket.getInputStream());

            // Save the object from the stream into the local variable thisDevice
            thisDevice = (LocalNetwork) input.readObject();

            // The local number of neurons never changes, so it can be made constant
            Constants.NUMBER_OF_NEURONS = thisDevice.numOfNeurons;

        } catch (IOException | NullPointerException | ClassNotFoundException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("SimulationService", stackTrace);
        }

        assert input != null;
        assert thisDevice != null;

        /**
         * Queues and Thread executors used to parallelize the computation
         */

        /* [KernelInitializer] */
        BlockingQueue<char[]> kernelInitQueue = new ArrayBlockingQueue<>(4);
        ExecutorService kernelInitExecutor = Executors.newCachedThreadPool();
        /* [KernelInitializer] */

        /* [KernelExecutor] */
        BlockingQueue<byte[]> kernelExcQueue = new ArrayBlockingQueue<>(4);
        ExecutorService kernelExcExecutor = Executors.newSingleThreadExecutor();
        // Future object which holds the pointer to the OpenCL structure defined in native_method.h
        Future<Long> newOpenCLObject;
        /* [KernelExecutor] */

        /* [DataSender] */
        ExecutorService dataSenderExecutor = Executors.newSingleThreadExecutor();
        /* [DataSender] */

        /*
        try {
            thisDevice = (LocalNetwork) input.readObject();
        } catch (IOException | ClassNotFoundException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("SimulationService", stackTrace);
        }
        */

        /**
         * Send the connected device an empty vector in order to receive their responses thus triggering
         * the initialization of the OpenCL kernel
         */

        try {
            byte[] emptyInput = new byte[Constants.DATA_BYTES];
            for (short index = 0; index < Constants.DATA_BYTES; index++) {
                emptyInput[index] = 0;
            }
            kernelExcQueue.put(emptyInput);
        } catch (InterruptedException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("SimulationService", stackTrace);
        }

        /**
         * Launch the Threads executors for the KernelExecutor and DataSender runnables
         */

        newOpenCLObject = kernelExcExecutor.submit(new KernelExecutor(kernelInitQueue, kernelExcQueue, thisDevice, openCLObject));
        dataSenderExecutor.execute(new DataSender(kernelExcQueue, thisDevice));


        /**
         * Launch thre Threads executor for the KernelInitializer runnable
         */

        // UDP socket which receives data from the presynaptic devices
        DatagramSocket receiverSocket = null;

        try {
            receiverSocket = new DatagramSocket(4194);
        } catch (SocketException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("SimulationService", stackTrace);
        }

        // Temporary array of maximum length to hold the incoming data, independently of the presynaptic network size
        byte[] tmpSpikes = new byte[256];
        DatagramPacket incomingPacket = new DatagramPacket(tmpSpikes, 256);
        String presynapticDeviceIP;

        assert receiverSocket != null;

        while (!shutdown) {

            /**
             * For each of the presynaptic device launch a different thread that runs KernelInitializer
             */

            for (short index = 0; index < thisDevice.presynapticNodes.size(); index++) {

                try {
                    receiverSocket.receive(incomingPacket);
                    tmpSpikes = incomingPacket.getData();
                } catch (IOException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("SimulationService", stackTrace);
                }

                presynapticDeviceIP = incomingPacket.getAddress().toString();
                kernelInitExecutor.execute(new KernelInitializer(kernelInitQueue, presynapticDeviceIP, thisDevice, tmpSpikes));

            }

        }

        // Retrieve from the Future object the last updated openCLObject
        try {
            openCLObject = newOpenCLObject.get();
        } catch (InterruptedException|ExecutionException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("SimulationService", stackTrace);
        }

        /**
         * Shut down the Threads and the Sockets
         */
        closeOpenCL(openCLObject);
        kernelInitExecutor.shutdown();
        dataSenderExecutor.shutdownNow();
        try {
            clientSocket.close();
            input.close();
        } catch (IOException | NullPointerException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("SimulationService", stackTrace);
        }
        shutdown = false;
        stopSelf();
    }

    public class KernelExecutor implements Callable<Long> {

        private BlockingQueue<char[]> kernelInitQueue;
        private BlockingQueue<byte[]> kernelExcQueue;
        private LocalNetwork thisDevice;
        private long openCLObject;
        private byte[] outputSpikes;
        private char[] synapseInput = new char[Constants.MAX_NUM_SYNAPSES * Constants.MAX_MULTIPLICATIONS];

        KernelExecutor(BlockingQueue<char[]> b, BlockingQueue<byte[]> b1, LocalNetwork l, long l1) {
            this.kernelInitQueue = b;
            this.kernelExcQueue = b1;
            this.thisDevice = l;
            this.openCLObject = l1;
        }

        @Override
        public Long call () {

            while (!shutdown) {

                try {
                    synapseInput = kernelInitQueue.take();
                } catch (InterruptedException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("KernelExecutor", stackTrace);
                }

                outputSpikes = simulateDynamics(synapseInput, openCLObject);

                try {
                    kernelExcQueue.put(outputSpikes);
                } catch (InterruptedException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("KernelExecutor", stackTrace);
                }

            }

            return openCLObject;
        }

    }

    public class DataSender implements Runnable {

        private BlockingQueue<byte[]> kernelExcQueue;
        private byte[] outputSpikes = new byte[Constants.DATA_BYTES];
        private LocalNetwork thisDevice;

        DataSender(BlockingQueue<byte[]> b, LocalNetwork l) {

            this.kernelExcQueue = b;
            this.thisDevice = l;

        }

        @Override
        public void run () {

            DatagramSocket senderSocket = null;

            try {
                senderSocket = new DatagramSocket(4194);
            } catch (SocketException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("DataSender", stackTrace);
            }

            assert senderSocket != null;

            DatagramPacket outboundPacket;
            LocalNetwork postynapticDevice;
            InetAddress postynapticDeviceIP = null;

            while (!shutdown) {

                try {
                    outputSpikes = kernelExcQueue.take();
                } catch (InterruptedException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("DataSender", stackTrace);
                }

                for (short index = 0; index < thisDevice.postsynapticNodes.size(); index++) {

                    postynapticDevice = thisDevice.postsynapticNodes.get(index);

                    try {
                        postynapticDeviceIP = InetAddress.getByName(postynapticDevice.ip);
                    } catch (UnknownHostException e) {
                        String stackTrace = Log.getStackTraceString(e);
                        Log.e("DataSender", stackTrace);
                    }

                    assert postynapticDeviceIP != null;
                    outboundPacket = new DatagramPacket(outputSpikes, Constants.DATA_BYTES, postynapticDeviceIP, 4194);

                    try {
                        senderSocket.send(outboundPacket);
                    } catch (IOException e) {
                        String stackTrace = Log.getStackTraceString(e);
                        Log.e("DataSender", stackTrace);
                    }

                }
                /* [End of the for loop] */

            }
            /* [End of the while loop] */

        }
        /* [End of the run loop] */

    }
    /* [End of the DataSender class] */

    public native long initializeOpenCL(String synapseKernel);
    public native byte[] simulateDynamics(char[] synapseInput, long openCLObject);
    public native void closeOpenCL(long openCLObject);
}