package com.example.overmind;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.Toast;

class IpChecker extends AsyncTask<Context, Integer, String> {

    private Context context;

    protected String doInBackground(Context ... contexts) {
        context = contexts[0];
        String ip = null;
        try (java.util.Scanner s = new java.util.Scanner(new java.net.URL("https://api.ipify.org").openStream(), "UTF-8").useDelimiter("\\A")) {
            ip = s.next();
        } catch (java.io.IOException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("IpChecker", stackTrace);
        }
        publishProgress(0);
        assert ip != null;
        return ip;
    }

    protected void onProgressUpdate(Integer... progress) {
        int duration = Toast.LENGTH_SHORT;
        switch (progress[0]) {
            case 0:
                CharSequence text = "Global IP retrieved";
                Toast.makeText(context, text, duration).show();
        }
    }

    protected void onPostExecute(String result) {
        Log.d("IpChecker", "My current IP address is " + result);
    }
}