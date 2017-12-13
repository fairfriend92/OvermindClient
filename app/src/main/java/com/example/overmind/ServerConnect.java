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
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;

class ServerConnect extends AsyncTask<Context, Integer, SocketInfo> {
    private Context context;
    private Terminal thisTerminal = new Terminal();
    private Socket clientSocket = null;


    protected SocketInfo doInBackground(Context ... contexts) {

        /*
         * Retrieve the global IP of this device.
         */

        context = contexts[0];
        String ip = null;

        if (!Constants.USE_LOCAL_CONNECTION) {
            try (java.util.Scanner s = new java.util.Scanner(new java.net.URL("https://api.ipify.org").openStream(), "UTF-8").useDelimiter("\\A")) {
                ip = s.next();
            } catch (java.io.IOException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("ServerConnect", stackTrace);
            }
        } else {
            try {
                Socket socket = new Socket("192.168.1.1", 80);
                ip = socket.getLocalAddress().getHostAddress();
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        thisTerminal.ip = ip;

        /*
         * Choose the number of neurons of the local network.
         */

        // If the checkbox has been selected the application look up the appropriate number of
        // neurons for the device.
        short numOfNeurons = 1;
        if (MainActivity.numOfNeuronsDeterminedByApp) {
            switch (MainActivity.renderer) {
                case "Mali-T720":
                    numOfNeurons = 56;
                    break;
                default:
                    // TODO default means could not identify GPU, exit app.
                    numOfNeurons = 1;
            }
            Constants.NUMBER_OF_NEURONS = numOfNeurons;
        } else {

            // Else the number of neurons is chosen by the user and as such must be
            // retrieved from the text box.

            numOfNeurons = Constants.NUMBER_OF_NEURONS;
        }

        thisTerminal.numOfNeurons = numOfNeurons;

        // If lateral connections have been enabled, the number of synapses must be modified
        // appropriately
        if (Constants.LATERAL_CONNECTIONS) {
            thisTerminal.numOfDendrites = (short) (Constants.NUMBER_OF_SYNAPSES - Constants.NUMBER_OF_NEURONS);
            thisTerminal.numOfSynapses = (short) (Constants.NUMBER_OF_SYNAPSES - Constants.NUMBER_OF_NEURONS);
        } else {
            thisTerminal.numOfDendrites = Constants.NUMBER_OF_SYNAPSES;
            thisTerminal.numOfSynapses = Constants.NUMBER_OF_SYNAPSES;
        }

        thisTerminal.natPort = 0;
        thisTerminal.serverIP = Constants.SERVER_IP;
        thisTerminal.presynapticTerminals = new ArrayList<>();
        thisTerminal.postsynapticTerminals = new ArrayList<>();

        // If lateral connections have been enabled, the terminal must add itself to its own
        // presynaptic and postsynaptic connections
        if (Constants.LATERAL_CONNECTIONS) {
            thisTerminal.presynapticTerminals.add(thisTerminal);
            thisTerminal.postsynapticTerminals.add(thisTerminal);
        }

        /*
         * Establish connection with the Overmind and send terminal info.
         */

        if (!MainActivity.serverConnectFailed) {

            try {
                clientSocket = new Socket(Constants.SERVER_IP, Constants.SERVER_PORT_TCP);
                clientSocket.setTrafficClass(Constants.IPTOS_RELIABILITY);
                clientSocket.setKeepAlive(true);
                //clientSocket.setTcpNoDelay(true);
            } catch (IOException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("ServerConnect", stackTrace);
                MainActivity.serverConnectFailed = true;
                MainActivity.serverConnectErrorNumber = 0;
            }

        }

        /*
         * If no error has occurred the info about the terminal are sent to the server.
         */

        ObjectOutputStream output = null;

        if (clientSocket != null && !MainActivity.serverConnectFailed) {
            try {
                output = new ObjectOutputStream(clientSocket.getOutputStream());
                output.writeObject(thisTerminal);
                output.flush();
                publishProgress(0);
            } catch (IOException | NullPointerException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("ServerConnect", stackTrace);
            }
        }

        assert ip != null;
        assert output != null;
        return new SocketInfo(clientSocket, output, null);
    }

    protected void onProgressUpdate(Integer... progress) {
        int duration = Toast.LENGTH_SHORT;
        switch (progress[0]) {
            case 0:
                CharSequence text = "Connected with the Overmind server";
                Toast.makeText(context, text, duration).show();
        }
    }
}