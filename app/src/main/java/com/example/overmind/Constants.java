package com.example.overmind;

final class Constants {

    static short NUMBER_OF_NEURONS = 1;
    static short MAX_NUM_SYNAPSES = 1024;
    private final static float ABSOLUTE_REFRACTORY_PERIOD = (float)2.0;
    static int SYNAPSE_FILTER_ORDER = 16;
    private final static float SAMPLING_RATE = (float) 0.5;
    static final char MAX_MULTIPLICATIONS = (char) (SYNAPSE_FILTER_ORDER * SAMPLING_RATE / ABSOLUTE_REFRACTORY_PERIOD);

    static final int SERVER_PORT_TCP = 4195;
    static final int SERVER_PORT_UDP = 4196;
    static final int IPTOS_RELIABILITY = 0x04;
    static String SERVER_IP = MainActivity.serverIP;
}