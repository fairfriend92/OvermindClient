package com.example.overmind;

/**
 * Created by root on 24/12/16.
 */

public class InitKernelWorkerThread implements Runnable {

    private byte[] presynapticSpikes = new byte[(Constants.NUMBER_OF_EXC_SYNAPSES + Constants.NUMBER_OF_INH_SYNAPSES) / 8];
    private char[] synapseInput = new char[(Constants.NUMBER_OF_EXC_SYNAPSES + Constants.NUMBER_OF_INH_SYNAPSES) * Constants.MAX_MULTIPLICATIONS];

    public InitKernelWorkerThread (byte[] s, char[] c) {
        this.presynapticSpikes = s;
        this.synapseInput = c;
    }

    @Override
    public void run () {
        //synapseInput = initializeSynapseKernel(presynapticSpikes, synapseInput);

        short byteIndex;
        char bitValue;
        for (int indexI = 0; indexI < Constants.NUMBER_OF_EXC_SYNAPSES + Constants.NUMBER_OF_INH_SYNAPSES; indexI++)
        {
            // Calculate the byte to which the current indexI belongs
            byteIndex = (short) (indexI / 8);
            // Check whether the indexI-th synapse has fired or not
            bitValue = (char)((presynapticSpikes[byteIndex] >> (indexI - byteIndex * 8)) & 1);
            // Increment the synapse inputs and advance them in the filter pipe only in case of firing
            for (char indexJ = 0; indexJ < Constants.MAX_MULTIPLICATIONS; indexJ++)
            {
                // Increment the input only if different from zero to begin with. Advance it if the synapse carries an action potential (bitValue = 1)
                synapseInput[indexJ + indexI * Constants.MAX_MULTIPLICATIONS] =
                        synapseInput[indexJ + indexI * Constants.MAX_MULTIPLICATIONS - bitValue] == 1 ? (char)(synapseInput[indexJ + indexI * Constants.MAX_MULTIPLICATIONS - bitValue] + 1) : 0;
            }
            // Make room for the new input in case bitValue = 1
            synapseInput[indexI * Constants.MAX_MULTIPLICATIONS] = (char)((1 - bitValue) * synapseInput[indexI * Constants.MAX_MULTIPLICATIONS]);
        }
    }

    //public native char[] initializeSynapseKernel(byte[] presynapticSpikes, char[] synapseInput);
}