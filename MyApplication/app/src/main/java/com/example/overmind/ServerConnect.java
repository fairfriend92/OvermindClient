/**
 * Async task called to establish the tcp connection with the server to send the information
 * regarding the local neural network
 */

package com.example.overmind;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;

class ServerConnect extends AsyncTask<Context, Integer, Socket> {

    private String SERVER_IP = MainActivity.serverIP;

    private Context context;
    private Terminal thisTerminal = new Terminal();
    private Socket clientSocket = null;

    private static final int SERVER_PORT_TCP = 4195;
    private static final int IPTOS_RELIABILITY = 0x04;

    protected Socket doInBackground(Context ... contexts) {

        /**
         * Retrieve the global IP of this device
         */

        context = contexts[0];
        String ip = null;
        try (java.util.Scanner s = new java.util.Scanner(new java.net.URL("https://api.ipify.org").openStream(), "UTF-8").useDelimiter("\\A")) {
            ip = s.next();
        } catch (java.io.IOException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("ServerConnect", stackTrace);
        }
        thisTerminal.ip = ip;

        /**
         * Choose the number of neurons of the local network
         */

        // If the checkbox has been selected the application look up the appropriate number of
        // neurons for the device
        short numOfNeurons = 1;
        if (MainActivity.numOfNeuronsDetermineByApp) {
            switch (MainActivity.renderer) {
                case "Mali-T720":
                    numOfNeurons = 56;
                    break;
                default:
                    // TODO default means could not identify GPU, exit app.
                    numOfNeurons = 1;
            }
        } else {

            // Else the number of neurons is chosen by the user and as such must be
            // retrieved from the text box

            try {
                numOfNeurons = (short)Integer.parseInt(MainActivity.numOfNeurons);
            } catch(NumberFormatException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("ServerConnect", stackTrace);
                MainActivity.ServerConnectErrorNumber = 5;
                MainActivity.ServerConnectFailed = true;
            }
        }

        // Check whether the number of neurons selected is within range
        if ((numOfNeurons > 1024) || (numOfNeurons < 1)) {
            MainActivity.ServerConnectErrorNumber = 5;
            MainActivity.ServerConnectFailed = true;
        }

        thisTerminal.numOfNeurons = numOfNeurons;
        thisTerminal.numOfDendrites = 1024;
        thisTerminal.numOfSynapses = getNumOfSynapses();
        thisTerminal.natPort = 0;
        thisTerminal.presynapticTerminals = new ArrayList<>();
        thisTerminal.postsynapticTerminals = new ArrayList<>();

        /**
         * Establish connection with the Overmind and send terminal info
         */

        if (!MainActivity.ServerConnectFailed) {

            try {
                clientSocket = new Socket(SERVER_IP, SERVER_PORT_TCP);
                //clientSocket.setTrafficClass(IPTOS_RELIABILITY);
                //clientSocket.setTcpNoDelay(true);
            } catch (IOException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("ServerConnect", stackTrace);
                MainActivity.ServerConnectFailed = true;
                MainActivity.ServerConnectErrorNumber = 0;
            }

        }

        /**
         * If no error has occurred the info about the terminal are sent to the server
         */

        if (clientSocket != null && !MainActivity.ServerConnectFailed) {
            try {
                ObjectOutputStream output = new ObjectOutputStream(clientSocket.getOutputStream());
                output.writeObject(thisTerminal);
                publishProgress(0);
            } catch (IOException | NullPointerException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("ServerConnect", stackTrace);
            }
        }

        assert ip != null;
        return clientSocket;
    }

    protected void onProgressUpdate(Integer... progress) {
        int duration = Toast.LENGTH_SHORT;
        switch (progress[0]) {
            case 0:
                CharSequence text = "Connected with the Overmind server";
                Toast.makeText(context, text, duration).show();
        }
    }

    public native short getNumOfSynapses();

}