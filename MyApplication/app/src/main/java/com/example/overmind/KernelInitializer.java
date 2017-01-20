package com.example.overmind;

import android.util.Log;

import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;

/**
 * Runnable which acts as a worker thread for Kernel initialization. It produces totalSynapseInput
 * which is stored in the initKernelQueue and consumed by the ExecuteKernel worker thread.
 */

public class KernelInitializer implements Runnable {

    private BlockingQueue<char[]> kernelInitQueue;
    private String presynapticDeviceIP;
    private byte[] tmpSpikes = new byte[256];
    private LocalNetwork thisDevice;

    // Static variable used to synchronize threads when the spikes need to be passed to KernelExecutor
    static private short threadsCounter;

    // List made of the spikes arrays received from each presynaptic device
    private ArrayList<char[]> partialSynapseInput = new ArrayList<>();

    // Array obtained by linking together each element of partialSynapseInput
    static private char[] totalSynapseInput = new char[Constants.NUMBER_OF_DENDRITES * Constants.MAX_MULTIPLICATIONS];

    public KernelInitializer(BlockingQueue<char[]> b, String s, LocalNetwork l, byte[] b1) {
        this.kernelInitQueue = b;
        this.presynapticDeviceIP = s;
        this.thisDevice = l;
        this.tmpSpikes = b1;
    }

    @Override
    public void run () {

        short byteIndex;
        char bitValue;
        int connectionIndex;
        LocalNetwork presynapticNetwork = new LocalNetwork();

        /**
         * Use the IP contained in the UDP package header to identify the presynaptic device among the ones
         * stored in thisDevice
         */

        presynapticNetwork.ip = presynapticDeviceIP;

        // Use LocalNetwork own equals(Obj) method to find the connected device based on the IP
        connectionIndex = thisDevice.presynapticNodes.indexOf(presynapticNetwork);

        presynapticNetwork = thisDevice.presynapticNodes.get(connectionIndex);

        /**
         * Resize the array holding the presynaptic spikes by adjusting it to the number of neurons of presynapticNetwork
         */

        byte[] presynapticSpikes = new byte[(short)(presynapticNetwork.numOfNeurons / 8) + 1];
        System.arraycopy(tmpSpikes, 0, presynapticSpikes, 0, (short)(presynapticNetwork.numOfNeurons / 8) + 1);
        char[] synapseInput = new char[presynapticNetwork.numOfNeurons * Constants.MAX_MULTIPLICATIONS];

        /**
         * For each synapse of the presynapticNetwork compute the appropriate input
         */

        for (int indexI = 0; indexI < presynapticNetwork.numOfNeurons; indexI++) {

            // Calculate the byte to which the current indexI belongs
            byteIndex = (short) (indexI / 8);

            // Check whether the indexI-th synapse has fired or not
            bitValue = (char) ((presynapticSpikes[byteIndex] >> (indexI - byteIndex * 8)) & 1);

            // Increment the synapse inputs and advance them in the filter pipe only in case of firing
            for (char indexJ = 1; indexJ < Constants.MAX_MULTIPLICATIONS; indexJ++) {

                // Increment the input only if different from zero to begin with. Advance it if the synapse carries an action potential (bitValue = 1)
                synapseInput[indexJ + indexI * Constants.MAX_MULTIPLICATIONS] =
                        synapseInput[indexJ + indexI * Constants.MAX_MULTIPLICATIONS - bitValue] != 0 ? (char) (synapseInput[indexJ + indexI * Constants.MAX_MULTIPLICATIONS - bitValue] + 1) : 0;

            }

            // Make room for the new input in case bitValue = 1
            synapseInput[indexI * Constants.MAX_MULTIPLICATIONS] = bitValue == 1 ? 1 : synapseInput[indexI * Constants.MAX_MULTIPLICATIONS];

        }

        /**
         * To create the total input put together the contributes of each thread. The computation needs to be serialized
         * thus it is contained in the following synchronized block
         */

        synchronized (this) {

            threadsCounter++;
            partialSynapseInput.set(connectionIndex, synapseInput);

            // Proceed only if all the partial results have been computed

            if (threadsCounter == thisDevice.presynapticNodes.size()) {

                // Put together the complete input

                short offset = 0;

                for (int i = 0; i < thisDevice.presynapticNodes.size(); i++) {
                    char[] tmpSynapseInput = partialSynapseInput.get(i);
                    System.arraycopy(tmpSynapseInput, 0, totalSynapseInput, offset, tmpSynapseInput.length);
                    offset += tmpSynapseInput.length;
                }

                try {
                    kernelInitQueue.put(totalSynapseInput);
                } catch (InterruptedException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("KernelInitializer", stackTrace);
                }

                threadsCounter = 0;

            }

        }
        /* [End of synchronized block] */
    }
    /* [End of run() method] */
}
/* [End of class] */
