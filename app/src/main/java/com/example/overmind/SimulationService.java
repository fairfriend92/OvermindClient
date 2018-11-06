/**
 * Background service used to initialize the OpenCL implementation and execute the Thread pool managers
 * for data reception, Kernel initialization, Kernel execution and data sending
 */

package com.example.overmind;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;
import android.telephony.TelephonyManager;
import android.util.Log;


import java.io.IOException;
import java.io.ObjectInputStream;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.example.overmind.Constants.NUMBER_OF_NEURONS;
import static com.example.overmind.Constants.SERVER_IP;

public class SimulationService extends IntentService {

    // TODO TCP and UDP socket can live on the same port
    private static final int IPTOS_THROUGHPUT = 0x08;
    private boolean errorRaised = false;
    private static int errornumber = 0;

    // Object used to hold all the relevant info pertaining this terminal
    static volatile Terminal thisTerminal = new Terminal();

    // List of Futures related to the instances of kernelInitExecutor, used to check if the
    // relative thread has finished its computation
    List<Future<?>> kernelInitFutures = new ArrayList<Future<?>>();

    /*
    Queues and Thread executors used to parallelize the computation.
    */

    // Buffer that stores the workitems which are going to be executed by KernelInitializer
    BlockingQueue<Runnable> kernelInitWorkerThreadsQueue = new ArrayBlockingQueue<>(128);

    // Buffer that contains the Input elaborated by KernelInitializer
    BlockingQueue<Input> kernelInitQueue = new LinkedBlockingQueue<>(128);

    // Buffer that contains the total input put together by InputCreator
    BlockingQueue<InputCreatorOutput> inputCreatorQueue = new ArrayBlockingQueue<>(128);

    // Buffer holding the clock signals created by kernelInitializer which signals when dataSender
    // should proceed to send the spikes.
    BlockingQueue<Object> clockSignalsQueue = new ArrayBlockingQueue<>(32);

    // Policy for KernelInitializer that creates an exception whenever a packet is dropped due
    // to the buffers' being full
    ThreadPoolExecutor.AbortPolicy rejectedExecutionHandler = new ThreadPoolExecutor.AbortPolicy();

    // Custom executor for KernelInitializer which allows to change the number of threads in the
    // pools dynamically. Moreover, it allows to specify the time to wait if the workitems
    // buffer is full before creating an exception
    ThreadPoolExecutor kernelInitExecutor = new ThreadPoolExecutor(2, 2, 3, TimeUnit.MILLISECONDS, kernelInitWorkerThreadsQueue, rejectedExecutionHandler);

    // Buffer where to put the array of spikes produced by the local network
    BlockingQueue<byte[]> kernelExcQueue = new ArrayBlockingQueue<>(128);

    // Executor for the thread that calls the OpenCL method
    ExecutorService kernelExcExecutor = Executors.newSingleThreadExecutor();

    // Future object which holds the pointer to the OpenCL structure defined in native_method.h
    Future<Long> newOpenCLObject;

    // Executor for the thread that put together the total input made of the inputs of the
    // single connections
    ExecutorService inputCreatorExecutor = Executors.newSingleThreadExecutor();

    // Executor for the thread that sends the spikes produced by the local network to the
    // postsynaptic devices
    ExecutorService dataSenderExecutor = Executors.newSingleThreadExecutor();

    // Executor for the thread that updates the information about the local network
    ExecutorService terminalUpdaterExecutor = Executors.newSingleThreadExecutor();

    // Buffers containing the last updated info about the local network
    BlockingQueue<Terminal> updatedTerminal = new ArrayBlockingQueue<>(1);
    BlockingQueue<Terminal> newWeights = new ArrayBlockingQueue<>(16);

    public SimulationService() {
        super("SimulationService");
    }

    static volatile boolean shutdown = false;
    static public void shutDown () {

        shutdown = true;

    }

    public static String getNetworkClass(Context context) {
        TelephonyManager mTelephonyManager = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
        int networkType = mTelephonyManager.getNetworkType();

        String connectionClass;

        switch (networkType) {
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
            case TelephonyManager.NETWORK_TYPE_IDEN:
                connectionClass =  "2G";
                break;
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
            case TelephonyManager.NETWORK_TYPE_EHRPD:
            case TelephonyManager.NETWORK_TYPE_HSPAP:
                connectionClass = "3G";
                break;
            case TelephonyManager.NETWORK_TYPE_LTE:
                connectionClass = "4G";
                break;
            default:
                connectionClass = "Unknown";
        }

        boolean connectedToInternet;

        try {
            InetAddress ipAddr = InetAddress.getByName("google.com");
            connectedToInternet =  !ipAddr.toString().equals("");
        } catch (Exception e) {
            connectedToInternet =  false;
        }

        if (connectedToInternet)
            return connectionClass;
        else
            return "No internet connection";

    }

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
            InetAddress serverAddr = InetAddress.getByName(SERVER_IP);
            byte[] testData = new byte[1];
            DatagramPacket testPacket = new DatagramPacket(testData, 1, serverAddr, Constants.SERVER_PORT_UDP);
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
                errornumber = 1;
                errorRaised = true;
            }
        }

        /*
        Get the string holding the kernel and initialize the OpenCL implementation.
         */

        String kernel = workIntent.getStringExtra("Kernel");
        long openCLObject = initializeOpenCL(kernel, NUMBER_OF_NEURONS, Constants.SYNAPSE_FILTER_ORDER, Constants.NUMBER_OF_SYNAPSES);

        // Launch those threads that are persistent
        inputCreatorExecutor.execute(new InputCreator(kernelInitQueue, inputCreatorQueue, clockSignalsQueue));
        newOpenCLObject = kernelExcExecutor.submit(new KernelExecutor(inputCreatorQueue, kernelExcQueue, openCLObject, newWeights));
        dataSenderExecutor.execute(new DataSender(kernelExcQueue, datagramSocket, clockSignalsQueue));
        terminalUpdaterExecutor.execute(new TerminalUpdater(updatedTerminal, newWeights));

        /*
        Get the updated info about the connected terminals stored in the Terminal class. Then
        receive the packets from the known connected terminals.
         */

        // Number of bytes necessary to hold the longest input among those of the presynaptic terminals
        short maxDataBytes = 1;

        Terminal thisTerminal = null;

        // Flag that signals whether the receive() call to the datagram socket timed out or whether
        // a packet was received.
        boolean receiveTimedOut = false;

        while (!shutdown) {

            try {

                // Iterate over the list of futures to remove those that signal that the respective
                // threads are done
                Iterator iterator = kernelInitFutures.iterator();
                while (iterator.hasNext()) {
                    Future<?> future = (Future<?>) iterator.next();
                    if (future.isDone())
                        iterator.remove();
                }

                // If datagramSocket.receive() timed out, the last Terminal retrieved has not been
                // passed to KernelInitializer and may still be valid
                if (!receiveTimedOut)
                    thisTerminal = updatedTerminal.poll();

                // If the terminal info have been updated
                if (thisTerminal != null) {

                    // Update the variable holding the terminal info
                    SimulationService.thisTerminal = thisTerminal;

                    // Change the size of the pool of kernelInitExecutors appropriately
                    int corePoolSize = thisTerminal.presynapticTerminals.size() > 0 ? thisTerminal.presynapticTerminals.size() :
                            kernelInitExecutor.getCorePoolSize();
                    int maximumPoolSize = thisTerminal.presynapticTerminals.size() > kernelInitExecutor.getMaximumPoolSize() ?
                            thisTerminal.presynapticTerminals.size() : kernelInitExecutor.getMaximumPoolSize();
                    kernelInitExecutor.setCorePoolSize(corePoolSize);
                    kernelInitExecutor.setMaximumPoolSize(maximumPoolSize);

                    // Update the value of maxDataBytes based on the new inputs
                    maxDataBytes = 1; // First reset the value, in case the previous biggest input is no longer present
                    for (Terminal presynapticTerminal : thisTerminal.presynapticTerminals) {
                        short dataBytes = (presynapticTerminal.numOfNeurons % 8) == 0 ?
                                (short) (presynapticTerminal.numOfNeurons / 8) : (short)(presynapticTerminal.numOfNeurons / 8 + 1);
                        maxDataBytes = dataBytes > maxDataBytes ? dataBytes : maxDataBytes;
                    }

                    // Determine if the lateral connections option has been changed by the server
                    Constants.INDEX_OF_LATERAL_CONN = thisTerminal.presynapticTerminals.indexOf(thisTerminal);
                    Constants.LATERAL_CONNECTIONS = Constants.INDEX_OF_LATERAL_CONN != -1;

                    // If the info about the terminal have been updated, wait for the threads to finish
                    // before dispatching new ones
                    for (Future<?> future : kernelInitFutures)
                        future.get();
                    kernelInitFutures = new ArrayList<>(kernelInitExecutor.getMaximumPoolSize());

                }

                // Receive the latest packet containing the spikes and store its address
                byte[] inputSpikesBuffer = new byte[maxDataBytes];
                DatagramPacket inputSpikesPacket = new DatagramPacket(inputSpikesBuffer, maxDataBytes);
                datagramSocket.receive(inputSpikesPacket);
                inputSpikesBuffer = inputSpikesPacket.getData();
                InetAddress presynapticTerminalAddr = inputSpikesPacket.getAddress();
                receiveTimedOut = false;

                //Log.d("SimulationService", "Submitting kernelInitializer instance");

                // Put the workload in the queue
                Future<?> future = kernelInitExecutor.submit(new KernelInitializer(kernelInitQueue, presynapticTerminalAddr.toString().substring(1),
                        inputSpikesPacket.getPort(), inputSpikesBuffer, thisTerminal, clockSignalsQueue));

                kernelInitFutures.add(future);

            } catch (SocketTimeoutException e) {
                receiveTimedOut = true;
                String stackTrace = Log.getStackTraceString(e);
                Log.e("SimulationService", stackTrace);
                if (!getNetworkClass(this).equals("4G") & !Constants.USE_LOCAL_CONNECTION) {
                    shutDown();
                    if (!errorRaised) {
                        errornumber = 2;
                        errorRaised = true;
                    }
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

        // TODO: Do orderly shutdown and use shutdownNow as a last resort.
        terminalUpdaterExecutor.shutdownNow();
        kernelInitExecutor.shutdownNow();
        inputCreatorExecutor.shutdownNow();
        kernelExcExecutor.shutdownNow();
        dataSenderExecutor.shutdownNow();


        boolean terminalUpdatersIsShutdown = false;
        boolean inputCreatorIsShutdown = false;
        boolean kernelInitializerIsShutdown = false;
        boolean kernelExecutorIsShutdown = false;
        boolean dataSenderIsShutdown = false;

        // Print whether or not the shutdowns were successful
        try {
            terminalUpdatersIsShutdown = terminalUpdaterExecutor.awaitTermination(300, TimeUnit.MILLISECONDS);
            kernelInitializerIsShutdown = kernelInitExecutor.awaitTermination(100, TimeUnit.MILLISECONDS);
            inputCreatorIsShutdown = inputCreatorExecutor.awaitTermination(100, TimeUnit.MILLISECONDS);
            kernelExecutorIsShutdown = kernelExcExecutor.awaitTermination(100, TimeUnit.MILLISECONDS);
            dataSenderIsShutdown = dataSenderExecutor.awaitTermination(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("SimulationService", stackTrace);
        }

        if (!terminalUpdatersIsShutdown || !kernelExecutorIsShutdown || !kernelInitializerIsShutdown
                || !dataSenderIsShutdown || !inputCreatorIsShutdown) {
            Log.e("SimulationService", "terminal updater is shutdown: " + terminalUpdatersIsShutdown +
                    " kernel initializer is shutdown: " + kernelInitializerIsShutdown + " kernel executor is shutdown: " + kernelExecutorIsShutdown +
                    " data sender is shutdown: " + dataSenderIsShutdown + " input creator is shutdown: " + inputCreatorIsShutdown);
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

        /* Reset some static variables for further use */

        shutdown = false;
        thisTerminal = null;

        // The field needs to be reset since the next time the terminal connects with the server
        // some settings, like num of synapses, may have changed. Since it takes some time for the
        // Terminal object with the new info to arrive from the server, in the meanwhile all
        // incoming udp packets must be discarded to prevent errors.
        KernelInitializer.numOfConnections = 0;

        Log.d("SimulationService", "Closing SimulationService");

        stopSelf();

        if (errorRaised) {
            Intent broadcastError = new Intent("ErrorMessage");
            broadcastError.putExtra("ErrorNumber", errornumber);
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastError);
        }

    }

    /**
     * Update the info about the terminal using the object sent back by the server whenever the
     * topology of the virtual layer changes
     */

    private class TerminalUpdater implements Runnable {
        private BlockingQueue<Terminal> updatedTerminal;
        private BlockingQueue<Terminal> newWeights;

        TerminalUpdater(BlockingQueue<Terminal> b, BlockingQueue<Terminal> b1) {

            updatedTerminal = b;
            newWeights = b1;

        }

        @Override
        public void run(){
            while (!shutdown) {
                Terminal thisTerminal;

                try {
                    Object obj = MainActivity.thisClient.objectInputStream.readObject();
                    if (obj instanceof Terminal) {
                        thisTerminal = ((Terminal) obj);

                        // TODO: Probably having two different queues is not necessary.
                        // The last terminal that is received is the only one that matters, thus the queue can be cleared
                        updatedTerminal.clear();
                        updatedTerminal.offer(thisTerminal);

                        // Vice versa, it may be important to conserve more than one weights array if the simulation has not updated them yet
                        newWeights.offer(thisTerminal);

                        for (Terminal presynConn : thisTerminal.presynapticTerminals) {
                            Log.d("TerminalUpdater", "ip of presynConn "
                                    + presynConn.ip);
                        }
                    }
                } catch (IOException | ClassNotFoundException e) {
                    Log.e("TerminalUpdater", "Socket is closed: " + MainActivity.thisClient.socket.isClosed());
                    Log.e("TerminalUpdater", "Socket is connected: " + MainActivity.thisClient.socket.isConnected());
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("TerminalUpdater", stackTrace);
                    shutDown();
                    if (!errorRaised) {
                        errornumber = 3; // TODO: Use constant variables with appropriate names
                        errorRaised = true;
                    }
                }
            }
        }
    }

    /*
    Class which calls the native method which schedules and runs the OpenCL kernel.
     */

    private class KernelExecutor implements Callable<Long> {
        private BlockingQueue<InputCreatorOutput> inputCreatorQueue;
        private long openCLObject;
        private InputCreatorOutput inputCreatorOutput;
        private short data_bytes = (NUMBER_OF_NEURONS % 8) == 0 ?
                (short) (NUMBER_OF_NEURONS / 8) : (short)(NUMBER_OF_NEURONS / 8 + 1);
        private byte[] outputSpikes = new byte[data_bytes];
        private BlockingQueue<byte[]> kernelExcQueue;
        private BlockingQueue<Terminal> newTerminalQueue;
        private IndexesMatrixBuilder indexesMatrixBuilder = new IndexesMatrixBuilder();
        private boolean populationPresent = false;

        KernelExecutor(BlockingQueue<InputCreatorOutput> b, BlockingQueue<byte[]> b1, long l1, BlockingQueue<Terminal> b2) {
            inputCreatorQueue = b;
            kernelExcQueue = b1;
            openCLObject = l1;
            newTerminalQueue = b2;
        }

        @Override
        public Long call () {
            while (!shutdown) {

                Terminal newTerminal = null;

                try {
                    inputCreatorOutput = inputCreatorQueue.take();
                    newTerminal = newTerminalQueue.poll();
                } catch (InterruptedException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("KernelExecutor", stackTrace);
                }

                // TODO: Instead of assigning zero length arrays is it possible to pass a null to the native side and check against nullptr?
                byte[] weights = new byte[0];
                int[] weightsIndexes = new int[0];
                byte[] updateWeightsFlags = new byte[0];
                IndexesMatrices indexesMatrices = new IndexesMatrices(
                        new int[0][0], new int[0][0]);

                if (newTerminal != null) {
                    Log.d("KernelExecutor", "New terminal present");

                    weights = newTerminal.newWeights;
                    weightsIndexes = newTerminal.newWeightsIndexes;
                    updateWeightsFlags = newTerminal.updateWeightsFlags;

                    // If the matrix of populations has been created, it should be used to generate the indexes for the
                    // synapses
                    if (newTerminal.popsMatrix != null) {
                        Log.d("KernelExecutor", "New terminal has popsMatrix != null");

                        indexesMatrices = indexesMatrixBuilder.buildIndexesMatrix(newTerminal);
                        if (newTerminal.popsMatrix.length != 0) {
                            Log.d("KernelExecutor", "popsMatrix has length " +
                                    newTerminal.popsMatrix.length);

                            populationPresent = true;
                        }
                        else {
                            populationPresent = false;
                        }
                    }
                }

                // Call the native side only if there is at least one population
                if (populationPresent) {
                    outputSpikes = simulateDynamics(inputCreatorOutput.resizedSynapticInput, openCLObject,
                            SimulationParameters.getParameters(), weights, weightsIndexes,
                            inputCreatorOutput.resizedFiringRates, updateWeightsFlags,
                            indexesMatrices.indexesMatrix, indexesMatrices.neuronsMatrix);

                    // A return object on length zero means an error has occurred
                    if (outputSpikes.length == 0) {
                        shutDown();
                        if (!errorRaised) {
                            errornumber = 6;
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
        private short dataBytes = (NUMBER_OF_NEURONS % 8) == 0 ?
                (short) (NUMBER_OF_NEURONS / 8) : (short)(NUMBER_OF_NEURONS / 8 + 1);
        private DatagramSocket outputSocket;
        private BlockingQueue<Object> clockSignals;

        DataSender(BlockingQueue<byte[]> b, DatagramSocket d, BlockingQueue<Object> b1) {
            kernelExcQueue = b;
            outputSocket = d;
            clockSignals = b1;
        }

        @Override
        public void run () {
            while (!SimulationService.shutdown) {
                byte[] outputSpikes;

                try {
                    outputSpikes = kernelExcQueue.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("DataSender", stackTrace);
                    return;
                }

                /*
                When clock signal is null, then the terminal is not receiving any input and the only packet
                it needs to send is the one needed to keep alive the socket on the server.

                If clock signal is different from null, then the terminal is being stimulated and the
                spikes should be sent to the postsynaptic terminals. If no spikes have been produced yet
                (outputSpikes = null), don't do anything at all.
                 */

                if (outputSpikes != null) {

                    for (Terminal postsynapticTerminal : thisTerminal.postsynapticTerminals) {

                        try {
                            InetAddress postsynapticTerminalAddr = InetAddress.getByName(postsynapticTerminal.ip);
                            DatagramPacket outputSpikesPacket = new DatagramPacket(outputSpikes, outputSpikes.length, postsynapticTerminalAddr, postsynapticTerminal.natPort);
                            outputSocket.send(outputSpikesPacket);
                        } catch (IOException e) {
                            String stackTrace = Log.getStackTraceString(e);
                            Log.e("DataSender", stackTrace);
                        }

                    }

                } else {

                    try {
                        InetAddress serverAddress = InetAddress.getByName(SERVER_IP);
                        DatagramPacket pingPacket = new DatagramPacket(new byte[1], 1, serverAddress, Constants.UDP_PORT);
                        outputSocket.send(pingPacket);
                    } catch (IOException e) {
                        String stackTrace = Log.getStackTraceString(e);
                        Log.e("DataSender", stackTrace);
                    }
                }
            }
             /* [End of the while loop] */
            // TODO Close the datagram outputsocket
        }
        /* [End of the run loop] */
    }
    /* [End of the DataSender class] */

    public native long initializeOpenCL(String synapseKernel, short numOfNeurons, int filterOrder, short numOfSynapses);
    public native byte[] simulateDynamics(byte[] synapseInput, long openCLObject, float[] simulationParameters,
                                          byte[] weights, int[] weightsIndexes, float[] presynFiringRates, byte[] updateWeightsFlags,
                                          int[][] indexesmatrix, int[][] neuronsMatrix);
    public native void closeOpenCL(long openCLObject);
}

