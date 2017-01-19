package com.example.overmind;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;

/**
 * Runnable which acts as a worker thread for Kernel initialization. It produces the synapseInput,
 * which is stored in the initKernelQueue and consumed by the ExecuteKernel worker thread.
 */

public class KernelInitializer implements Runnable {

    private BlockingQueue<char[]> kernelInitQueue;
    private short thisThread;
    private short threadsCounter;

    // TODO is it a problem if this variable is static?

    static private LocalNetwork thisDevice;

    public KernelInitializer(BlockingQueue<char[]> b, short s, LocalNetwork l) {
        this.kernelInitQueue = b;
        this.thisThread = s;
        KernelInitializer.thisDevice = l;
    }

    static private char[] totalSynapseInput = new char[thisDevice.numOfDendrites * Constants.MAX_MULTIPLICATIONS];
    static private ArrayList<char[]> partialSynapseInput = new ArrayList<>();

    @Override
    public void run () {
        short byteIndex;
        char bitValue;

        LocalNetwork presynapticNetwork = thisDevice.presynapticNodes.get(thisThread);
        DatagramSocket dendriteSocket = null;
        DatagramPacket dendritePacket = null;

        byte[] presynapticSpikes = new byte[presynapticNetwork.numOfNeurons/ 8];
        char[] synapseInput = new char[presynapticNetwork.numOfNeurons * Constants.MAX_MULTIPLICATIONS];


        try {
            dendriteSocket = new DatagramSocket(4194);
            // TODO If for instance numOfNeurons = 1 length of Packet is incorrect. Possibly cast to int and add 1?
            dendritePacket =  new DatagramPacket(presynapticSpikes, presynapticNetwork.numOfNeurons / 8);
        } catch (SocketException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("KernelInitializer", stackTrace);
        }

        assert dendritePacket != null;

        while (!SimulationService.shutdown) {

            try {
                dendriteSocket.receive(dendritePacket);
                presynapticSpikes = dendritePacket.getData();
            } catch (IOException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("KernelInitializer", stackTrace);
            }

            // TODO UDP has a buffer so we shouldn't worry about writing our own, nonetheless it's better to check, especially for potential issues regarding its size

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

            synchronized (this) {
                threadsCounter++;

                partialSynapseInput.set(thisThread, synapseInput);

                if (threadsCounter == thisDevice.presynapticNodes.size()) {

                    for (int i = 0; i < thisDevice.presynapticNodes.size(); i++) {
                        char[] tmpSynapseInput = partialSynapseInput.get(i);
                        System.arraycopy(tmpSynapseInput, 0, totalSynapseInput, totalSynapseInput.length, tmpSynapseInput.length);
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
        }

    }
}
