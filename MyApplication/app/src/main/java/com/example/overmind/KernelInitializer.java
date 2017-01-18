package com.example.overmind;

import android.util.Log;

import java.util.concurrent.BlockingQueue;

/**
 * Runnable which acts as a worker thread for Kernel initialization. It produces the synapticInput,
 * which is stored in the initKernelQueue and consumed by the ExecuteKernel worker thread.
 */

public class KernelInitializer implements Runnable {

    private BlockingQueue<byte[]> dataReceiverQueue;
    private BlockingQueue<char[]> kernelInitQueue;
    private byte[] presynapticSpikes = new byte[(Constants.NUMBER_OF_SYNAPSES) / 8];
    private char[] synapseInput = new char[(Constants.NUMBER_OF_SYNAPSES) * Constants.MAX_MULTIPLICATIONS];

    public KernelInitializer(BlockingQueue<byte[]> b, BlockingQueue<char[]> b1) {
        this.dataReceiverQueue = b;
        this.kernelInitQueue = b1;
    }

    @Override
    public void run () {
        short byteIndex;
        char bitValue;

        while (!SimulationService.shutdown) {

            try {
                presynapticSpikes = dataReceiverQueue.take();
                //Log.d("DataReceiver", "DR " + SimulationService.bytesToHex(presynapticSpikes));
            } catch (InterruptedException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("SimulationService", stackTrace);
            }

            for (int indexI = 0; indexI < Constants.NUMBER_OF_SYNAPSES; indexI++) {
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

            try {
                kernelInitQueue.put(synapseInput);
            } catch (InterruptedException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("SimulationService", stackTrace);
            }
        }

    }
}
