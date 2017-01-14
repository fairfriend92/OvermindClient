package com.example.overmind;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.Socket;

class IpChecker extends AsyncTask<Context, Integer, LocalNetwork> {

    private static final String SERVER_IP = "82.49.192.21";
    private static final int SERVER_PORT = 4194;

    private Context context;
    static short numOfNeurons;
    private LocalNetwork thisDevice = new LocalNetwork();
    private Socket clientSocket = null;
    private ObjectOutputStream output = null;

    protected LocalNetwork doInBackground(Context ... contexts) {

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
         * Choose the number of neurons of the local network based on GPU performance
         */
        switch (MainActivity.renderer) {
            case "Mali-T720":
                numOfNeurons = 32;
                break;
            default:
                // TODO default means could not identify GPU, exit app.
                numOfNeurons = 1;
        }
        thisDevice.numOfNeurons = numOfNeurons;
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
                output = new ObjectOutputStream(clientSocket.getOutputStream());
                output.writeObject(thisDevice);
            } catch (IOException | NullPointerException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("IpChecker", stackTrace);
            }
        }

        publishProgress(2);

        /**
         * Since relevant info have been sent we can close the connection and the ObjectStream
         */
        if (clientSocket != null && output != null) {
            try {
                clientSocket.close();
                output.close();
            } catch (IOException | NullPointerException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("IpChecker", stackTrace);
            }
        }

        assert ip != null;
        return thisDevice;
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