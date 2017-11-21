package com.example.overmind;

final class Constants {

    /* Neural netowrk constants */

    static short NUMBER_OF_NEURONS = 1;
    static short NUMBER_OF_SYNAPSES = 1024;
    static boolean LATERAL_CONNECTIONS = false;
    static int INDEX_OF_LATERAL_CONN = -1;

    /* Neuronal dynamics constants */

    private final static float ABSOLUTE_REFRACTORY_PERIOD = (float)2.0;
    static int SYNAPSE_FILTER_ORDER = 16;
    private final static float SAMPLING_RATE = (float) 0.5;
    static final char MAX_MULTIPLICATIONS = (char) (SYNAPSE_FILTER_ORDER * SAMPLING_RATE / ABSOLUTE_REFRACTORY_PERIOD);
    static float MEAN_RATE_INCREMENT = 0.1f; // This is the inverse of the number of samples used to compute the mean firing rate

    /* Connection constants */

    static final int SERVER_PORT_TCP = 4195;
    static final int SERVER_PORT_UDP = 4196;
    static int UDP_PORT = 4194;
    static final int IPTOS_RELIABILITY = 0x04;
    static String SERVER_IP;
}
