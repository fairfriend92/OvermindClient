package com.example.overmind;

import android.util.Log;

import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;

class KernelInitializer implements Runnable {

    private BlockingQueue<char[]> kernelInitQueue;
    private int currentPresynapticDevice;
    private static LocalNetwork thisDevice = new LocalNetwork();
    private byte[] inputSpikesBuffer;

    static final Object lock = new Object();

    // Static variable used to synchronize threads when the spikes need to be passed to KernelExecutor
    static private short threadsCounter = 0;

    // List made of the spikes arrays received from each presynaptic device
    // TODO At this point perhaps a double array would be more efficient?
    private static ArrayList<char[]> partialSynapseInput = new ArrayList<>();

    // Array obtained by joinining together each element of partialSynapseInput
    private char[] totalSynapseInput = new char[Constants.MAX_NUM_SYNAPSES * Constants.MAX_MULTIPLICATIONS];

    KernelInitializer(BlockingQueue<char[]> b, int i, LocalNetwork l, byte[] b1) {
        this.kernelInitQueue = b;
        this.currentPresynapticDevice = i;
        if (KernelInitializer.thisDevice == null) {
            KernelInitializer.thisDevice = l.get();
        } else {
            KernelInitializer.thisDevice.update(l);
        }

        this.inputSpikesBuffer = b1;
    }

    @Override
    public void run () {

        LocalNetwork presynapticNetwork = thisDevice.presynapticNodes.get(currentPresynapticDevice);

        int dataBytes = (presynapticNetwork.numOfNeurons % 8) == 0 ?
                (short) (presynapticNetwork.numOfNeurons / 8) : (short)(presynapticNetwork.numOfNeurons / 8 + 1);

        byte[] inputSpikes = new byte[dataBytes];
        System.arraycopy(inputSpikesBuffer, 0, inputSpikes, 0, dataBytes);

        char[] synapseInput = new char[presynapticNetwork.numOfNeurons * Constants.MAX_MULTIPLICATIONS];

        synchronized (lock) {

            if (partialSynapseInput.size() < thisDevice.presynapticNodes.size()) {
                for (int i = partialSynapseInput.size(); i < thisDevice.presynapticNodes.size(); i++) {
                    partialSynapseInput.add(null);
                }
            }

            if (partialSynapseInput.get(currentPresynapticDevice) != null) {
                synapseInput = partialSynapseInput.get(currentPresynapticDevice);
            }

        }

        /**
         * For each synapse of the presynapticNetwork compute the appropriate input
         */

        for (int indexI = 0; indexI < presynapticNetwork.numOfNeurons; indexI++) {

            // Calculate the byte to which the current indexI belongs
            short byteIndex = (short) (indexI / 8);

            // Check whether the indexI-th synapse has fired or not
            char bitValue = (char) ((inputSpikes[byteIndex] >> (indexI - byteIndex * 8)) & 1);

            // Increment the synapse inputs and advance them in the filter pipe only in case of firing
            for (char indexJ = Constants.MAX_MULTIPLICATIONS - 1; indexJ >= 1; indexJ--) {

                // Increment the input only if different from zero to begin with. Advance it if the synapse carries an action potential (bitValue = 1)
                synapseInput[indexJ + indexI * Constants.MAX_MULTIPLICATIONS] =
                        synapseInput[indexJ + indexI * Constants.MAX_MULTIPLICATIONS - bitValue] != 0 ? (char) (synapseInput[indexJ + indexI * Constants.MAX_MULTIPLICATIONS - bitValue] + 1) : 0;

            }

            // Make room for the new input in case bitValue = 1
            //TODO Can be further optimized
            if (bitValue == 1) {
                synapseInput[indexI * Constants.MAX_MULTIPLICATIONS] = 1;
            } else {
                synapseInput[indexI * Constants.MAX_MULTIPLICATIONS] = synapseInput[indexI * Constants.MAX_MULTIPLICATIONS] != 0 ?
                        (char)(synapseInput[indexI * Constants.MAX_MULTIPLICATIONS] + 1) : 0;
            }

        }

        /**
         * To create the total input put together the contributes of each thread. The computation needs to be serialized
         * thus it is contained in the following synchronized block
         */

        // TODO this piece of code can be further optimized by allowing each thread to copy its partial input in the
        // TODO result and leaving to the last thread the job of putting it in the queue

        synchronized (lock) {

            // TODO This way we cannot tell if we are serving the same synapse before the totalinput is complete...
            threadsCounter++;
            partialSynapseInput.set(currentPresynapticDevice, synapseInput);

            // Proceed only if all the partial results have been computed

            if (threadsCounter == thisDevice.presynapticNodes.size()) {

                threadsCounter = 0;

                // Put together the complete input

                short offset = 0;

                for (int i = 0; i < thisDevice.presynapticNodes.size(); i++) {
                    char[] tmpSynapseInput = partialSynapseInput.get(i);
                    System.arraycopy(tmpSynapseInput, 0, totalSynapseInput, offset, tmpSynapseInput.length);
                    offset += tmpSynapseInput.length;
                }

                // TODO Change blocking put in offer (?)

                try {
                    kernelInitQueue.put(totalSynapseInput);
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
