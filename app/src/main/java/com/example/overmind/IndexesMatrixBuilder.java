package com.example.overmind;

import android.util.Log;

import java.util.ArrayList;

/**
 * This class takes care of building the matrix that stores the indexes that allow the populations
 * neurons to access the right inputs
 */

class IndexesMatrixBuilder {
    private Terminal terminal = null;

    /*
    Build a matrix whose rows are collections of indexes which give synapses access to the right
    inputs. The indexes are repeated for every neuron, even if the neurons of the same population
    have the same synapses (but different weights) and therefore the same inputs
     */

    int[][] buildMatrix(Population[][] popsMatrix) {
        terminal = SimulationService.thisTerminal;

        int[][] indexesMatrix = new int[popsMatrix.length][];

        // Iterate over the layers
        for (int i = 0; i < popsMatrix.length; i++) {
            int rowLength = 0;
            indexesMatrix[i] = new int[0];

            // Iterate over the populations belonging to a given layer
            for (int j = 0; j < popsMatrix[i].length; j++) {
                // Get the needed info about the inputs of population (i, j)
                ArrayList<int[]> inputsInfo = getInputsInfo(i, j, popsMatrix);

                // Build the collection of indexes for population (i, j)
                int[] tmpIndexes =  fillIndexesArray(inputsInfo, popsMatrix[i][j].numOfNeurons);

                // Update the length of the collection of indexes for this layer
                rowLength += tmpIndexes.length;

                /*
                Copy the collection of indexes for population j of layer i into the collection of
                all the indexes of layer i
                 */

                int[] newIndexes = new int[rowLength];
                System.arraycopy(indexesMatrix[i], 0, newIndexes, 0, indexesMatrix[i].length);
                System.arraycopy(tmpIndexes, 0, newIndexes, indexesMatrix[i].length, tmpIndexes.length);
                indexesMatrix[i] = newIndexes;
            }
        }

        // For debugging purposes print on the terminal all the indexes sequentially
        printMatrix(indexesMatrix);
        return indexesMatrix;
    }

    /**
     * Prints on the terminal all the indexes sequentially. For debugging purposes.
     * @param indexesMatrix: The collections of all the indexes
     */

    private void printMatrix(int[][] indexesMatrix) {
        for (int i = 0; i < indexesMatrix.length; i++) {
            for (int j = 0; j < indexesMatrix[i].length; j++) {
                Log.d("IndexesMatrixBuilder", indexesMatrix[i][j] + " " + i + " " + j);
            }
            Log.d(" IndexesMatrixBuilder", " ");
        }
    }

    /**
     * Get the information about the inputs of a given population needed to build the collection of
     * indexes. These information are the number of neurons of the input and its offset, that is to
     * say how many neurons come before those of the inputs.
     *
     * The input neurons are to be stored sequentially in a memory buffer, following the order with
     * which the populations appear in the matrix, from left to right and from up to down.
     * Presynaptic terminals are going to be included as well at the beginning of the memory buffer
     *
     * @param row: The row of the population whose inputs must be inspected
     * @param column: The column
     * @param matrix: The matrix of the populations
     * @return: Array containing couples of integer, the first one of which is the numbers of
     * neurons of the inputs and the second one their offsets
     */

    private ArrayList<int[]> getInputsInfo(int row, int column, Population[][] matrix) {
        Population pop = matrix[row][column];
        ArrayList<int[]> inputsInfo = new ArrayList<>();
        int offset = 0;

        // Is any of the inputs a presynaptic terminal?
        for (Terminal presynTerminal : terminal.presynapticTerminals) {
            if (pop.inputIndexes.contains(presynTerminal.id)) {
                int[] info = new int[2];
                info[0] = presynTerminal.numOfNeurons;
                info[1] = offset;
                inputsInfo.add(info);
            }

            // Even if an input was not found the offset should be incremented because an input may
            // be found later and the populations as well as the synaptic terminals are stored
            // sequentially one after the other
            offset += presynTerminal.numOfNeurons;
        }

        // Is any of the inputs a population?
        for (int i = 0; i < row; i++) {
            for (int j = 0; j < matrix[i].length; j++) {
                if (pop.inputIndexes.contains(matrix[i][j].id)) {
                    int[] info = new int[2];
                    info[0] = matrix[i][j].numOfNeurons;
                    info[1] = offset;
                    inputsInfo.add(info);
                }

                offset += matrix[i][j].numOfNeurons;
            }
        }
        return inputsInfo;
    }

    /**
     * Create an array of indexes. Each index is a number which tells the position of the respective
     * input in the memory buffer. Every neuron of a given population has the same array of indexes,
     * however the indexes are copied repeatedly until they span all the inputs of all the neurons
     * of a population.
     *
     * This is made necessary by the fact that the OpenCL kernel are not aware of how neurons are
     * grouped into populations.
     *
     * @param inputsInfo: An array of couples of integer, representing respectively the number of
     *                  neurons and the offset of a given input.
     * @param numOfNeurons: The number of neurons of the population for which the array is being
     *                    built.
     * @return: The array of indexes.
     */

    private int[] fillIndexesArray(ArrayList<int[]> inputsInfo, int numOfNeurons) {
        int[] indexes = new int[0];

        // This indexes count all the synapses that have been considered
        int j = 0;

        // Iterate over the inputs of the population
        for (int[] info : inputsInfo) {
            int numOfSynapses = info[0], offset = info[1];

            // Array which is going to contain only the indexes for the current inpu
            int[] tmpIndexes = new int[numOfSynapses];

            // Populate the array for the current inpu
            for (int i = offset; i < offset + numOfSynapses; i++, j++) {
                tmpIndexes[i - offset] = i;
            }

            /*
            Put together all the arrays that have been built up until this point for the population
             */

            int[] newIndexes = new int[j];
            System.arraycopy(indexes, 0, newIndexes, 0, indexes.length);
            System.arraycopy(tmpIndexes, 0, newIndexes, indexes.length, tmpIndexes.length);
            indexes = newIndexes;
        }

        /*
        Once the array of indexes has been created for one neuron, copy it as many times as
        necessary to cover the connections of all the neurons of the population
         */

        int[] repeatedIndexes = new int[indexes.length * numOfNeurons];
        for (int i = 0; i < numOfNeurons; i++) {
            System.arraycopy(indexes, 0, repeatedIndexes, i * indexes.length, indexes.length);
        }
        return repeatedIndexes;
    }

}
