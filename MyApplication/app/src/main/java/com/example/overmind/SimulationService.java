/**
 * Background service used to initialize the OpenCL implementation and execute the Thread pool managers
 * for data reception, Kernel initialization, Kernel execution and data sending
 */

package com.example.overmind;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.provider.Telephony;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;


import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.example.overmind.Constants.NUMBER_OF_NEURONS;

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

    // Object used to hold all the relevant info pertaining this terminal
    static volatile Terminal thisTerminal = new Terminal();

    @Override
    protected void onHandleIntent (Intent workIntent) {

        // Socket and stream used for TCP communications with the Overmind server
        Socket clientSocket = MainActivity.thisClient.socket;

        /*
        Build the datagram socket used for sending and receiving spikes
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

        /*
        Send a test packet to the server to initiate UDP hole punching
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

        /*
        Open input stream through TCP socket
         */

        try {

            if (MainActivity.thisClient.objectInputStream == null) {
                MainActivity.thisClient.objectInputStream  = new ObjectInputStream(clientSocket.getInputStream());
            }

        } catch (IOException | NullPointerException e) {
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

        /*
        Get the string holding the kernel and initialize the OpenCL implementation.
         */

        String kernel = workIntent.getStringExtra("Kernel");
        long openCLObject = initializeOpenCL(kernel, NUMBER_OF_NEURONS);

        /*
        Queues and Thread executors used to parallelize the computation.
         */

        // TODO Fine tune the queues' capacities, using perhaps SoC info...

        BlockingQueue<Runnable> kernelInitWorkerThreadsQueue = new ArrayBlockingQueue<>(12);
        BlockingQueue<char[]> kernelInitQueue = new ArrayBlockingQueue<>(4);
        ThreadPoolExecutor.AbortPolicy rejectedExecutionHandler = new ThreadPoolExecutor.AbortPolicy();
        ThreadPoolExecutor kernelInitExecutor = new ThreadPoolExecutor(2, 2, 3, TimeUnit.MILLISECONDS, kernelInitWorkerThreadsQueue, rejectedExecutionHandler);

        BlockingQueue<byte[]> kernelExcQueue = new ArrayBlockingQueue<>(16);
        ExecutorService kernelExcExecutor = Executors.newSingleThreadExecutor();
        // Future object which holds the pointer to the OpenCL structure defined in native_method.h
        Future<Long> newOpenCLObject;

        ExecutorService dataSenderExecutor = Executors.newSingleThreadExecutor();

        ExecutorService terminalUpdaterExecutor = Executors.newSingleThreadExecutor();
        BlockingQueue<Terminal> updatedTerminal = new ArrayBlockingQueue<>(1);

        newOpenCLObject = kernelExcExecutor.submit(new KernelExecutor(kernelInitQueue, kernelExcQueue, openCLObject, this));
        dataSenderExecutor.execute(new DataSender(kernelExcQueue, datagramSocket));
        terminalUpdaterExecutor.execute(new TerminalUpdater(updatedTerminal, this));

        List<Future<?>> kernelInitFutures = new ArrayList<Future<?>>();

        /*
        Get the updated info about the connected terminals stored in the Terminal class. Then
        receive the packets from the known connected terminals.
         */

        while (!shutdown) {

            Terminal thisTerminal;
            try {

                thisTerminal = updatedTerminal.poll(100, TimeUnit.MICROSECONDS);

                if (thisTerminal != null) {
                    // TODO create assign method for Terminal ?
                    SimulationService.thisTerminal = thisTerminal;
                    int poolSize = thisTerminal.presynapticTerminals.size() > 0 ? thisTerminal.presynapticTerminals.size() :
                            kernelInitExecutor.getPoolSize();
                    kernelInitExecutor.setCorePoolSize(poolSize);
                    kernelInitExecutor.setMaximumPoolSize(poolSize);

                }

                byte[] inputSpikesBuffer = new byte[128];

                DatagramPacket inputSpikesPacket = new DatagramPacket(inputSpikesBuffer, 128);

                datagramSocket.receive(inputSpikesPacket);

                inputSpikesBuffer = inputSpikesPacket.getData();

                InetAddress presynapticTerminalAddr = inputSpikesPacket.getAddress();

                Iterator iterator = kernelInitFutures.iterator();

                while (iterator.hasNext()) {
                    Future<?> future = (Future<?>) iterator.next();
                    if (future.isDone())
                        iterator.remove();
                }

                if (thisTerminal != null) {
                    for (Future<?> future : kernelInitFutures)
                        future.get();
                    kernelInitFutures = new ArrayList<>();
                }

                // Put the workload in the queue
                Future<?> future = kernelInitExecutor.submit(new KernelInitializer(kernelInitQueue, presynapticTerminalAddr.toString().substring(1), inputSpikesBuffer, thisTerminal));
                kernelInitFutures.add(future);

            } catch (SocketTimeoutException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("TerminalUpdater", stackTrace);
                shutDown();
                if (!errorRaised) {
                    Intent broadcastError = new Intent("ErrorMessage");
                    broadcastError.putExtra("ErrorNumber", 2);
                    LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastError);
                    errorRaised = true;
                }
            } catch (IOException | RejectedExecutionException |
                    InterruptedException | ExecutionException | IllegalArgumentException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("SimulationService", stackTrace);
            }

        }

        /*
        Retrieve from the Future object the last updated openCLObject
         */

        try {
            openCLObject = newOpenCLObject.get(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException|ExecutionException|TimeoutException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("SimulationService", stackTrace);
        }

        /*
        Shut down the Threads
         */

        terminalUpdaterExecutor.shutdownNow();
        kernelInitExecutor.shutdownNow();
        kernelExcExecutor.shutdownNow();
        dataSenderExecutor.shutdownNow();

        boolean terminalUpdatersIsShutdown = false;
        boolean kernelInitializerIsShutdown = false;
        boolean kernelExecutorIsShutdown = false;
        boolean dataSenderIsShutdown = false;

        // Print whether or not the shutdowns were successful
        try {
            terminalUpdatersIsShutdown = terminalUpdaterExecutor.awaitTermination(300, TimeUnit.MILLISECONDS);
            kernelInitializerIsShutdown = kernelInitExecutor.awaitTermination(100, TimeUnit.MILLISECONDS);
            kernelExecutorIsShutdown = kernelExcExecutor.awaitTermination(100, TimeUnit.MILLISECONDS);
            dataSenderIsShutdown = dataSenderExecutor.awaitTermination(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("SimulationService", stackTrace);
        }

        if (!terminalUpdatersIsShutdown || !kernelExecutorIsShutdown || !kernelInitializerIsShutdown || dataSenderIsShutdown) {
            Log.e("SimulationService", "terminal updater is shutdown: " + terminalUpdatersIsShutdown +
                    " kernel initializer is shutdown: " + kernelInitializerIsShutdown + " kernel executor is shutdown: " + kernelExecutorIsShutdown +
                    " data sender is shutdown: " + dataSenderIsShutdown);
        }

        closeOpenCL(openCLObject);

        try {
            MainActivity.thisClient.objectInputStream.close();
            MainActivity.thisClient.objectOutputStream.close();
            MainActivity.thisClient.socket.close();
        } catch (IOException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("SimulationService", stackTrace);
        }

        shutdown = false;
        stopSelf();

    }

    /**
     * Update the info about the terminal using the object sent back by the server whenever the
     * topology of the virtual layer changes
     */

    private class TerminalUpdater implements Runnable {

        private BlockingQueue<Terminal> updatedTerminal;
        private Context context;

        TerminalUpdater(BlockingQueue<Terminal> a, Context c) {

            this.updatedTerminal = a;
            this.context = c;

        }

        @Override
        public void run(){

            while (!shutdown) {

                Terminal thisTerminal = new Terminal();

                try {
                    Object obj = MainActivity.thisClient.objectInputStream.readObject();
                    if (obj instanceof Terminal)
                        thisTerminal = ((Terminal)obj);
                    else {
                        Log.e("TerminalUpdater", "Object is in wrong format");
                        throw new IOException();
                    }
                } catch (IOException | ClassNotFoundException e) {
                    Log.e("TerminalUpdater", "Socket is closed: " + MainActivity.thisClient.socket.isClosed());
                    Log.e("TerminalUpdater", "Socket is connected: " + MainActivity.thisClient.socket.isConnected());
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("TerminalUpdater", stackTrace);
                    shutDown();
                    if (!errorRaised) {
                        Intent broadcastError = new Intent("ErrorMessage");
                        broadcastError.putExtra("ErrorNumber", 3);
                        LocalBroadcastManager.getInstance(context).sendBroadcast(broadcastError);
                        errorRaised = true;
                    }
                }

                try {
                    updatedTerminal.put(thisTerminal);
                } catch (InterruptedException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("TerminalUpdater", stackTrace);
                }

            }

        }

    }

    /*
    Class which calls the native method which schedules and runs the OpenCL kernel.
     */

    private class KernelExecutor implements Callable<Long> {

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

                // A return object on length zero means an error has occurred
                if (outputSpikes.length == 0) {
                    shutDown();
                    if (!errorRaised) {
                        Intent broadcastError = new Intent("ErrorMessage");
                        broadcastError.putExtra("ErrorNumber", 6);
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

    /*
    Runnable class which sends the spikes produced by the local network to the postsynaptic terminals,
    including the server itself
    */

    private class DataSender implements Runnable {

        private BlockingQueue<byte[]> kernelExcQueue;
        private short data_bytes = (NUMBER_OF_NEURONS % 8) == 0 ?
                (short) (NUMBER_OF_NEURONS / 8) : (short)(NUMBER_OF_NEURONS / 8 + 1);
        private byte[] outputSpikes = new byte[data_bytes];
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

                Terminal thisTerminalLocal = thisTerminal;

                for (short index = 0; index < thisTerminalLocal.postsynapticTerminals.size(); index++) {

                    try {

                        Terminal postsynapticTerminal = thisTerminalLocal.postsynapticTerminals.get(index);

                        InetAddress postsynapticTerminalAddr = InetAddress.getByName(postsynapticTerminal.ip);

                        DatagramPacket outputSpikesPacket = new DatagramPacket(outputSpikes, data_bytes, postsynapticTerminalAddr, postsynapticTerminal.natPort);

                        outputSocket.send(outputSpikesPacket);

                    } catch (IOException e) {
                        String stackTrace = Log.getStackTraceString(e);
                        Log.e("DataSender", stackTrace);
                    }

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
    public native byte[] simulateDynamics(char[] synapseInput, long openCLObject, short numOfNeurons, float[] simulationParameters);
    public native void closeOpenCL(long openCLObject);

}