package com.example.overmind;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;

class IpChecker extends AsyncTask<Context, Integer, Socket> {

    private static final String SERVER_IP = MainActivity.serverIP;


    private Context context;
    private LocalNetwork thisDevice = new LocalNetwork();
    private Socket clientSocket = null;

    private static final int SERVER_PORT = 4195;

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
            Log.e("IpChecker", stackTrace);
        }
        thisDevice.ip = ip;
        publishProgress(0);

        /**
         * Choose the number of neurons of the local network based on GPU performance and set the other info
         */

        short numOfNeurons;
        switch (MainActivity.renderer) {
            case "Mali-T720":
                numOfNeurons = 32;
                break;
            default:
                // TODO default means could not identify GPU, exit app.
                numOfNeurons = 1;
        }
        thisDevice.numOfNeurons = numOfNeurons;
        thisDevice.numOfDendrites = 1024;
        thisDevice.numOfSynapses = 1024;
        thisDevice.natPort = 0;
        thisDevice.presynapticNodes = new ArrayList<>();
        thisDevice.postsynapticNodes = new ArrayList<>();
        publishProgress(1);

        /**
         * Establish connection with the Overmind and send local network info
         */

        try {
            clientSocket = new Socket(SERVER_IP, SERVER_PORT);
        } catch (IOException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("IpChecker", stackTrace);
        }

        if (clientSocket != null) {
            try {
                ObjectOutputStream output = new ObjectOutputStream(clientSocket.getOutputStream());
                output.writeObject(thisDevice);
            } catch (IOException | NullPointerException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("IpChecker", stackTrace);
            }
        }

        publishProgress(2);

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
        Log.d("IpChecker", "My current IP address is " + result.ip);
    }
}