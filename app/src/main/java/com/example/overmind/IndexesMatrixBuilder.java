package com.example.overmind;

import android.util.Log;
import java.util.ArrayList;

/**
 * Simple class that groups together the two matrices to pass them to a different class.
 */

class IndexesMatrices {
    int[][] indexesMatrix;
    int[][] neuronsMatrix;

    IndexesMatrices(int[][] indexesMatrix, int[][] neuronsMatrix) {
        this.indexesMatrix = indexesMatrix;
        this.neuronsMatrix = neuronsMatrix;
    }
}

/**
 * Class that contains the necessary methods to build the matrix of the synaptic indexes and that
 * of the neuron indexes. The entries of the first tell which input the synapse is connected to.
 * The entries of the second one tells to which neuron the synapse belongs.
 *
 * Consequently both the matrix have as many entries as synapses.
 */

class IndexesMatrixBuilder {
    private Terminal terminal = null;
    private int[] tmpIndexes, tmpNeurons;

    /**
     * Build a matrix whose rows are collections of indexes which give synapses access to the right
     * inputs. The indexes are repeated for every neuron, even if the neurons of the same population
     * have the same synapses (but different weights) and therefore the same inputs.
     *
     * Additionally create a matrix with as many elements as synapses in which every entry tells to
     * which neuron the respective synapse belongs.
     *
     * @param terminal This physical terminal
     * @return A matrix of the indexes that the synapses use to access the right input
     */

    IndexesMatrices buildIndexesMatrix(Terminal terminal) {
        this.terminal = terminal;
        Population[][] popsMatrix = terminal.popsMatrix;

        int[][] indexesMatrix = new int[popsMatrix.length][];
        int[][] neuronsMatrix = new int[popsMatrix.length][];

        // Iterate over the layers
        for (int i = 0; i < popsMatrix.length; i++) {
            int rowLength = 0;
            indexesMatrix[i] = new int[0];
            neuronsMatrix[i] = new int[0];

            // Iterate over the populations belonging to a given layer
            for (int j = 0; j < popsMatrix[i].length; j++) {
                // Get the needed info about the inputs of population (i, j)
                ArrayList<int[]> inputsInfo = getInputsInfo(i, j, popsMatrix);

                // Build the collection of indexes for population (i, j)
                fillArrays(inputsInfo, popsMatrix[i][j].numOfNeurons);

                // Update the length of the collection of indexes for this layer
                rowLength += tmpIndexes.length;

                /*
                Copy the collection of indexes for population j of layer i into the collection of
                all the indexes of layer i
                 */

                int[] newIndexes = new int[rowLength];
                int[] newNeurons = new int[rowLength];

                System.arraycopy(indexesMatrix[i], 0, newIndexes, 0, indexesMatrix[i].length);
                System.arraycopy(tmpIndexes, 0, newIndexes, indexesMatrix[i].length, tmpIndexes.length);
                indexesMatrix[i] = newIndexes;

                System.arraycopy(neuronsMatrix[i], 0, newNeurons, 0, neuronsMatrix[i].length);
                System.arraycopy(tmpNeurons, 0, newNeurons, neuronsMatrix[i].length, tmpNeurons.length);
                neuronsMatrix[i] = newNeurons;
            }
        }

        // For debugging purposes print on the terminal all the indexes sequentially
        //printMatrix(indexesMatrix);
        //printMatrix(neuronsMatrix);

        return new IndexesMatrices(indexesMatrix, neuronsMatrix);
    }

    /**
     * Prints on the terminal all the indexes sequentially. For debugging purposes.
     * @param matrix The collections of all the indexes
     */

    private void printMatrix(int[][] matrix) {
        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix[i].length; j++) {
                Log.d("IndexesMatrixBuilder", "Element " + matrix[i][j] + " layer " + i + " synapse " + j);
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
     * @param row The row of the population whose inputs must be inspected
     * @param column The column
     * @param matrix The matrix of the populations
     * @return  Array containing couples of integer, the first one of which is the numbers of
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
     * Also, create an array whose elements are neurons index. For each synapse an element is created.
     * The value of said element tells to which neuron the synapse belongs.
     *
     * @param inputsInfo: An array of couples of integer, representing respectively the number of
     *                  neurons and the offset of a given input.
     * @param numOfNeurons: The number of neurons of the population for which the array is being
     *                    built.
     */

    private void fillArrays(ArrayList<int[]> inputsInfo, int numOfNeurons) {
        int[] indexes = new int[0];

        // This indexes count all the synapses that have been considered
        int j = 0;

        // Iterate over the inputs of the population
        for (int[] info : inputsInfo) {
            int numOfSynapses = info[0], offset = info[1];

            // Array which is going to contain only the indexes for the current input
            int[] tmpIndexes = new int[numOfSynapses];

            // Populate the array for the current input
            for (int i = 0; i < numOfSynapses; i++) { tmpIndexes[i] = i + offset; }

            j += numOfSynapses;

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
        necessary to cover the connections of all the neurons of the population.

        Also populate the array of neurons with the index of the neuron to which the respective
        synapse belongs
         */

        tmpIndexes = new int[indexes.length * numOfNeurons];
        tmpNeurons = new int[indexes.length * numOfNeurons];
        for (int i = 0; i < numOfNeurons; i++) {
            System.arraycopy(indexes, 0, tmpIndexes, i * indexes.length, indexes.length);
            for (j = i * indexes.length; j < (i + 1) * indexes.length; j++)
                tmpNeurons[j] = i;
        }

    }

}
