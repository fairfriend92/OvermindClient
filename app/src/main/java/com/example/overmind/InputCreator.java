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
    private BlockingQueue<InputCreatorOutput> inputCreatorQueue;
    static AtomicLong waitTime = new AtomicLong(500000000);
    private int numOfConnections = 0;
    private boolean[] connectionsServed;
    private List<Input> inputs = new ArrayList<>();
    private BlockingQueue<Object> clockSignals;

    InputCreator(BlockingQueue<Input> l, BlockingQueue<InputCreatorOutput> b, BlockingQueue<Object> clockSignals)  {

        kernelInitQueue = l;
        inputCreatorQueue = b;
        this.clockSignals = clockSignals;

    }

    @Override
    public void run() {

        // Array holding flags which specify which connections have been served
        connectionsServed  = new boolean[numOfConnections];

        // TODO Use local shutdown set by method
        while (!SimulationService.shutdown) {

            // Array holding the complete input to be passed to KernelExecutor
            byte[] totalSynapticInput = new byte[Constants.NUMBER_OF_SYNAPSES * Constants.MAX_MULTIPLICATIONS];

            // Array holding the firing rates of the neurons of all the presynaptic terminals
            float[] totalFiringRates = new float[Constants.NUMBER_OF_SYNAPSES];

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

            // Whenever a new Input is retrieved, we must check whether the number of connections has
            // changed by inspecting it, and if that's the case the arrays must be resized
            // accordingly
            resizeArrays(firstInput.connectionsSize.length);

            // The retrieved Input is put in the list of inputs waiting to be put together, and the
            // respective flag is set
            inputs.set(firstInput.presynTerminalIndex, firstInput);
            connectionsServed[firstInput.presynTerminalIndex] = true;

            Log.d("InputCreator", "firstInput " + firstInput.presynTerminalIndex + " " + kernelInitQueue.remainingCapacity());

            Iterator<Input> iterator = kernelInitQueue.iterator();

            // Iterate over the queue of Inputs to be served
            while (iterator.hasNext()) {

                Input currentInput = iterator.next();

                // As before check whether in the meantime the number of connection has changed
                resizeArrays(currentInput.connectionsSize.length);

                // Proceed if the current input comes from a connection that has not been served
                if (!connectionsServed[currentInput.presynTerminalIndex]) {
                    Log.d("InputCreator", "currentInput " + currentInput.presynTerminalIndex + " " + kernelInitQueue.remainingCapacity());
                    inputs.set(currentInput.presynTerminalIndex, currentInput);
                    connectionsServed[currentInput.presynTerminalIndex] = true;
                    iterator.remove();
                }

            }

            boolean inputIsEmpty = true;
            int totalFiringRateLength = 0;

            // Iterate over the collection of inputs taken from the kernelInitialzer queue
            for (int i = 0; i < numOfConnections; i++) {

                Input currentInput = inputs.get(i);

                // If the input is null then the respective connection has not fired yet
                if (currentInput != null) {

                    // If all the inputs are empty, then there's no need to pass them to
                    // KernelExecutor. The flag signals whether they should be passed or not
                    inputIsEmpty &= false;

                    int arrayLength = currentInput.synapticInput.length;
                    int firingRateArrayLength = currentInput.firingRates.length;
                    totalFiringRateLength = currentInput.connectionsOffset[currentInput.connectionsOffset.length - 1];

                    /*
                    If the complete input we're building is made of inputs sampled at different
                    times, there's a possibility that not all of them may fit. Therefore, we must
                    check for the remaining space.
                      */

                    int firingRateOffset = (currentInput.connectionsOffset[i] - currentInput.connectionsSize[i]);
                    int offset = firingRateOffset * Constants.MAX_MULTIPLICATIONS;

                    if ((Constants.NUMBER_OF_SYNAPSES * Constants.MAX_MULTIPLICATIONS - offset) >= arrayLength) {
                        System.arraycopy(currentInput.synapticInput, 0, totalSynapticInput, offset, arrayLength);
                        System.arraycopy(currentInput.firingRates, 0, totalFiringRates, firingRateOffset, firingRateArrayLength);
                    }

                    // Now that the input has been processed the reference in the buffer should be null
                    inputs.set(i, null);

                }

            }

            if (!inputIsEmpty) {

                int totalSynapticInputLenght = totalFiringRateLength * Constants.MAX_MULTIPLICATIONS;

                // Resize totalSynapticInput
                byte[] resizedSynapticInput = new byte[totalSynapticInputLenght];
                System.arraycopy(totalSynapticInput, 0, resizedSynapticInput, 0, totalSynapticInputLenght);

                // Resize totalFiringRates
                float[] resizedFiringRates = new float[totalFiringRateLength];
                System.arraycopy(totalFiringRates, 0, resizedFiringRates, 0, totalFiringRateLength);

                try {

                    int kernelInitQueueSize = kernelInitQueue.size();
                    int kernelInitQueueRemainingCapacity = kernelInitQueue.remainingCapacity();
                    int waitFactor = kernelInitQueueRemainingCapacity / (kernelInitQueueSize + kernelInitQueueRemainingCapacity) * 8;

                    Object newClockSignal = clockSignals.poll(waitTime.get() * 8, TimeUnit.NANOSECONDS);

                    // newClockSignal != null
                    if (waitTime.get() != 0) {
                        boolean inputSent = inputCreatorQueue.offer(new InputCreatorOutput(resizedSynapticInput, resizedFiringRates), waitTime.get() * waitFactor, TimeUnit.NANOSECONDS);

                        if (inputSent)
                            Log.d("InputCreator", "input sent ");
                        else
                            Log.d("InputCreator", "input NOT sent");
                    } else {
                        Log.d("InputCreator", "clock null input NOT sent");
                    }

                    // In cas the pressure on the buffer is such that the capacity goes under the
                    // threshold, to prevent the application from stalling the queue is cleared
                    if (kernelInitQueue.remainingCapacity() < (kernelInitQueue.size() / 3)) {
                        Log.e("InputCreator", "Capacity 1/3rd of size: Must clear kernelInitQueue");
                        kernelInitQueue.clear();
                    }

                    // Reset the flags that remember which input has been served
                    connectionsServed = new boolean[numOfConnections];

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
    int[] connectionsSize;
    int[] connectionsOffset;
    float firingRates[];

    Input(byte[] c, int i, int[] i1, int[] i2, float[] f) {

        synapticInput = c;
        presynTerminalIndex = i;
        connectionsSize = i1;
        connectionsOffset = i2;
        firingRates = f;

    }

}

class InputCreatorOutput {
    byte[] resizedSynapticInput;
    float[] resizedFiringRates;

    InputCreatorOutput(byte[] resizedSynapticInput, float[] resizedFiringRates) {
        this.resizedSynapticInput = resizedSynapticInput;
        this.resizedFiringRates = resizedFiringRates;
    }

}
