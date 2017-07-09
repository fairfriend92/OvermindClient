package com.example.overmind;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

class KernelInitializer implements Runnable {

    // Queue that stores the inputs to be sent to the KernelExecutor thread
    private BlockingQueue<Input> kernelInitQueue;

    // IP of the presynaptic terminal whose output must be processed
    private String presynTerminalIP;

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

    private static final Object[] threadsLocks = new Object[Constants.MAX_NUM_SYNAPSES];

    private static volatile List<ArrayBlockingQueue<byte[]>> presynTerminalQueue;

    private static volatile List<Boolean> threadIsFree = Collections.synchronizedList(new ArrayList<Boolean>());

    // Double array with one dimension representing the presynaptic terminals and the other the
    // input of a certain terminal
    private static volatile char[][] synapticInputCollection;

    KernelInitializer(BlockingQueue<Input> b, String s, byte[] b1, Terminal t) {
        this.kernelInitQueue = b;
        this.presynTerminalIP = s;
        this.inputSpikesBuffer = b1;
        this.thisTerminal = t;
    }

    @Override
    public void run () {

        /*
        When we are putting together a new input there is some initialization to do...
        */

        if (thisTerminal != null) {

            // If the information of the terminal have been updated...
            synchronized (lock) {

                // This ArrayList can't be a simple reference because we want the connections to
                // be updated only when threadsCounter == 0
                presynapticTerminals = new ArrayList<>(thisTerminal.presynapticTerminals);
                numOfConnections = presynapticTerminals.size();

                // Create the array storing the kernel input derived from the spikes produced
                // by each presynaptic Terminal
                synapticInputCollection = new char[numOfConnections][4096];

                threadIsFree = new ArrayList<>(numOfConnections);
                presynTerminalQueue = new ArrayList<>(numOfConnections);

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
        presynTerminal.ip = presynTerminalIP;
        int presynTerminalIndex = presynapticTerminals.indexOf(presynTerminal);

        // If it was not possible to identify the presynaptic terminal drop the packet and return
        if (presynTerminalIndex == -1) {
            Log.e("KernelInitializer", "Cannot find presynTerminal with ip " + presynTerminalIP);
            KernelInitializer.threadIsFree.set(presynTerminalIndex, false);
            return;
        }

        try {
            presynTerminalQueue.get(presynTerminalIndex).put(inputSpikesBuffer);
        } catch (InterruptedException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("KernelInitializer", stackTrace);
        }

        boolean threadIsFree = false;

        while (!threadIsFree) {

            synchronized (threadsLocks[presynTerminalIndex]) {

                if (!KernelInitializer.threadIsFree.get(presynTerminalIndex)) {

                    try {
                        presynTerminalQueue.get(presynTerminalIndex).take();
                    } catch (InterruptedException e) {
                        String stackTrace = Log.getStackTraceString(e);
                        Log.e("KernelInitializer", stackTrace);
                    }
                    threadIsFree = true;
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

        char[] synapticInput = new char[presynTerminal.numOfNeurons * Constants.MAX_MULTIPLICATIONS];

        // The runnable initializes the kernel at time n using the input at time n - 1, which
        // must be first retrieved from synapticInputCollection
        char[] oldInput = synapticInputCollection[presynTerminalIndex];
        System.arraycopy(oldInput, 0, synapticInput, 0, synapticInput.length);

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
                                (char) (synapticInput[indexJ + indexI * Constants.MAX_MULTIPLICATIONS - bitValue] + 1) : 0;

            }

            // Make room for the new input in case bitValue = 1
            switch (bitValue) {
                case 1:
                    synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] = 1;
                    break;
                default:
                    synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] =
                            (synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] != 0) && (synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] < Constants.SYNAPSE_FILTER_ORDER) ?
                                    (char)(synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] + 1) : 0;
                    break;
            }

        }

        synapticInputCollection[presynTerminalIndex] = synapticInput;

        try {
            Log.e("KernelInitializer", " " + kernelInitQueue.remainingCapacity() + " " + presynTerminalIndex);
            kernelInitQueue.put(new Input(synapticInput, presynTerminalIndex));
        } catch (InterruptedException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("KernelInitializer", stackTrace);
        }

        KernelInitializer.threadIsFree.set(presynTerminalIndex, false);

    }
    /* [End of run() method] */

}
/* [End of class] */