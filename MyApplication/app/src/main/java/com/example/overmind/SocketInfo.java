package com.example.overmind;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class SocketInfo {

    Socket socket;
    ObjectOutputStream objectOutputStream;
    ObjectInputStream objectInputStream;

    public SocketInfo (Socket s, ObjectOutputStream o, ObjectInputStream i) {
        this.socket = s;
        this.objectOutputStream = o;
        this.objectInputStream = i;
    }

}