package com.example.overmind;

import java.io.Serializable;
import java.util.ArrayList;

class LocalNetwork implements Serializable {
    short numOfNeurons, numOfDendrites, numOfSynapses;
    String ip;
    ArrayList<LocalNetwork> presynapticNodes;
    ArrayList<LocalNetwork> postsynapticNodes;

    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != this.getClass()) { return false; }
        LocalNetwork compare = (LocalNetwork) obj;
        return compare.ip.equals(ip);
    }
}
