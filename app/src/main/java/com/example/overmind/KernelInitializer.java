package com.example.overmind;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// TODO: For the returned Integer used final variables with meaningful names.

class KernelInitializer implements Callable<Integer> {

    // Queue that stores the inputs to be sent to the KernelExecutor thread
    private BlockingQueue<Input> kernelInitQueue;

    // Queue that stores the signals that clock the dataSender thread
    private BlockingQueue<Object> clockSignalsQueue;

    // IP and nat port of the presynaptic terminal whose output must be processed
    private String presynTerminalIP;
    private int presynTerminalNatPort;

    // Local collection of the presynaptic terminals
    private static volatile List<Terminal> presynapticTerminals = Collections.synchronizedList(new ArrayList<Terminal>());

    // Each element of the array represents the number of neurons of the respective presynaptic connection
    private static int[] connectionsSize;

    // Each element represents of many neurons come before the ones of the respective connections in the total synaptic input
    private static int[] connectionsOffset;

    // The number of presynaptic connections
    static int numOfConnections = 0; // DO NOT make private

    // Local variable storing information about the terminal in use
    private Terminal thisTerminal;

    // Buffer to hold the incoming spikes until the length of the synaptic input array has not been
    // figured out
    private byte[] inputSpikesBuffer;

    // Object used for synchronization
    private static final Object lock = new Object();

    // Array of Objects used to lock the single connections
    private static final Object[] threadsLocks = new Object[Constants.NUMBER_OF_SYNAPSES];

    // List of buffers, one for each connections
    private static volatile List<ArrayBlockingQueue<byte[]>> presynTerminalQueue;

    // List of flags indicating whether the respective connections is being served already
    private static volatile List<Boolean> threadIsFree = Collections.synchronizedList(new ArrayList<Boolean>());

    // List of nat ports that have not been mapped to a terminal yet
    private static volatile List<Integer> unknownPorts = Collections.synchronizedList(new ArrayList<Integer>());

    // List of ports that have been mapped
    private static volatile List<Integer> knownPorts = Collections.synchronizedList(new ArrayList<Integer>());

    // Double array with one dimension representing the presynaptic terminals and the other the
    // input of a certain terminal
    private static volatile byte[][] synapticInputCollection;

    // Double array where, as before, the first dimension represents a presynaptic terminal and the
    // one the firing rates of the neurons belonging to said terminal
    private static volatile float[][] firingRatesCollection;

    // Collection of the times at which the connections fired
    private static volatile long[] lastFiringTimes;

    // Collections of average time intervals between spikes for each connection
    private static volatile long[] meanTimeIntervals;

    // Map that links the local port of a presynaptic terminal to index of the terminal
    private static volatile ConcurrentHashMap<Integer, Integer> connectionsMap;

    // Index of the shortest time interval among the ones in the collection
    private static AtomicInteger shortestTInterIndex;

    KernelInitializer(BlockingQueue<Input> b, String s, int i, byte[] b1, Terminal t,
                      BlockingQueue<Object> b2) {
        this.kernelInitQueue = b;
        this.presynTerminalIP = s;
        this.presynTerminalNatPort = i;
        this.inputSpikesBuffer = b1;
        this.thisTerminal = t;
        this.clockSignalsQueue = b2;
    }

    @Override
    public Integer call () {

        /*
        When we are putting together a new input there is some initialization to do...
        */

        if (thisTerminal != null) {

            Log.d("KernelInitializer", "New terminal means new initialization...");

            // If the information of the terminal have been updated...
            synchronized (lock) {

                // We build a new arraylist using that contained in thisTerinal because thisTerminal
                // is usually null but we want to be able to reference the arraylist at each iteration.
                presynapticTerminals = new ArrayList<>(thisTerminal.presynapticTerminals);

                /* Point the Objects to new memory space */

                numOfConnections = presynapticTerminals.size();
                synapticInputCollection = new byte[numOfConnections][];
                firingRatesCollection = new float[numOfConnections][];
                lastFiringTimes = new long[numOfConnections];
                meanTimeIntervals = new long[numOfConnections];
                shortestTInterIndex = new AtomicInteger(0);
                threadIsFree = new ArrayList<>(numOfConnections);
                unknownPorts = new ArrayList<>(numOfConnections);
                knownPorts = new ArrayList<>(numOfConnections);
                connectionsMap = new ConcurrentHashMap<>(numOfConnections);

                // We add the lateral connection here since no packet will be received from this connection
                // until the neurons of this terminal fire, but that won't happen unless all the presynaptic connections
                // have been mapped
                if (Constants.INDEX_OF_LATERAL_CONN != - 1) {
                    knownPorts.add(thisTerminal.natPort);
                    connectionsMap.put(thisTerminal.natPort, Constants.INDEX_OF_LATERAL_CONN);
                }

                presynTerminalQueue = new ArrayList<>(numOfConnections);
                connectionsSize = new int[numOfConnections];
                connectionsOffset = new int[numOfConnections];
                int totalOffset = 0;

                for (int i = 0; i < numOfConnections; i++) {
                    threadsLocks[i] = new Object();
                    threadIsFree.add(false);
                    presynTerminalQueue.add(new ArrayBlockingQueue<byte[]>(4));
                    connectionsSize[i] = presynapticTerminals.get(i).numOfNeurons;
                    totalOffset += connectionsSize[i];
                    connectionsOffset[i] = totalOffset;
                    meanTimeIntervals[i] = Integer.MAX_VALUE;
                }

            }

        }

        if (numOfConnections == 0) {
            Log.e("KernelInitializer", "No presynaptic connection has been established: exiting KernelInitializer");
            return 0;
        }

        // Identify the presynaptic terminal using the IP contained in the header of the datagram
        // packet
        Terminal presynTerminal = new Terminal();
        presynTerminal.ip = presynTerminalIP;
        presynTerminal.natPort = presynTerminalNatPort;
        presynTerminal.id = presynTerminal.customHashCode();
        int presynTerminalIndex = presynapticTerminals.indexOf(presynTerminal);

        synchronized (lock) {

            // If the presynaptic terminal was not found and its mapping is null.
            if (presynTerminalIndex == -1 && connectionsMap.get(presynTerminalNatPort) == null) {
                if (!unknownPorts.contains(presynTerminalNatPort)) {
                    if (numOfConnections < knownPorts.size() + 1 + unknownPorts.size()) {
                        Log.e("KernelInitializer",
                                "Inbound connections are more than expected: This should not happen!");

                        return 1;
                    } else {
                        unknownPorts.add(presynTerminalNatPort);
                    }
                }

                // Order the ports which we could not map to a presynaptic terminal in ascending order
                Collections.sort(unknownPorts);

                Log.d("KernelInitializer", "unknownPorts " + unknownPorts.size() + " numOfConnections " + numOfConnections + " knownPorts " + knownPorts.size());
                for (Integer unknownPort : unknownPorts) {
                    Log.d("KernelInitializer", "unknownPort " + unknownPort);
                }
                for (Integer knownPort : knownPorts) {
                    Log.d("KernelInitializer", "knownPort " + knownPort);
                }

                // If the number of collected ports is equal to that of the presyn terminals that have not been mapped
                if (unknownPorts.size() == numOfConnections - knownPorts.size()) {

                    Log.d("KernelInitializer", "Found all missing connections");

                    for (int i = 0; i < numOfConnections; i++) {
                        // Lowest available port number
                        int natPort = unknownPorts.get(0);

                        /*
                        The block is synchronized as multiple threads might access it simultaneously and thus
                        add/remove ports to the respective arraylists more than once
                         */

                        int index = i;
                        // Find the right index for the connection
                        while (connectionsMap.containsValue(index)) {
                            index++;
                        }

                        // Skip the iteration if the corresponding map has already been found
                        if (connectionsMap.get(natPort) == null) {

                            // Since the ports are ordered in ascending order, the map consists in assigning the first available
                            // port to the current terminal
                            connectionsMap.put(natPort, index);

                            // Remove the port that has been used right now
                            unknownPorts.remove(0);

                            // The number must be increased since we've just found out a new map
                            knownPorts.add(natPort);
                        }
                        /* [End of if] */
                    }
                    /* [End of for] */
                }
                /* [End of if] */

                return 0;
            } else if (presynTerminalIndex == -1 && connectionsMap.get(presynTerminalNatPort) != null) {

                // If the presynaptic terminal was not found but its nat port has already been mapped to an index
                presynTerminalIndex = connectionsMap.get(presynTerminalNatPort);

            } else if (presynTerminalIndex != -1 && connectionsMap.get(presynTerminalNatPort) == null) {

                // If the presynaptic terminal was found for the first time, update its entry in the connectionsMap
                connectionsMap.put(presynTerminalNatPort, presynTerminalIndex);

                // Increase the number of connections maps that have been discovered
                if (!knownPorts.contains(presynTerminalNatPort)) {
                    knownPorts.add(presynTerminalNatPort);
                }
            }

        }

        // Put in the buffer of the connection that is being served by this thread the packet just
        // received
        presynTerminalQueue.get(presynTerminalIndex).offer(inputSpikesBuffer);
        /*
        try {
            presynTerminalQueue.get(presynTerminalIndex).put(inputSpikesBuffer);
        } catch (InterruptedException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("KernelInitializer", stackTrace);
        }
        */

        // Local flag that signals when this thread can proceed to serve its connection
        boolean threadIsFree = false;

        while (!threadIsFree) {

            // This block is synchronized on the connection served by the thread
            synchronized (threadsLocks[presynTerminalIndex]) {

                // If the connection that must be served is available the thread can proceed (and
                // therefore is not free anymore)
                if (!KernelInitializer.threadIsFree.get(presynTerminalIndex)) {

                    // Retrieve from the buffer the oldest packets
                    try {
                        presynTerminalQueue.get(presynTerminalIndex).take();
                    } catch (InterruptedException e) {
                        String stackTrace = Log.getStackTraceString(e);
                        Log.e("KernelInitializer", stackTrace);
                    }

                    // Update the local variable
                    threadIsFree = true;

                    // Lock the connection
                    KernelInitializer.threadIsFree.set(presynTerminalIndex, true);

                }

            }

        }

        /*
        Create the array which stores the bits representing the spikes emitted by the neuron of the
        chosen presynaptic terminal
         */

        presynTerminal =  presynapticTerminals.get(presynTerminalIndex);

        int dataBytes = (presynTerminal.numOfNeurons % 8) == 0 ?
                (short) (presynTerminal.numOfNeurons / 8) : (short)(presynTerminal.numOfNeurons / 8 + 1);

        byte[] inputSpikes = new byte[dataBytes];
        System.arraycopy(inputSpikesBuffer, 0, inputSpikes, 0, dataBytes);

        // The runnable initializes the kernel at lastTime n using the input at lastTime n - 1, which
        // must be first retrieved from synapticInputCollection
        byte[] synapticInput = synapticInputCollection[presynTerminalIndex] == null ?
                new byte[presynTerminal.numOfNeurons * Constants.MAX_MULTIPLICATIONS] : synapticInputCollection[presynTerminalIndex];

        // Like before, if this terminal info have been updated, create a new array. Otherwise retrieve
        // the old firing rates.
        float[] firingRates = firingRatesCollection[presynTerminalIndex] == null ?
                new float[presynTerminal.numOfNeurons] : firingRatesCollection[presynTerminalIndex];

        /*
        For each synapse of the presynTerminal compute the appropriate input
         */

        for (int indexI = 0; indexI < presynTerminal.numOfNeurons; indexI++) {

            // Calculate the byte to which the current indexI belongs
            short byteIndex = (short) (indexI / 8);

            // Check whether the indexI-th synapse has fired or not
            char bitValue = (char) ((inputSpikes[byteIndex] >> (indexI - byteIndex * 8)) & 1);

            // Increment the synapse inputs and advance them in the filter pipe only in case of firing
            for (int indexJ = (Constants.MAX_MULTIPLICATIONS - 1); indexJ >= 1; indexJ--) {

                // Increment the input only if different from zero to begin with. Advance it if the synapse carries an action potential (bitValue = 1)
                synapticInput[indexJ + indexI * Constants.MAX_MULTIPLICATIONS] =
                        (synapticInput[indexJ + indexI * Constants.MAX_MULTIPLICATIONS - bitValue] != 0) && (synapticInput[indexJ + indexI * Constants.MAX_MULTIPLICATIONS - bitValue] < Constants.SYNAPSE_FILTER_ORDER) ?
                                (byte) (synapticInput[indexJ + indexI * Constants.MAX_MULTIPLICATIONS - bitValue] + 1) : 0;

            }

            // Make room for the new input in case bitValue = 1. Update the firing rates too
            switch (bitValue) {
                case 1:
                    firingRates[indexI] += Constants.MEAN_RATE_INCREMENT * (1 - firingRates[indexI]); // Moving mean firing rate
                    synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] = 1;
                    break;
                default:
                    firingRates[indexI] -= Constants.MEAN_RATE_INCREMENT * firingRates[indexI];
                    synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] =
                            (synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] != 0) && (synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] < Constants.SYNAPSE_FILTER_ORDER) ?
                                    (byte)(synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] + 1) : 0;
                    break;
            }

        }

        synapticInputCollection[presynTerminalIndex] = synapticInput; // Makes sense only in the case synapticInputCollection[presynTerminalIndex] was originally null
        firingRatesCollection[presynTerminalIndex] = firingRates;

        /*
        The clock used to time the sending of the outgoing packets is chosen among the frequencies of
        the currently active terminals.
         */

        try {
            // Compute the time elapsed since the last packet sent by the current presynaptic terminal
            long timeInterval = lastFiringTimes[presynTerminalIndex] != 0 ?
                    System.nanoTime() - lastFiringTimes[presynTerminalIndex] : 0;

            // Using the moving average algorithm compute the mean time intervals for the current presynaptic terminal
            meanTimeIntervals[presynTerminalIndex] += lastFiringTimes[presynTerminalIndex] == 0 ?
                    0 : 0.025f * (timeInterval - meanTimeIntervals[presynTerminalIndex]);
            // TODO: The number of samples to compute the average should be a function of the clock

            // Save the current time in the array storing the last recorded times at which a presynaptic terminal sent a packet
            lastFiringTimes[presynTerminalIndex] = System.nanoTime();

            /*
            The clock is updated only if the current terminal is the one whose frequency has been chosen
            to clock DataSender.
             */

            if (presynTerminalIndex == shortestTInterIndex.get()) {

                clockSignalsQueue.put(new Object());

                // Update the refresh rate
                if (meanTimeIntervals[shortestTInterIndex.get()] != 0)
                    InputCreator.waitTime.set(meanTimeIntervals[shortestTInterIndex.get()]);

            }

            /*
            Log.d("KernelInitializer", presynTerminalIndex + " " + presynTerminalNatPort + " " +
                    knownPorts.size() + " " + unknownPorts.size() +  " " + InputCreator.waitTime.get());
                    */

            boolean mustChangeIndex = shortestTInterIndex.get() == Constants.INDEX_OF_LATERAL_CONN | (
                    System.nanoTime() - lastFiringTimes[shortestTInterIndex.get()] > 8 * meanTimeIntervals[shortestTInterIndex.get()] &
                    meanTimeIntervals[presynTerminalIndex] < meanTimeIntervals[shortestTInterIndex.get()]); // New clock must be faster than old one

            shortestTInterIndex.set(mustChangeIndex & presynTerminalIndex != Constants.INDEX_OF_LATERAL_CONN
                    ? presynTerminalIndex : shortestTInterIndex.get());

            kernelInitQueue.put(new Input(synapticInput, presynTerminalIndex, connectionsSize, connectionsOffset, firingRates));

        } catch (InterruptedException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("KernelInitializer", stackTrace);
        }

        KernelInitializer.threadIsFree.set(presynTerminalIndex, false);

        return 0;
    }
    /* [End of run() method] */

}
/* [End of class] */