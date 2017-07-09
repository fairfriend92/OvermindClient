package com.example.overmind;

import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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

        int numOfConnections = 1;

        List<Input> inputs = new ArrayList<>();

        // TODO Use local shutdown set by method
        while (!SimulationService.shutdown) {

            char[] totalSynapticInput = new char[Constants.MAX_NUM_SYNAPSES * Constants.MAX_MULTIPLICATIONS];
            numOfConnections = KernelInitializer.numOfConnections > numOfConnections ?
                    KernelInitializer.numOfConnections : numOfConnections;
            for (int i = inputs.size(); i < numOfConnections; i++)
                inputs.add(null);
            boolean[] connectionsServed = new boolean[numOfConnections];

            try {
                Input firstInput = kernelInitQueue.take();
                if (!connectionsServed[firstInput.presynTerminalIndex]) {
                    Log.e("InputCreator", " " + firstInput.presynTerminalIndex);
                    inputs.set(firstInput.presynTerminalIndex, firstInput);
                    connectionsServed[firstInput.presynTerminalIndex] = true;
                }
            } catch (InterruptedException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("InputCreator", stackTrace);
            }

            Iterator<Input> iterator = kernelInitQueue.iterator();

            while (iterator.hasNext()) {
                Input currentInput = iterator.next();
                if (!connectionsServed[currentInput.presynTerminalIndex]) {
                    Log.e("InputCreator", " " + currentInput.presynTerminalIndex);
                    inputs.set(currentInput.presynTerminalIndex, currentInput);
                    connectionsServed[currentInput.presynTerminalIndex] = true;
                    iterator.remove();
                }
            }

            int offset = 0;
            boolean finished = false, inputIsNull = true;

            for (int i = 0; i < numOfConnections && !finished; i++) {
                if (inputs.get(i) != null) {
                    Input currentInput = inputs.get(i);
                    inputIsNull &= currentInput.inputIsEmpty;
                    int arrayLength = currentInput.synapticInput.length;
                    if ((4096 - offset) >= arrayLength) {
                        System.arraycopy(currentInput.synapticInput, 0, totalSynapticInput, offset, arrayLength);
                        offset += arrayLength;
                        inputs.set(i, new Input(new char[arrayLength], arrayLength, true));
                    }
                } else
                    finished = true;
            }

            if (!inputIsNull) {
                Log.e("InputCreator", "input sent");
                try {
                    inputCreatorQueue.put(totalSynapticInput);
                } catch (InterruptedException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("InputCreator", stackTrace);
                }
            }

        }

    }

}

class Input {

    char[] synapticInput;
    int presynTerminalIndex;
    boolean inputIsEmpty;

    Input(char[] c, int i, boolean b) {

        synapticInput = c;
        presynTerminalIndex = i;
        inputIsEmpty = b;

    }

}
