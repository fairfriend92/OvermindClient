package com.example.overmind;

import android.util.Log;

import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

class InputCreator implements Runnable {

    private LinkedBlockingQueue<Input> kernelInitQueue = new LinkedBlockingQueue<>(12);
    private BlockingQueue<char[]> inputCreatorQueue;

    InputCreator(LinkedBlockingQueue<Input> l, BlockingQueue<char[]> b) {

        kernelInitQueue = l;
        inputCreatorQueue = b;

    }

    @Override
    public void run() {

        // TODO Use local shutdown set by method
        while (!SimulationService.shutdown) {

            int connectionsServed = 0;
            char[] totalSynapticInput = new char[Constants.MAX_NUM_SYNAPSES * Constants.MAX_MULTIPLICATIONS];
            int offset = 0;

            while (connectionsServed < KernelInitializer.numOfConnections && !SimulationService.shutdown) {

                Iterator<Input> iterator = kernelInitQueue.iterator();
                boolean inputFound = false;

                while (iterator.hasNext() && !inputFound) {

                    Input currentInput = iterator.next();

                    if (currentInput.presynTerminalIndex == connectionsServed) {
                        inputFound = true;
                        System.arraycopy(currentInput.synapticInput, 0, totalSynapticInput, offset, currentInput.synapticInput.length);
                        offset += currentInput.synapticInput.length;
                        connectionsServed++;
                        iterator.remove();
                    }

                }

            }

            try {
                inputCreatorQueue.put(totalSynapticInput);
            } catch (InterruptedException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("InputCreator", stackTrace);
            }

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
