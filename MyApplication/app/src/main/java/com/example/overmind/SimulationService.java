package com.example.overmind;

import android.app.FragmentManager;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.example.overmind.Constants.NUMBER_OF_NEURONS;

/**
 * Background service used to initialize the OpenCL implementation and execute the Thread pool managers
 * for data reception, Kernel initialization, Kernel execution and data sending.
 */

public class SimulationService extends IntentService {

    // TODO TCP and UDP socket can live on the same port
    private static final int SERVER_PORT_UDP = 4196;
    private static final int IPTOS_THROUGHPUT = 0x08;
    private boolean errorRaised = false;

    public SimulationService() {
        super("SimulationService");
    }

    static boolean shutdown = false;
    static public void shutDown () {

        shutdown = true;

    }

    // Object used to hold all the relevant info pertaining this device
    private LocalNetwork thisDevice = new LocalNetwork();

    @Override
    protected void onHandleIntent (Intent workIntent) {

        // Socket and stream used for TCP communications with the Overmind server
        Socket clientSocket = MainActivity.thisClient;
        ObjectInputStream input = null;

        /**
         * Build the datagram socket used for sending and receiving spikes
         */

        DatagramSocket datagramSocket = null;

        try {
            datagramSocket = new DatagramSocket();
            datagramSocket.setTrafficClass(IPTOS_THROUGHPUT);
            datagramSocket.setSoTimeout(5000);
        } catch (SocketException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("DataSender", stackTrace);
        }

        assert datagramSocket != null;

        /**
         * Send a test packet to the server to initiate UDP hole punching
         */

        try {

            InetAddress serverAddr = InetAddress.getByName(MainActivity.serverIP);

            byte[] testData = new byte[1];

            DatagramPacket testPacket = new DatagramPacket(testData, 1, serverAddr, SERVER_PORT_UDP);

            datagramSocket.send(testPacket);

        } catch (IOException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("SimulationService", stackTrace);
        }

        /**
         * Retrieve info regarding the connected devices.
         */

        try {

            // Open input stream through TCP socket
            input = new ObjectInputStream(clientSocket.getInputStream());

            Log.d("Shutdown test", "New input stream read");

            // Save the object from the stream into the local variable thisDevice
            thisDevice.update((LocalNetwork) input.readObject());

            // The local number of neurons never changes, so it can be made constant
            NUMBER_OF_NEURONS = thisDevice.numOfNeurons;

        } catch (IOException | NullPointerException | ClassNotFoundException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("SimulationService", stackTrace);
            shutDown();
            if (!errorRaised) {
                Intent broadcastError = new Intent("ErrorMessage");
                broadcastError.putExtra("ErrorNumber", 1);
                LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastError);
                errorRaised = true;
            }
        }

        Log.e("barrier", "1");

        assert input != null;
        assert thisDevice != null;

        Log.e("barrier", "2");

        /**
         * Get the string holding the kernel and initialize the OpenCL implementation.
         */
        String kernel = workIntent.getStringExtra("Kernel");
        long openCLObject = initializeOpenCL(kernel, NUMBER_OF_NEURONS);

        /**
         * Queues and Thread executors used to parallelize the computation.
         */

        Log.e("barrier", "3");

        BlockingQueue<char[]> kernelInitQueue = new ArrayBlockingQueue<>(4);
        ExecutorService kernelInitExecutor = Executors.newCachedThreadPool();

        Log.e("barrier", "4");

        BlockingQueue<byte[]> kernelExcQueue = new ArrayBlockingQueue<>(4);
        ExecutorService kernelExcExecutor = Executors.newSingleThreadExecutor();
        // Future object which holds the pointer to the OpenCL structure defined in native_method.h
        Future<Long> newOpenCLObject;

        Log.e("barrier", "5");

        ExecutorService dataSenderExecutor = Executors.newSingleThreadExecutor();

        Log.e("barrier", "6");

        ExecutorService localNetworkUpdaterExecutor = Executors.newSingleThreadExecutor();
        BlockingQueue<LocalNetwork> updatedLocalNetwork = new ArrayBlockingQueue<>(1);

        Log.e("barrier", "7");

        newOpenCLObject = kernelExcExecutor.submit(new KernelExecutor(kernelInitQueue, kernelExcQueue, openCLObject, this));
        dataSenderExecutor.execute(new DataSender(kernelExcQueue, datagramSocket));
        localNetworkUpdaterExecutor.execute(new LocalNetworkUpdater(input, updatedLocalNetwork, this));

        Log.e("barrier", "8");

        while (!shutdown) {

            try {
                LocalNetwork tmp = updatedLocalNetwork.poll(100, TimeUnit.MICROSECONDS);
                if (tmp != null) {
                    Log.d("SimulationService", "Number of presynaptic nodes is " + tmp.presynapticNodes.size());
                    thisDevice.update(tmp);
                }
            } catch (InterruptedException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("SimulationService", stackTrace);
            }

            try {

                // TODO Perhaps the size of this buffer should be bigger to accommodate eventual headers
                byte[] inputSpikesBuffer = new byte[128];

                DatagramPacket inputSpikesPacket = new DatagramPacket(inputSpikesBuffer, 128);

                datagramSocket.receive(inputSpikesPacket);

                inputSpikesBuffer = inputSpikesPacket.getData();

                InetAddress presynapticDeviceAddr = inputSpikesPacket.getAddress();

                LocalNetwork presynapticDevice = new LocalNetwork();

                presynapticDevice.ip = presynapticDeviceAddr.toString().substring(1);

                int index = thisDevice.presynapticNodes.indexOf(presynapticDevice);

                if (index != -1) {
                    kernelInitExecutor.execute(new KernelInitializer(kernelInitQueue, index, thisDevice, inputSpikesBuffer));
                } else {
                    Log.e("SimulationService", "Could not find presynaptic device with IP: " + presynapticDevice.ip);
                }

            } catch (SocketTimeoutException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("LocalNetworkUpdater", stackTrace);
                shutDown();
                if (!errorRaised) {
                    Intent broadcastError = new Intent("ErrorMessage");
                    broadcastError.putExtra("ErrorNumber", 2);
                    LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastError);
                    errorRaised = true;
                }
            } catch (IOException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("SimulationService", stackTrace);
            }

        }

        /**
         * Retrieve from the Future object the last updated openCLObject
         */

        Log.e("barrier", "9");


        try {
            openCLObject = newOpenCLObject.get(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException|ExecutionException|TimeoutException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("SimulationService", stackTrace);
        }

        Log.e("barrier", "10");

        /**
         * Shut down the Threads
         */

        localNetworkUpdaterExecutor.shutdownNow();
        kernelInitExecutor.shutdownNow();
        kernelExcExecutor.shutdownNow();
        dataSenderExecutor.shutdownNow();

        boolean localNetworkUpdaterIsShutdown = false;
        boolean kernelInitializerIsShutdown = false;
        boolean kernelExecutorIsShutdown = false;
        boolean dataSenderIsShutdown = false;

        try {
            localNetworkUpdaterIsShutdown = localNetworkUpdaterExecutor.awaitTermination(300, TimeUnit.MILLISECONDS);
            kernelInitializerIsShutdown = kernelInitExecutor.awaitTermination(100, TimeUnit.MILLISECONDS);
            kernelExecutorIsShutdown = kernelExcExecutor.awaitTermination(100, TimeUnit.MILLISECONDS);
            dataSenderIsShutdown = dataSenderExecutor.awaitTermination(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("SimulationService", stackTrace);
        }

        if (!localNetworkUpdaterIsShutdown || !kernelExecutorIsShutdown || !kernelInitializerIsShutdown || dataSenderIsShutdown) {
            Log.e("SimulationService", "network updater is shutdown: " + localNetworkUpdaterIsShutdown +
                    " kernel initializer is shutdown: " + kernelInitializerIsShutdown + " kernel executor is shutdown: " + kernelExcExecutor +
                    " data sender is shutdown: " + dataSenderIsShutdown);
        }

        closeOpenCL(openCLObject);

        Log.e("barrier", "11");

        shutdown = false;
        stopSelf();

    }

    public class LocalNetworkUpdater implements Runnable {

        private ObjectInputStream input;
        private LocalNetwork thisDevice = new LocalNetwork();
        private BlockingQueue<LocalNetwork> updatedLocalNetwork;
        private Context context;

        LocalNetworkUpdater(ObjectInputStream o, BlockingQueue<LocalNetwork> a, Context c) {

            this.input = o;
            this.updatedLocalNetwork = a;
            this.context = c;

        }

        @Override
        public void run(){

            while (!shutdown) {

                try {
                    thisDevice.update((LocalNetwork) input.readObject());
                } catch (IOException | ClassNotFoundException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("LocalNetworkUpdater", stackTrace);
                    shutDown();
                    if (!errorRaised) {
                        Intent broadcastError = new Intent("ErrorMessage");
                        broadcastError.putExtra("ErrorNumber", 3);
                        LocalBroadcastManager.getInstance(context).sendBroadcast(broadcastError);
                        errorRaised = true;
                    }
                    //stopSelf();
                }

                //Log.d("LocalNetworkUpdate", "Number of dendrites is " + thisDevice.numOfDendrites);

                try {
                    updatedLocalNetwork.put(thisDevice);
                } catch (InterruptedException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("LocalNetworkUpdater", stackTrace);
                }

            }

        }

    }

    /**
     * Class which calls the native method which schedules and runs the OpenCL kernel.
     */

    public class KernelExecutor implements Callable<Long> {

        private BlockingQueue<char[]> kernelInitQueue;
        private long openCLObject;
        private char[] synapseInput = new char[Constants.MAX_NUM_SYNAPSES * Constants.MAX_MULTIPLICATIONS];
        private short data_bytes = (NUMBER_OF_NEURONS % 8) == 0 ?
                (short) (NUMBER_OF_NEURONS / 8) : (short)(NUMBER_OF_NEURONS / 8 + 1);
        private byte[] outputSpikes = new byte[data_bytes];
        private BlockingQueue<byte[]> kernelExcQueue;
        private Context context;

        KernelExecutor(BlockingQueue<char[]> b, BlockingQueue<byte[]> b1, long l1, Context c) {
            this.kernelInitQueue = b;
            this.kernelExcQueue = b1;
            this.openCLObject = l1;
            this.context = c;
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

                outputSpikes = simulateDynamics(synapseInput, openCLObject, NUMBER_OF_NEURONS, SimulationParameters.getParameters());

                if (outputSpikes.length == 0) {
                    shutDown();
                    if (!errorRaised) {
                        Intent broadcastError = new Intent("ErrorMessage");
                        broadcastError.putExtra("ErrorNumber", 2);
                        LocalBroadcastManager.getInstance(context).sendBroadcast(broadcastError);
                        errorRaised = true;
                    }
                } else {

                    try {
                        kernelExcQueue.put(outputSpikes);
                    } catch (InterruptedException e) {
                        String stackTrace = Log.getStackTraceString(e);
                        Log.e("KernelExecutor", stackTrace);
                    }

                }

            }

            return openCLObject;

        }

    }

    /**
     * Runnable class which sends the spikes produced by the local network to the postsynaptic devices.
     */

    public class DataSender implements Runnable {

        private BlockingQueue<byte[]> kernelExcQueue;
        private short data_bytes = (NUMBER_OF_NEURONS % 8) == 0 ?
                (short) (NUMBER_OF_NEURONS / 8) : (short)(NUMBER_OF_NEURONS / 8 + 1);
        private byte[] outputSpikes = new byte[data_bytes];
        // TODO Maybe it's better to have different sockets for send and receive but on the same port rather than just one socket
        private DatagramSocket outputSocket;

        DataSender(BlockingQueue<byte[]> b, DatagramSocket d) {

            this.kernelExcQueue = b;
            this.outputSocket = d;
        }

        @Override
        public void run () {

            while (!shutdown) {

                try {
                    outputSpikes = kernelExcQueue.take();
                } catch (InterruptedException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("DataSender", stackTrace);
                }

                // TODO Should thisDevice be static or should we use KernelInitializer method?
                LocalNetwork thisDeviceLocal = thisDevice.get();

                for (short index = 0; index < thisDeviceLocal.postsynapticNodes.size(); index++) {

                    try {

                        LocalNetwork postynapticDevice = thisDeviceLocal.postsynapticNodes.get(index);

                        InetAddress postsynapticDeviceAddr = InetAddress.getByName(postynapticDevice.ip);

                        DatagramPacket outputSpikesPacket = new DatagramPacket(outputSpikes, data_bytes, postsynapticDeviceAddr, postynapticDevice.natPort);

                        outputSocket.send(outputSpikesPacket);

                    } catch (IOException e) {
                        String stackTrace = Log.getStackTraceString(e);
                        Log.e("DataSender", stackTrace);
                    }

                    //Log.d("DataSender", "Spikes have been sent!");

                }
                /* [End of the for loop] */

            }
            /* [End of the while loop] */

            // TODO Close the datagram outputsocket

        }
        /* [End of the run loop] */

    }
    /* [End of the DataSender class] */

    public native long initializeOpenCL(String synapseKernel, short numOfNeurons);
    public native byte[] simulateDynamics(char[] synapseInput, long openCLObject, short numOfNeurons, double[] simulationParameters);
    public native void closeOpenCL(long openCLObject);

}