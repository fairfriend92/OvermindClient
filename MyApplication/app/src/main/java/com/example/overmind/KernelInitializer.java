package com.example.overmind;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;

class KernelInitializer implements Runnable {

    // Queue that stores the inputs to be sent to the KernelExecutor thread
    private BlockingQueue<char[]> kernelInitQueue;

    // IP of the presynaptic terminal whose output must be processed
    private String presynTerminalIP;

    // Local collection of the presynaptic terminals
    private static List<Terminal> presynapticTerminals = Collections.synchronizedList(new ArrayList<Terminal>());

    // Local variable storing information about the terminal in use
    private Terminal thisTerminal;

    // Buffer to hold the incoming spikes until the length of the synaptic input array has not been
    // figured out
    private byte[] inputSpikesBuffer;

    // Object used for synchronization
    private static final Object lock = new Object();

    // Static variable used to synchronize threads when the spikes need to be passed to KernelExecutor
    private static short threadsCounter = 0;

    // Double array with one dimension representing the presynaptic terminals and the other the
    // input of a certain terminal
    private static char[][] synapticInputCollection;

    // An array used to remember which connections have already been served before putting together
    // the input.
    private static boolean connectionWasServed[];

    // Array obtained by joining together each element of synapticInputCollection
    private char[] totalSynapticInput = new char[Constants.MAX_NUM_SYNAPSES * Constants.MAX_MULTIPLICATIONS];

    KernelInitializer(BlockingQueue<char[]> b, String s, byte[] b1, Terminal t) {
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

        synchronized (lock) {

            // If the information of the terminal have been updated...
            if (thisTerminal != null) {

                // This ArrayList can't be a simple reference because we want the connections to
                // be updated only when threadsCounter == 0
                presynapticTerminals = new ArrayList<>(thisTerminal.presynapticTerminals);

                // Create the array storing the kernel input derived from the spikes produced
                // by each presynaptic Terminal
                synapticInputCollection = new char[presynapticTerminals.size()][4096];

                threadsCounter = 0;

                // Initialize to false the flags that tell which terminals have been served
                connectionWasServed = new boolean[presynapticTerminals.size()];

            }

            if (presynapticTerminals.size() == 0) {
                Log.e("KernelInitializer", "No presynaptic connection has been established: exiting KernelInitializer");
                return;
            }

        }

        // Identify the presynaptic terminal using the IP contained in the header of the datagram
        // packet
        Terminal presynTerminal = new Terminal();
        presynTerminal.ip = presynTerminalIP;
        int presynTerminalIndex = presynapticTerminals.indexOf(presynTerminal);

        // If it was not possible to identify the presynaptic terminal drop the packet and return
        if (presynTerminalIndex == -1) {
            Log.e("KernelInitializer", "Cannot find presynTerminal with ip " + presynTerminalIP);
            return;
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
            //TODO Can be further optimized
            if (bitValue == 1) {
                synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] = 1;
            } else {
                synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] =
                        (synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] != 0) && (synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] < Constants.SYNAPSE_FILTER_ORDER) ?
                        (char)(synapticInput[indexI * Constants.MAX_MULTIPLICATIONS] + 1) : 0;
            }

        }

        /*
        To create the total input put together the contributes of each thread. The computation needs to be serialized
        thus it is contained in the following synchronized block.
         */

        synchronized (lock) {

            // If we are processing the input of a connection that has already been served discard
            // it since we must first process all the inputs of the preceding iteration.
            if (connectionWasServed[presynTerminalIndex]) {
                Log.e("KernelInitializer", "Terminal with ip " + presynTerminalIP + " has already been served");
                return;
            } else if (!connectionWasServed[presynTerminalIndex])
                connectionWasServed[presynTerminalIndex] = true;

            threadsCounter++;

            synapticInputCollection[presynTerminalIndex] = synapticInput;

            // Proceed only if all the partial results have been computed
            if (threadsCounter == presynapticTerminals.size()) {

                threadsCounter = 0;
                connectionWasServed = new boolean[presynapticTerminals.size()];

                // Put together the complete input
                short offset = 0;

                for (int i = 0; i < presynapticTerminals.size(); i++) {
                    char[] tmpSynapticInput = synapticInputCollection[i];
                    System.arraycopy(tmpSynapticInput, 0, totalSynapticInput, offset, tmpSynapticInput.length);
                    offset += tmpSynapticInput.length;
                }

                try {
                    kernelInitQueue.put(totalSynapticInput);
                } catch (InterruptedException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("KernelInitializer", stackTrace);
                }

            }

        }
        /* [End of synchronized block] */

    }
    /* [End of run() method] */

}
/* [End of class] */
