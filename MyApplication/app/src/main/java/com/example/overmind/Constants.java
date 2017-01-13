package com.example.overmind;

public final class Constants {

    public final static short NUMBER_OF_EXC_SYNAPSES = 512;
    public final static short NUMBER_OF_INH_SYNAPSES = 512;
    public final static short NUMBER_OF_NEURONS = IpChecker.numOfNeurons;
    public final static float ABSOLUTE_REFRACTORY_PERIOD = (float)2.0;
    public final static char SYNAPSE_FILTER_ORDER = 64;
    public final static float SAMPLING_RATE = (float) 0.1;
    public final static char MAX_MULTIPLICATIONS = (int) (SYNAPSE_FILTER_ORDER * SAMPLING_RATE / ABSOLUTE_REFRACTORY_PERIOD + 1);

}
