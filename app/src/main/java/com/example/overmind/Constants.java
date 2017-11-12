package com.example.overmind;

final class Constants {

    static short NUMBER_OF_NEURONS = 1;
    static short NUMBER_OF_SYNAPSES = 1024;
    static boolean LATERAL_CONNECTIONS = false;

    private final static float ABSOLUTE_REFRACTORY_PERIOD = (float)2.0;
    static int SYNAPSE_FILTER_ORDER = 16;
    private final static float SAMPLING_RATE = (float) 0.5;
    static final char MAX_MULTIPLICATIONS = (char) (SYNAPSE_FILTER_ORDER * SAMPLING_RATE / ABSOLUTE_REFRACTORY_PERIOD);

    /* Connection constants */

    static final int SERVER_PORT_TCP = 4195;
    static final int SERVER_PORT_UDP = 4196;
    static int UDP_PORT = 4194;
    static final int IPTOS_RELIABILITY = 0x04;
    static String SERVER_IP;
}
