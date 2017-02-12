package com.example.overmind;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;

class ServerConnect extends AsyncTask<Context, Integer, Socket> {

    private String SERVER_IP = MainActivity.serverIP;

    private Context context;
    private LocalNetwork thisDevice = new LocalNetwork();
    private Socket clientSocket = null;

    private static final int SERVER_PORT_TCP = 4195;
    private static final int IPTOS_RELIABILITY = 0x04;

    protected Socket doInBackground(Context ... contexts) {

        //Looper.prepare();

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
        thisDevice.ip = ip;
        //publishProgress(0);

        /**
         * Choose the number of neurons of the local network based on GPU performance and set the other info
         */

        short numOfNeurons = 1;
        if (MainActivity.numOfNeuronsDetermineByApp) {
            switch (MainActivity.renderer) {
                case "Mali-T720":
                    numOfNeurons = 58;
                    break;
                default:
                    // TODO default means could not identify GPU, exit app.
                    numOfNeurons = 1;
            }
        } else {
            try {
                numOfNeurons = (short)Integer.parseInt(MainActivity.numOfNeurons);
            } catch(NumberFormatException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("ServerConnect", stackTrace);
                MainActivity.ServerConnectErrorNumber = 5;
                MainActivity.ServerConnectFailed = true;
            }
        }

        if ((numOfNeurons > 1024) || (numOfNeurons < 1)) {
            MainActivity.ServerConnectErrorNumber = 5;
            MainActivity.ServerConnectFailed = true;
        }

        thisDevice.numOfNeurons = numOfNeurons;
        thisDevice.numOfDendrites = 1024;
        thisDevice.numOfSynapses = 1024;
        thisDevice.natPort = 0;
        thisDevice.presynapticNodes = new ArrayList<>();
        thisDevice.postsynapticNodes = new ArrayList<>();
        //publishProgress(1);

        /**
         * Establish connection with the Overmind and send local network info
         */

        // TODO Perhaps clientsocket should have a timeout?

        if (!MainActivity.ServerConnectFailed) {

            try {
                clientSocket = new Socket(SERVER_IP, SERVER_PORT_TCP);
                //clientSocket.setTrafficClass(IPTOS_RELIABILITY);
                //clientSocket.setTcpNoDelay(true);
                clientSocket.setSoTimeout(0);
            } catch (IOException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("ServerConnect", stackTrace);
                MainActivity.ServerConnectFailed = true;
                MainActivity.ServerConnectErrorNumber = 0;
            }

        }

        if (clientSocket != null && !MainActivity.ServerConnectFailed) {
            try {
                ObjectOutputStream output = new ObjectOutputStream(clientSocket.getOutputStream());
                output.writeObject(thisDevice);
            } catch (IOException | NullPointerException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("ServerConnect", stackTrace);
            }
        }

        //publishProgress(2);

        assert ip != null;
        return clientSocket;
    }

    protected void onProgressUpdate(Integer... progress) {
        int duration = Toast.LENGTH_SHORT;
        switch (progress[0]) {
            case 0:
                CharSequence text = "Global IP retrieved";
                Toast.makeText(context, text, duration).show();
                break;
            case 1:
                text = "Local Network initialized";
                Toast.makeText(context, text, duration).show();
                break;
            case 2:
                text = "Connection with the Overmind established";
                Toast.makeText(context, text, duration).show();
        }
    }

    protected void onPostExecute(LocalNetwork result) {
        Log.d("ServerConnect", "My current IP address is " + result.ip);
    }
}