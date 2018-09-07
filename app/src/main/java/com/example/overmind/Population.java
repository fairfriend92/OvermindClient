package com.example.overmind;

/**
 * Class containing information about the population of neurons living on any given terminal
 */

public class Population {
    public short numOfNeurons, numOfDendrites, numOfSynapses;

    // Which of the presynaptic terminals is stimulating this population
    public short presynTerminalsIndexes[];
}
