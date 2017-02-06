package com.example.overmind;

final class Constants {

    static short NUMBER_OF_NEURONS = 1;
    final static short MAX_NUM_SYNAPSES = 1024;
    private final static float ABSOLUTE_REFRACTORY_PERIOD = (float)2.0;
    private final static char SYNAPSE_FILTER_ORDER = 64;
    // TODO Change the sampling rate perhaps?
    private final static float SAMPLING_RATE = (float) 0.1;
    static final char MAX_MULTIPLICATIONS = (char) (SYNAPSE_FILTER_ORDER * SAMPLING_RATE / ABSOLUTE_REFRACTORY_PERIOD + 1);

}
