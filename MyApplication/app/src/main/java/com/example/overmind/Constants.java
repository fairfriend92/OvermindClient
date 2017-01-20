package com.example.overmind;

final class Constants {

    static short NUMBER_OF_NEURONS;
    static short NUMBER_OF_DENDRITES;
    private final static float ABSOLUTE_REFRACTORY_PERIOD = (float)2.0;
    private final static char SYNAPSE_FILTER_ORDER = 64;
    private final static float SAMPLING_RATE = (float) 0.1;
    static final char MAX_MULTIPLICATIONS = (char) (SYNAPSE_FILTER_ORDER * SAMPLING_RATE / ABSOLUTE_REFRACTORY_PERIOD + 1);

    static void updateConstants(LocalNetwork thisDevice) {
        NUMBER_OF_NEURONS = thisDevice.numOfNeurons;
        NUMBER_OF_DENDRITES = (short)(1024 - thisDevice.numOfDendrites);
    }

}
