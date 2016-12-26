package com.example.overmind;

/**
 * Created by rodolfo on 08/12/16.
 */

public final class Constants {

    public final static int NUMBER_OF_EXC_SYNAPSES = 4;
    public final static int NUMBER_OF_INH_SYNAPSES = 4;
    public final static int NUMBER_OF_NEURONS = 8;
    public final static int ABSOLUTE_REFRACTORY_PERIOD = 2;
    public final static int SYNAPSE_FILTER_ORDER = 64;
    public final static float SAMPLING_RATE = (float) 0.1;
    public final static int MAX_MULTIPLICATIONS = (int) (SYNAPSE_FILTER_ORDER * SAMPLING_RATE / ABSOLUTE_REFRACTORY_PERIOD + 1);

}
