package com.example.overmind;

final class Constants {

    static short NUMBER_OF_NEURONS;
    static short NUMBER_OF_SYNAPSES;
    static short NUMBER_OF_DENDRITES;
    static float ABSOLUTE_REFRACTORY_PERIOD = (float)2.0;
    static char SYNAPSE_FILTER_ORDER = 64;
    static float SAMPLING_RATE = (float) 0.1;
    static char MAX_MULTIPLICATIONS = (char) (SYNAPSE_FILTER_ORDER * SAMPLING_RATE / ABSOLUTE_REFRACTORY_PERIOD + 1);

    static public void updateConstants(LocalNetwork thisDevice) {
        NUMBER_OF_NEURONS = thisDevice.numOfNeurons;
        NUMBER_OF_SYNAPSES = (short)(1024 - thisDevice.numOfSynapses);
        NUMBER_OF_DENDRITES = (short)(1024 - thisDevice.numOfDendrites);
    }

}
