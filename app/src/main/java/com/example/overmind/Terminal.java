package com.example.overmind;

import java.io.Serializable;
import java.util.ArrayList;

public class Terminal implements Serializable {
    // These are total numbers for all the populations
    public short numOfNeurons, numOfDendrites, numOfSynapses;

    // Populations that live on this terminal
    public Population[] populations = new Population[0];

    public String serverIP;
    public String ip;
    public int natPort;
    public ArrayList<Terminal> presynapticTerminals = new ArrayList<>(); // TODO: Arrays and collections shouldn't be created here.
    public ArrayList<Terminal> postsynapticTerminals = new ArrayList<>();
    public byte[] newWeights = new byte[0];
    public int[] newWeightsIndexes = new int[0];
    public byte[] updateWeightsFlags = new byte[0];

    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != this.getClass()) { return false; }
        Terminal compare = (Terminal) obj;
        if (!compare.ip.equals(serverIP))
            return (compare.ip.equals(this.ip) & compare.natPort == this.natPort);
        else
            return compare.ip.equals(this.ip);
    }
}
