package com.example.overmind;

import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/*
The class retrieves the inputs elaborated by KernelInitializer and put them together before sending
them to KernelExecutor
 */

class InputCreator implements Runnable {

    private BlockingQueue<Input> kernelInitQueue;
    private BlockingQueue<byte[]> inputCreatorQueue;
    static AtomicLong waitTime = new AtomicLong(0);
    private int numOfConnections = 0;
    private boolean[] connectionsServed;
    private List<Input> inputs = new ArrayList<>();

    InputCreator(BlockingQueue<Input> l, BlockingQueue<byte[]> b) {

        kernelInitQueue = l;
        inputCreatorQueue = b;

    }

    @Override
    public void run() {

        // TODO Use local shutdown set by method
        while (!SimulationService.shutdown) {

            // Array holding the complete input to be passed to KernelExecutor
            byte[] totalSynapticInput = new byte[Constants.NUMBER_OF_SYNAPSES * Constants.MAX_MULTIPLICATIONS];

            // Object holding the first input in the kernelInitialzer queue
            Input firstInput;

            // The blocking "take" operation is used to prevent the thread from looping unnecessarily
            try {
                firstInput = kernelInitQueue.take();
            } catch (InterruptedException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("InputCreator", stackTrace);
                return;
            }

            // Array holding flags which specify which connections have been served
            connectionsServed  = new boolean[numOfConnections];

            // Whenever a new Input is retrieved, we must check whether the number of connections has
            // changed by inspecting it, and if that's the case the arrays must be resized
            // accordingly
            resizeArrays(firstInput.numOfConnections);

            // The retrieved Input is put in the list of inputs waiting to be put together, and the
            // respective flag is set
            inputs.set(firstInput.presynTerminalIndex, firstInput);
            connectionsServed[firstInput.presynTerminalIndex] = true;

            Log.e("InputCreator", "firstInput " + firstInput.presynTerminalIndex + " " + kernelInitQueue.remainingCapacity());

            Iterator<Input> iterator = kernelInitQueue.iterator();

            // Iterate over the queue of Inputs to be served
            while (iterator.hasNext()) {

                Input currentInput = iterator.next();

                // As before check whether in the meantime the number of connection has changed
                resizeArrays(currentInput.numOfConnections);

                // Proceed if the current input comes from a connection that has not been served
                if (!connectionsServed[currentInput.presynTerminalIndex]) {
                    Log.e("InputCreator", "currentInput " + currentInput.presynTerminalIndex + " " + kernelInitQueue.remainingCapacity());
                    inputs.set(currentInput.presynTerminalIndex, currentInput);
                    connectionsServed[currentInput.presynTerminalIndex] = true;
                    iterator.remove();
                }

            }

            int offset = 0;
            boolean finished = false, inputIsNull = true;

            Log.d("InputCreator", "numOfConnections " + numOfConnections);

            // Iterate over the collection of inputs taken from the kernelInitialzer queue
            for (int i = 0; i < numOfConnections && !finished; i++) {

                // If the input is null then the respective connection has not fired yet
                if (inputs.get(i) != null) {

                    Log.d("InputCreator", "input " + i + " is not null");

                    Input currentInput = inputs.get(i);

                    // If all the inputs are empty, then there's no need to pass them to
                    // KernelExecutor. The flag signals whether they should be passed or not
                    inputIsNull &= currentInput.inputIsEmpty;

                    int arrayLength = currentInput.synapticInput.length;

                    // If the complete input we're building is made of inputs sampled at different
                    // times, there's a possibility that not all of them may fit. Therefore, we must
                    // check for the remaining space.
                    if ((Constants.NUMBER_OF_SYNAPSES * Constants.MAX_MULTIPLICATIONS - offset) >= arrayLength) {
                        System.arraycopy(currentInput.synapticInput, 0, totalSynapticInput, offset, arrayLength);
                        offset += arrayLength;
                        inputs.set(i, new Input(new byte[arrayLength], arrayLength, true, numOfConnections));
                    }
                } else
                    finished = true;
            }

            // Resize totalSynapticInput
            byte[] resizedSynapticInput = new byte[offset];
            System.arraycopy(totalSynapticInput, 0, resizedSynapticInput, 0, offset);

            if (!inputIsNull) {
                try {
                    boolean inputSent = inputCreatorQueue.offer(resizedSynapticInput, 8 * waitTime.get(), TimeUnit.NANOSECONDS);

                    if (inputSent)
                        Log.e("InputCreator", "input sent");
                    else
                        Log.e("InputCreator", "input NOT sent");


                    // In cas the pressure on the buffer is such that the capacity goes under the
                    // threshold, to prevent the application from stalling the queue is cleared
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

    byte[] synapticInput;
    int presynTerminalIndex;
    boolean inputIsEmpty;
    int numOfConnections;

    Input(byte[] c, int i, boolean b, int i1) {

        synapticInput = c;
        presynTerminalIndex = i;
        inputIsEmpty = b;
        numOfConnections = i1;

    }

}
