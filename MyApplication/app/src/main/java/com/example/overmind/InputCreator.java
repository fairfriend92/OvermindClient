package com.example.overmind;

import android.util.Log;

import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

class InputCreator implements Runnable {

    private LinkedBlockingDeque<Input> kernelInitQueue = new LinkedBlockingDeque<>(12);
    private BlockingQueue<char[]> inputCreatorQueue;

    InputCreator(LinkedBlockingDeque<Input> l, BlockingQueue<char[]> b) {

        kernelInitQueue = l;
        inputCreatorQueue = b;

    }

    @Override
    public void run() {

        int connectionsServed = 0;
        int numOfConnections;
        char[] totalSynapticInput =  new char[Constants.MAX_NUM_SYNAPSES * Constants.MAX_MULTIPLICATIONS];
        int offset = 0;

        while (connectionsServed < (numOfConnections = KernelInitializer.numOfConnections)) {

            Iterator<Input> iterator = kernelInitQueue.iterator();
            boolean inputFound = false;

            while (iterator.hasNext() || inputFound) {

                Input currentInput = iterator.next();

                if (currentInput.presynTerminalIndex == numOfConnections) {
                    inputFound = true;
                    System.arraycopy(currentInput.synapticInput, 0, totalSynapticInput, offset, currentInput.synapticInput.length);
                    offset += currentInput.synapticInput.length;
                    connectionsServed++;
                    iterator.remove();
                }

            }

        }

        offset = 0;

        try {
            inputCreatorQueue.put(totalSynapticInput);
        } catch (InterruptedException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("InputCreator", stackTrace);
        }

    }

}

class Input {

    char[] synapticInput;
    int presynTerminalIndex;

    Input(char[] c, int i) {

        synapticInput = c;
        presynTerminalIndex = i;

    }

}
