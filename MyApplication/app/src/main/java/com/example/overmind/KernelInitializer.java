package com.example.overmind;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;

class KernelInitializer implements Runnable {

    // Queue that stores the inputs to be sent to the ernelExecutor thread
    private BlockingQueue<char[]> kernelInitQueue;

    // IP of the presynaptic terminal whose output must be processed
    private String presynTerminalIP;

    // Local variable
    private static List<Terminal> presynapticTerminals = Collections.synchronizedList(new ArrayList<Terminal>());

    // Buffer to hold the incoming spikes until the length of the synaptic input array has not been
    // figured out
    private byte[] inputSpikesBuffer;

    // Flag which signals whenever new info regarding the terminal have been received from the TCP
    // stream. The local variable is necessary to avoid further thread synchronization
    // The flag is initially set true to account for the first updated terminal ever received
    // from the server
    private static boolean terminalWasUpdated = true;
    private boolean terminalWasUpdated_local = false;

    // Object used for synchronization
    private static final Object lock = new Object();

    // Static variable used to synchronize threads when the spikes need to be passed to KernelExecutor
    static private short threadsCounter = 0;

    // Double array with one dimension representing the presynaptic terminals and the other the
    // input of a certain terminal
    private static char[][] synapticInputCollection;

    // An array used to remember which connections have already been served before putting together
    // the input
    private static boolean[] connectionWasServed;

    // Array obtained by joinining together each element of synapticInputCollection
    private char[] totalSynapticInput = new char[Constants.MAX_NUM_SYNAPSES * Constants.MAX_MULTIPLICATIONS];

    KernelInitializer(BlockingQueue<char[]> b, String s, byte[] b1, boolean b2 ) {
        this.kernelInitQueue = b;
        this.presynTerminalIP = s;
        this.inputSpikesBuffer = b1;
        this.terminalWasUpdated_local = b2;
    }

    @Override
    public void run () {

        /*
        When we are putting together a new input there is some initialization to do...
        */

        synchronized (lock) {

            terminalWasUpdated = terminalWasUpdated || terminalWasUpdated_local;

            if (threadsCounter == 0)  {

                // If the infomation of the terminal have been updated...
                if (terminalWasUpdated) {

                    // Create anew the ArrayList storing the presynaptic Terminals
                    Collections.copy(presynapticTerminals, SimulationService.thisTerminal.get().presynapticTerminals);

                    // Create the array storing the kernel input derived from the spikes produced
                    // by each presynaptic Terminal
                    synapticInputCollection = new char[presynapticTerminals.size()][4096];

                    // Reset the flag
                    terminalWasUpdated = false;
                }

                // Create a new array storing the flags that tell which connection has been served
                connectionWasServed = new boolean[presynapticTerminals.size()];

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
            // it since we must first process all the inputs of the preceeding iteration
            if (connectionWasServed[presynTerminalIndex]) {
                Log.e("KernelInitializer", "Terminal with ip " + presynTerminalIP + " has already been served");
                return;
            } else
                connectionWasServed[presynTerminalIndex] = true;

            threadsCounter++;
            synapticInputCollection[presynTerminalIndex] = synapticInput;

            // Proceed only if all the partial results have been computed
            if (threadsCounter == presynapticTerminals.size()) {

                threadsCounter = 0;

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
