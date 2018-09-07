package com.example.overmind;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

public class CountdownToConnectionService extends IntentService {

    static volatile boolean shutdown = false;

    public CountdownToConnectionService () {
        super("CountdownToConnectionService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {

        shutdown = false;

        String connectionClass = "null";

        while (!connectionClass.equals("4G") && !shutdown & !Constants.USE_LOCAL_CONNECTION) {

            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("cdToConnectionService", stackTrace);
            }

            connectionClass = SimulationService.getNetworkClass(this);

        }

        if (!shutdown) {
            Intent broadcastConnectionAttempt = new Intent("AttemptConnection");
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastConnectionAttempt);
        }

        Log.d("cdToConnectionService", "Closing cdToConnectionService");
        stopSelf();

    }

}
