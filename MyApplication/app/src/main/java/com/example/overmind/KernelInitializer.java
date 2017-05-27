package com.example.overmind;

import android.os.Process;
import android.util.Log;

import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

class KernelInitializer implements Runnable {

    private BlockingQueue<char[]> kernelInitQueue;
    private int currentPresynapticTerminal;
    private static short waitBeforeSending = 3;
    private static int counterOfSentPackets = 0;
    private static Terminal thisTerminal = new Terminal();
    private byte[] inputSpikesBuffer;

    static final Object lock = new Object();

    // Static variable used to synchronize threads when the spikes need to be passed to KernelExecutor
    static private short threadsCounter = 0;

    // List made of the spikes arrays received from each presynaptic terminal
    // TODO At this point perhaps a double array would be more efficient?
    private static ArrayList<char[]> partialSynapseInput = new ArrayList<>();

    // Array obtained by joinining together each element of partialSynapseInput
    private char[] totalSynapseInput = new char[Constants.MAX_NUM_SYNAPSES * Constants.MAX_MULTIPLICATIONS];

    KernelInitializer(BlockingQueue<char[]> b, int i, Terminal l, byte[] b1) {
        this.kernelInitQueue = b;
        this.currentPresynapticTerminal = i;
        if (KernelInitializer.thisTerminal == null) {
            KernelInitializer.thisTerminal = l.get();
        } else {
            KernelInitializer.thisTerminal.update(l);
        }

        this.inputSpikesBuffer = b1;
    }

    @Override
    public void run () {

        Terminal presynapticTerminal =  thisTerminal.presynapticTerminals.get(currentPresynapticTerminal);

        int dataBytes = (presynapticTerminal.numOfNeurons % 8) == 0 ?
                (short) (presynapticTerminal.numOfNeurons / 8) : (short)(presynapticTerminal.numOfNeurons / 8 + 1);

        byte[] inputSpikes = new byte[dataBytes];
        System.arraycopy(inputSpikesBuffer, 0, inputSpikes, 0, dataBytes);

        char[] synapseInput = new char[presynapticTerminal.numOfNeurons * Constants.MAX_MULTIPLICATIONS];

        /**
         * Add new empty elements to the list of partial inputs if the size is not sufficient to hold all
         * the presynaptic spikes
         */

        synchronized (lock) {

            // The size of partialSynapseInput must be changed dynamically
            if (partialSynapseInput.size() < (currentPresynapticTerminal + 1)) {
                for (int i = partialSynapseInput.size(); i < thisTerminal.presynapticTerminals.size(); i++) {
                    partialSynapseInput.add(null);
                }
            }

            // The runnable initializes the kernel at time n using the input at time n - 1, which
            // must be first retrieved from partialSynapseInput
            if (partialSynapseInput.get(currentPresynapticTerminal) != null) {
                char[] oldInput = partialSynapseInput.get(currentPresynapticTerminal);
                int length = synapseInput.length < oldInput.length ? synapseInput.length : oldInput.length;
                System.arraycopy(oldInput, 0, synapseInput, 0, length);
            }

        }

        /**
         * For each synapse of the presynapticTerminal compute the appropriate input
         */

        for (int indexI = 0; indexI < presynapticTerminal.numOfNeurons; indexI++) {

            // Calculate the byte to which the current indexI belongs
            short byteIndex = (short) (indexI / 8);

            // Check whether the indexI-th synapse has fired or not
            char bitValue = (char) ((inputSpikes[byteIndex] >> (indexI - byteIndex * 8)) & 1);

            // Increment the synapse inputs and advance them in the filter pipe only in case of firing
            for (char indexJ = (Constants.MAX_MULTIPLICATIONS - 1); indexJ >= 1; indexJ--) {

                // Increment the input only if different from zero to begin with. Advance it if the synapse carries an action potential (bitValue = 1)
                synapseInput[indexJ + indexI * Constants.MAX_MULTIPLICATIONS] =
                        (synapseInput[indexJ + indexI * Constants.MAX_MULTIPLICATIONS - bitValue] != 0) && (synapseInput[indexJ + indexI * Constants.MAX_MULTIPLICATIONS - bitValue] < Constants.SYNAPSE_FILTER_ORDER) ?
                                (char) (synapseInput[indexJ + indexI * Constants.MAX_MULTIPLICATIONS - bitValue] + 1) : 0;

            }

            // Make room for the new input in case bitValue = 1
            //TODO Can be further optimized
            if (bitValue == 1) {
                synapseInput[indexI * Constants.MAX_MULTIPLICATIONS] = 1;
            } else {
                synapseInput[indexI * Constants.MAX_MULTIPLICATIONS] =
                        (synapseInput[indexI * Constants.MAX_MULTIPLICATIONS] != 0) && (synapseInput[indexI * Constants.MAX_MULTIPLICATIONS] < Constants.SYNAPSE_FILTER_ORDER) ?
                        (char)(synapseInput[indexI * Constants.MAX_MULTIPLICATIONS] + 1) : 0;
            }

        }

        /**
         * To create the total input put together the contributes of each thread. The computation needs to be serialized
         * thus it is contained in the following synchronized block
         */

        synchronized (lock) {

            // TODO This way we cannot tell if we are serving the same synapse before the totalinput is complete...
            threadsCounter++;
            partialSynapseInput.set(currentPresynapticTerminal, synapseInput);

            // Proceed only if all the partial results have been computed

            if (threadsCounter == thisTerminal.presynapticTerminals.size()) {

                threadsCounter = 0;

                // Put together the complete input

                short offset = 0;

                for (int i = 0; i < partialSynapseInput.size(); i++) {
                    char[] tmpSynapseInput = partialSynapseInput.get(i);
                    System.arraycopy(tmpSynapseInput, 0, totalSynapseInput, offset, tmpSynapseInput.length);
                    offset += tmpSynapseInput.length;
                }

                // Use a counter to count the number of times a spikes packet has been accepted by the KernelExecutor queue.
                // When the counter reaches a certain threshold, decrease the time waited before dropping the packet altogether.
                // Every time a packet id dropped, increase the time by 3 ms.

                // TODO Perhaps to improve algorithm look up branch prediction algorithms
                try {
                    boolean packetDropped = !kernelInitQueue.offer(totalSynapseInput, waitBeforeSending, TimeUnit.MILLISECONDS);
                    if (packetDropped) {
                        waitBeforeSending += 3;
                        counterOfSentPackets = 0;
                        //Log.e("KernelInitializer", "dropped packet " + waitBeforeSending);
                    } else {
                        counterOfSentPackets++;
                        if (counterOfSentPackets > 1024 && waitBeforeSending > 3) {
                            waitBeforeSending--;
                            counterOfSentPackets = 0;
                        }
                    }
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
