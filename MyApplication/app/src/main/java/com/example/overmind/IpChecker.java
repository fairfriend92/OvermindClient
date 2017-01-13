package com.example.overmind;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.Socket;

class IpChecker extends AsyncTask<Context, Integer, String> {

    private static final String SERVER_IP = "82.59.183.105";
    private static final int SERVER_PORT = 4194;

    private Context context;
    static short numOfNeurons;
    private localNetwork thisDevice = new localNetwork();
    private Socket clientSocket = null;
    private ObjectOutputStream output = null;

    protected String doInBackground(Context ... contexts) {

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

        switch (MainActivity.renderer) {
            case "Mali-T720":
                numOfNeurons = 32;
                break;
            default:
                // TODO default means could not identify GPU, exit app.
                numOfNeurons = 1;
        }
        thisDevice.numOfNeurons = numOfNeurons;

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

        publishProgress(1);

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
        return ip;
    }

    protected void onProgressUpdate(Integer... progress) {
        int duration = Toast.LENGTH_SHORT;
        switch (progress[0]) {
            case 0:
                CharSequence text = "Global IP retrieved";
                Toast.makeText(context, text, duration).show();
                break;
            case 1:
                text = "Connection with the Overmind established";
                Toast.makeText(context, text, duration).show();
        }
    }

    protected void onPostExecute(String result) {
        Log.d("IpChecker", "My current IP address is " + result);
    }
}