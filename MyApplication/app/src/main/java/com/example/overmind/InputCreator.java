package com.example.overmind;

import android.util.Log;

import java.util.Iterator;
import java.util.concurrent.BlockingQueue;

class InputCreator implements Runnable {

    private BlockingQueue<Input> kernelInitQueue;
    private BlockingQueue<char[]> inputCreatorQueue;

    InputCreator(BlockingQueue<Input> l, BlockingQueue<char[]> b) {

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

                if (!inputFound && kernelInitQueue.remainingCapacity() < kernelInitQueue.size() / 3) {
                    Log.e("InputCreator", "Queue is almost full, last input is disregarded");
                    kernelInitQueue.remove();
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
