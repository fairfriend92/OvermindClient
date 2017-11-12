package com.example.overmind;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

class KernelInitializer implements Runnable {

    // Queue that stores the inputs to be sent to the KernelExecutor thread
    private BlockingQueue<Input> kernelInitQueue;

    // Queue that stores the signals that clock the dataSender thread
    private BlockingQueue<Object> clockSignalsQueue;

    // IP and nat port of the presynaptic terminal whose output must be processed
    private String presynTerminalIP;
    private int presynTerminalNatPort;

    // Local collection of the presynaptic terminals
    private static volatile List<Terminal> presynapticTerminals = Collections.synchronizedList(new ArrayList<Terminal>());
    static int numOfConnections = 0;

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

    // Atomic long used to compute the refresh rate at which new packets are being received
    private static AtomicLong lastTime = new AtomicLong(0);

    // Flag that signals whether the physical terminal is clocked by the server
    private static volatile boolean connectedToServer = false;

    // Double array with one dimension representing the presynaptic terminals and the other the
    // input of a certain terminal
    private static volatile byte[][] synapticInputCollection;

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
    public void run () {

        /*
        When we are putting together a new input there is some initialization to do...
        */

        if (thisTerminal != null) {

            Log.d("KernelInitializer", "test");

            // If the information of the terminal have been updated...
            synchronized (lock) {

                // This ArrayList can't be a simple reference because we want the connections to
                // be updated only when threadsCounter == 0
                presynapticTerminals = new ArrayList<>(thisTerminal.presynapticTerminals);

                numOfConnections = presynapticTerminals.size();

                // Create the array storing the kernel input derived from the spikes produced
                // by each presynaptic Terminal
                synapticInputCollection = new byte[numOfConnections][0];

                threadIsFree = new ArrayList<>(numOfConnections);

                presynTerminalQueue = new ArrayList<>(numOfConnections);

                connectedToServer = presynapticTerminals.contains(MainActivity.server);

                for (int i = 0; i < numOfConnections; i++) {
                    threadsLocks[i] = new Object();
                    threadIsFree.add(false);
                    presynTerminalQueue.add(new ArrayBlockingQueue<byte[]>(4));
                }

            }

        }

        if (numOfConnections == 0) {
            Log.e("KernelInitializer", "No presynaptic connection has been established: exiting KernelInitializer");
            return;
        }

        // Identify the presynaptic terminal using the IP contained in the header of the datagram
        // packet
        Terminal presynTerminal = new Terminal();
        presynTerminal.serverIP = MainActivity.server.ip;
        presynTerminal.ip = presynTerminalIP;
        presynTerminal.natPort = presynTerminalNatPort;
        int presynTerminalIndex = presynapticTerminals.indexOf(presynTerminal);

        // If it was not possible to identify the presynaptic terminal drop the packet and return
        if (presynTerminalIndex == -1) {
            for (Terminal presynapticTerminal : presynapticTerminals){
                Log.e("KernelInitializer", " " + presynapticTerminal.ip + " " + presynapticTerminal.natPort);
            }
            Log.e("KernelInitializer", "Cannot find presynTerminal with ip " + presynTerminalIP + " " + presynTerminalNatPort);
            return;
        }

        // Put in the buffer of the connection that is being served by this thread the packet just
        // received
        try {
            presynTerminalQueue.get(presynTerminalIndex).put(inputSpikesBuffer);
        } catch (InterruptedException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("KernelInitializer", stackTrace);
        }

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

        byte[] synapticInput = new byte[presynTerminal.numOfNeurons * Constants.MAX_MULTIPLICATIONS];

        // The runnable initializes the kernel at lastTime n using the input at lastTime n - 1, which
        // must be first retrieved from synapticInputCollection
        if (synapticInputCollection[presynTerminalIndex].length != 0)
            System.arraycopy(synapticInputCollection[presynTerminalIndex], 0, synapticInput, 0, synapticInput.length);

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

            // Make room for the new input in case bitValue = 1
            switch (bitValue) {
                case 1:
                    synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] = 1;
                    break;
                default:
                    synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] =
                            (synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] != 0) && (synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] < Constants.SYNAPSE_FILTER_ORDER) ?
                                    (byte)(synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] + 1) : 0;
                    break;
            }

        }

        synapticInputCollection[presynTerminalIndex] = synapticInput;

        try {
            // If the terminal is clocked and it's just received a packet from the server, or if it
            // is not and the first connection has fired again, then DataSender can send the latest
            // array of spikes produced by KernelExecutor
            if ((!connectedToServer && presynTerminalIndex == 0) ||
                    (connectedToServer && presynTerminalIP.equals(Constants.SERVER_IP))) {

                // Put in the queue an object which unblocks the waiting DataSender
                clockSignalsQueue.put(new Object());

                // Update the refresh rate
                InputCreator.waitTime.set(System.nanoTime() - lastTime.get());

                lastTime.set(System.nanoTime());

            }

            Log.d("KernelInitializer", " " + presynTerminal.ip);
            kernelInitQueue.put(new Input(synapticInput, presynTerminalIndex, false, numOfConnections));

        } catch (InterruptedException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("KernelInitializer", stackTrace);
        }

        KernelInitializer.threadIsFree.set(presynTerminalIndex, false);

    }
    /* [End of run() method] */

}
/* [End of class] */