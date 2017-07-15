package com.example.overmind;

import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

class InputCreator implements Runnable {

    private BlockingQueue<Input> kernelInitQueue;
    private BlockingQueue<char[]> inputCreatorQueue;
    static AtomicLong waitTime = new AtomicLong(0);
    private int numOfConnections = 0;
    private boolean[] connectionsServed;
    private List<Input> inputs = new ArrayList<>();

    InputCreator(BlockingQueue<Input> l, BlockingQueue<char[]> b) {

        kernelInitQueue = l;
        inputCreatorQueue = b;

    }

    @Override
    public void run() {

        // TODO Use local shutdown set by method
        while (!SimulationService.shutdown) {

            char[] totalSynapticInput = new char[Constants.MAX_NUM_SYNAPSES * Constants.MAX_MULTIPLICATIONS];
            Input firstInput = null;

            try {
                firstInput = kernelInitQueue.take();
            } catch (InterruptedException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("InputCreator", stackTrace);
                return;
            }

            assert firstInput != null;

            connectionsServed  = new boolean[numOfConnections];

            resizeArrays(firstInput.numOfConnections);
            if (!connectionsServed[firstInput.presynTerminalIndex]) {
                Log.e("InputCreator", " " + firstInput.presynTerminalIndex + " " + kernelInitQueue.remainingCapacity());
                inputs.set(firstInput.presynTerminalIndex, firstInput);
                connectionsServed[firstInput.presynTerminalIndex] = true;
            }

            Iterator<Input> iterator = kernelInitQueue.iterator();

            while (iterator.hasNext()) {
                Input currentInput = iterator.next();
                resizeArrays(currentInput.numOfConnections);
                if (!connectionsServed[currentInput.presynTerminalIndex]) {
                    Log.e("InputCreator", " " + currentInput.presynTerminalIndex + " " + kernelInitQueue.remainingCapacity());
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
                        inputs.set(i, new Input(new char[arrayLength], arrayLength, true, numOfConnections));
                    }
                } else
                    finished = true;
            }

            if (!inputIsNull) {
                try {
                    boolean inputSent = inputCreatorQueue.offer(totalSynapticInput, 8 * waitTime.get(), TimeUnit.NANOSECONDS);

                    if (inputSent)
                        Log.e("InputCreator", "input sent");
                    else
                        Log.e("InputCreator", "input NOT sent");

                    if (kernelInitQueue.remainingCapacity() < (kernelInitQueue.size() / 3)) {
                        Log.e("InputCreator", "Capacity 1/3rd of size: Must clear kernelInitQueue");
                        kernelInitQueue.clear();
                    }
                } catch (InterruptedException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("InputCreator", stackTrace);
                }
            }

        }

    }

    private void resizeArrays(int newNumOfConnections) {

        if (newNumOfConnections != numOfConnections) {

            for (int i = inputs.size(); i < newNumOfConnections; i++)
                inputs.add(null);

            boolean[] tmpArray = new boolean[newNumOfConnections];

            System.arraycopy(connectionsServed, 0, tmpArray, 0, numOfConnections < newNumOfConnections ? numOfConnections : newNumOfConnections);

            connectionsServed = tmpArray;

            numOfConnections = newNumOfConnections;

        }

    }

}

class Input {

    char[] synapticInput;
    int presynTerminalIndex;
    boolean inputIsEmpty;
    int numOfConnections;

    Input(char[] c, int i, boolean b, int i1) {

        synapticInput = c;
        presynTerminalIndex = i;
        inputIsEmpty = b;
        numOfConnections = i1;

    }

}
