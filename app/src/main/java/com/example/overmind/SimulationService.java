/**
 * Background service used to initialize the OpenCL implementation and execute the Thread pool managers
 * for data reception, Kernel initialization, Kernel execution and data sending
 */

package com.example.overmind;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
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
    private static boolean errorRaised = false;
    private static int errornumber = 0;

    static {

        switch (MainActivity.vendor) {
            case "ARM":try {
                System.loadLibrary("overmind");
            } catch (UnsatisfiedLinkError e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("SimulationService", stackTrace);
            }
                break;
            default:
                errornumber = 4;
                errorRaised = true;
        }

    }

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

    ThreadPoolExecutor.AbortPolicy rejectedExecutionHandler = new ThreadPoolExecutor.AbortPolicy();

    // Custom executor for KernelInitializer which allows to change the number of threads in the
    // pools dynamically. Moreover, it allows to specify the time to wait if the workitems
    // buffer is full before creating an exception
    ThreadPoolExecutor kernelInitExecutor = new ThreadPoolExecutor(1, 1, 3,
            TimeUnit.MILLISECONDS, kernelInitWorkerThreadsQueue, rejectedExecutionHandler);

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

    /*
    Miscellanea
     */

    int postsynTerminalsInfo[][][] = null;

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

        Log.d("SimulationService", "Initializing OpenCL...");
        String kernel = workIntent.getStringExtra("Kernel");
        long openCLObject = initializeOpenCL(kernel, NUMBER_OF_NEURONS, Constants.SYNAPSE_FILTER_ORDER, Constants.NUMBER_OF_SYNAPSES);
        Log.d("SimulationService", "OpenCL initialization complete.");

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

        while (!shutdown & !errorRaised) {

            try {

                // TODO: When inspecting the future we should take notice of which connections are being
                // TODO: served so that no other packets coming from the same connections are sent to the
                // TODO: the executor.
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
                // TODO: Move the update of the terminal to info to separate method/class
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
                    Log.d("SimualtionService", "Index of lateral connection is " + Constants.INDEX_OF_LATERAL_CONN);

                    // If the info about the terminal have been updated, wait for the threads to finish
                    // before dispatching new ones
                    for (Future<?> future : kernelInitFutures)
                        future.get();
                    kernelInitFutures = new ArrayList<>(kernelInitExecutor.getMaximumPoolSize());

                    /*
                    Collect the information about the populations that are connected to the postsynaptic terminals
                    so that dataSender can send to the right terminals their respective portions of the output
                    of the native side
                     */

                    // TODO: Maybe offsets for the populations should be built server side by partitionTool?

                    postsynTerminalsInfo = new int[thisTerminal.postsynapticTerminals.size()][][];

                    // Iterate over the postsynaptic terminals
                    for (int i = 0; i < thisTerminal.postsynapticTerminals.size(); i ++) {
                        Terminal postTerminal = thisTerminal.postsynapticTerminals.get(i);

                        // Get the list of the indexes of the populations connected to this terminal
                        ArrayList<Integer> popsIdxs =
                                thisTerminal.outputsToPopulations.get(postTerminal.id);

                        // Create the array that will hold the information about this population
                        int info[][] = null;

                        // Proceed only if the terminal is connected to a population
                        if (popsIdxs != null) {
                            // The last element of the array holds information about the buffer that dataSender
                            // will need to create
                            info = new int[popsIdxs.size() + 1][2];

                            int matrixSize = thisTerminal.popsMatrix.length;
                            int offset = 0, // Offset of population from the beginning of the last layer
                                    k = 0; // Index used to access the info array

                            // Iterate over the populations of the last layer
                            for (int j = 0; j < thisTerminal.popsMatrix[matrixSize - 1].length; j++) {
                                Population pop = thisTerminal.popsMatrix[matrixSize - 1][j];

                                // If the population is connected to the i-th postsynaptic terminal...
                                if (popsIdxs.contains(pop.id)) {
                                    info[k][0] = offset / 8;
                                    info[k][1] = pop.numOfNeurons / 8;
                                    info[info.length - 1][0] += pop.numOfNeurons;
                                    k++;
                                }

                                offset += pop.numOfNeurons;
                            }
                        }

                        postsynTerminalsInfo[i] = info;
                    }

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
                try {
                    Future<?> future = kernelInitExecutor.submit(new KernelInitializer(kernelInitQueue, presynapticTerminalAddr.toString().substring(1),
                            inputSpikesPacket.getPort(), inputSpikesBuffer, thisTerminal, clockSignalsQueue));

                    kernelInitFutures.add(future);
                } catch (RejectedExecutionException e) {
                    Log.e("SimulationService", "Queue of kernelInitializer is full therefore is going to be cleared");

                    // TODO: The flow blocks somewhere else too because this is not enough to keep things going. Investigate.

                    int corePoolSize = kernelInitExecutor.getCorePoolSize();
                    kernelInitExecutor.getQueue().clear();
                    kernelInitExecutor.shutdownNow();
                    kernelInitExecutor =
                            new ThreadPoolExecutor(corePoolSize, corePoolSize, 3, TimeUnit.MILLISECONDS, kernelInitWorkerThreadsQueue, rejectedExecutionHandler);
                }

            } catch (SocketTimeoutException e) {
                receiveTimedOut = true;
                Log.d("SimulationService", "Timeout of the datagram socket: " +
                        "This is normal if the client is not being stimulated.");
                if (!getNetworkClass(this).equals("4G") & !Constants.USE_LOCAL_CONNECTION) {
                    shutDown();
                    if (!errorRaised) {
                        errornumber = 2;
                        errorRaised = true;
                    }
                }
            } catch (IOException | InterruptedException | ExecutionException |
                    IllegalArgumentException e) {
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

                    for (int i = 0; i < thisTerminal.postsynapticTerminals.size(); i++) {
                        Terminal postsynapticTerminal = thisTerminal.postsynapticTerminals.get(i);

                        // Get the info needed to locate the data to send to this terminal
                        int[][] info = postsynTerminalsInfo[i];

                        /*
                        // Proceed only if the postsynTerminal is connected to some populations
                        if (info != null) {
                            // Get the size of buffer that will hold the data
                            int size = info[info.length - 1][0];
                            size = size % 8 == 0 ? size : size + 1;
                            byte[] buffer = new byte[size];
                            int offset = 0;

                            // Iterate over the populations that are connected to this postsynaptic terminal
                            for (int j = 0; j < info.length - 1; j++) { // The last element of info contains info unrelated to the populations
                                // Copy the relevant data from the total array into the buffer for this specific terminal
                                System.arraycopy(outputSpikes, info[j][0], buffer, offset, info[j][1]);
                                offset += info[j][1];
                            }

                            // TODO: When sending only portions of outputspikes move the try block in here and send
                            // TODO: buffer instead of outputspikes
                        }
                        */

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

