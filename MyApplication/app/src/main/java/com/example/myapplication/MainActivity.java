package com.example.myapplication;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;

public class MainActivity extends AppCompatActivity {

    static {
        System.loadLibrary( "hello-world" );
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    /**
     * Called when the user clicks the Start simulation button
     */
   public void startSimulation(View view) {

       /**
        * Setup service to receive data from the connected peers.
        */
       // Allocate memory to hold the presynaptic spikes
       boolean[] presynapticSpikes = new boolean[4];
       // Create the intent to start the service which reads the incoming spikes and write them in presynapticSpikes
       Intent serviceIntent = new Intent(this, DataReceiver.class);
       serviceIntent.putExtra("Spikes", presynapticSpikes);
       // Start the service
       this.startService(serviceIntent);


       // Call the native method, passing to it a pointer to presynapticSpikes
       boolean test = helloWorld(presynapticSpikes);
    }

    /**
     * JNI : follows the native method helloWorld
     */
    public native boolean helloWorld(boolean[] presynapticSpikeTrains);
}



