/**
 * Main activity containing everything which runs on the gui thread, from error messages to OpenGL
 * calls to retrieve info about the renderer and the GPU vendor
 */

package com.example.overmind;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.CountDownTimer;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends AppCompatActivity {

    /**
     * Class which provides GPU info
     */

    private class MyGLRenderer implements GLSurfaceView.Renderer {

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            // Get various GPU and GL information
            Log.d("GL info", "gl renderer: " + gl.glGetString(GL10.GL_RENDERER));
            Log.d("GL info", "gl vendor: " + gl.glGetString(GL10.GL_VENDOR));
            Log.d("GL info", "gl version: " + gl.glGetString(GL10.GL_VERSION));
            Log.d("GL info", "gl extensions: " + gl.glGetString(GL10.GL_EXTENSIONS));

            // Store the needed GPU info in the preferences
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString("VENDOR", gl.glGetString(GL10.GL_VENDOR));
            editor.putString("RENDERER", gl.glGetString(GL10.GL_RENDERER));
            editor.apply();

            // Set the background frame color
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            // Redraw background color
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }
    }

    /**
     * OpenGL surface view called at app startup to retrieve GPU info
     */

    class MyGLSurfaceView extends GLSurfaceView {
        public MyGLRenderer mRenderer;

        public MyGLSurfaceView(Context context) {
            super(context);

            // Create an OpenGL ES 2.0 context
            setEGLContextClientVersion(2);

            mRenderer = new MyGLRenderer();

            // Set the Renderer for drawing on the GLSurfaceView
            setRenderer(mRenderer);
        }
    }

    // Used to store GPU info by the renderer class
    private static SharedPreferences prefs;

    // Socket used for TCP communications with the Overmind server
    public static Socket thisClient;

    static String serverIP;
    static String numOfNeurons;
    static boolean numOfNeuronsDetermineByApp = false;
    static String renderer;

    EditText editText = null;
    EditText editNumOfNeurons = null;
    RadioButton regularSpikingRadioButton = null;

    /**
     * Called to lookup the Overmind server IP on the Overmind webpage
     */

    public void lookUpServerIP(View view) {

        String ip = null;

        // Traffic needs to happen outside the GUI thread, therefore an asynchronous task is launched
        // to retrieve the IP
        LookUpServerIP lookUpServerIP = new LookUpServerIP();
        lookUpServerIP.execute();

        // Wait for the async task to finish to receive the IP
        try {
            ip = lookUpServerIP.get();
        } catch (InterruptedException | ExecutionException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("MainActivity", stackTrace);
        }

        assert ip != null;

        // Pass the IP to the text box
        editText.setText(ip);

    }

    public static boolean ServerConnectFailed = false;
    public static short ServerConnectErrorNumber;

    /**
     * Called when the start simulation button is pressed
     */

    public void startSimulation(View view) {

        assert editText != null;

        // Get the server ip from the text box
        serverIP = editText.getText().toString();

        // Get the number of neurons ot the local netwowrk from the text box
        numOfNeurons = editNumOfNeurons.getText().toString();

        // OpenGL surface view
        MyGLSurfaceView mGlSurfaceView = new MyGLSurfaceView(this);

        // Set on display the OpenGL surface view in order to call the OpenGL renderer and retrieve the GPU info
        setContentView(mGlSurfaceView);

        // Use this countdown timer to give enough time to the renderer to retrieve the info
        new CountDownTimer(1000, 500) {
            public void onTick(long millisUntilFinished) {
            }

            public void onFinish() {

                // Get the GPU model to set the number of neurons of the local network
                SharedPreferences prefs = getSharedPreferences("GPUinfo", Context.MODE_PRIVATE);
                renderer = prefs.getString("RENDERER", null);

                // Load the appropriate OpenCL library based on the GPU vendor
                loadGLLibrary();

                // Create and launch the AsyncTask to retrieve the global IP of the device and to send the info
                // of the local network to the Overmind server
                ServerConnect serverConnect = new ServerConnect();
                serverConnect.execute(getApplicationContext());

                // Get from the AsyncTask the struct holding all the info regardinf the local network
                try {
                    thisClient = serverConnect.get();
                } catch (InterruptedException | ExecutionException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("MainActivity", stackTrace);
                }

                // Display error message and bring back the home layout if the connection with the
                // Overmind server fails
                if (ServerConnectFailed) {

                    // When going back to the home menu the default settings must be restored
                    MainActivity.numOfNeuronsDetermineByApp = false;
                    ServerConnectFailed = false;

                    // Show the appropriate error message
                    android.support.v4.app.DialogFragment dialogFragment = new ErrorDialogFragment();
                    Bundle args = new Bundle();
                    args.putInt("ErrorNumber", ServerConnectErrorNumber);
                    dialogFragment.setArguments(args);
                    dialogFragment.show(getSupportFragmentManager(), "Connection failed");

                    // Bring back the home menu view
                    setContentView(R.layout.pre_connection);
                    editText = (EditText) findViewById(R.id.edit_ip);
                    editNumOfNeurons = (EditText) findViewById(R.id.edit_num_of_neurons);

                } else {

                    /**
                     * If the async task server connect succeeded the simulation can be started
                     */

                    // Now that the GPU info are available display the proper application layout
                    setContentView(R.layout.activity_main);

                    // By default the network is made of regular spiking neurons, so the appropriate parameters
                    // must be passed to the simulation and the default radio button must be selected
                    regularSpikingRadioButton = (RadioButton) findViewById(R.id.radio_rs);

                    assert regularSpikingRadioButton != null;

                    regularSpikingRadioButton.setChecked(true);

                    SimulationParameters.setParameters(0.02f, 0.2f, -65.0f, 8.0f);

                    Resources res = getResources();

                    /**
                     * Show some info about the device running the simulation
                     */

                    TextView rendererView = new TextView(MainActivity.this);
                    TextView vendorView = new TextView(MainActivity.this);

                    String rendererString = String.format(res.getString(R.string.renderer), renderer);
                    String vendorString = String.format(res.getString(R.string.venodr), vendor);

                    rendererView.setText(rendererString);
                    vendorView.setText(vendorString);

                    ViewGroup mainActivityLayout = (ViewGroup) findViewById(R.id.activity_main);

                    assert mainActivityLayout != null;

                    mainActivityLayout.addView(rendererView);
                    mainActivityLayout.addView(vendorView);

                    startSimulation();

                }

                assert thisClient != null;

            }
        }.start();
    }

    /**
     * Called when the checkbox is clicked
     */

    public void onCheckboxClicked(View view) {
        // Is the view now checked?
        boolean checked = ((CheckBox) view).isChecked();

        // Check which checkbox was clicked
        switch(view.getId()) {
            case R.id.checkbox_num_of_neurons:
                if (checked) {
                    editNumOfNeurons.setText(getResources().getString(R.string.num_of_neurons_text));
                    numOfNeuronsDetermineByApp = true;
                } else {
                    editNumOfNeurons.setText("1");
                    numOfNeuronsDetermineByApp = false;
                }
                break;
        }
    }

    /**
     * Called when one of the radio buttons is selected
     */

    public void onRadioButtonClicked(View view) {
        // Is the button now checked?
        boolean checked = ((RadioButton) view).isChecked();

        // Check which radio button was clicked
        switch(view.getId()) {
            case R.id.radio_fs:
                if (checked)
                    SimulationParameters.setParameters(0.1f, 0.2f, -65.0f, 2.0f);
                    break;
            case R.id.radio_rs:
                if (checked)
                    SimulationParameters.setParameters(0.02f, 0.2f, -65.0f, 8.0f);
                    break;
            case R.id.radio_ch:
                if (checked)
                    SimulationParameters.setParameters(0.02f, 0.2f, -50.0f, 2.0f);
                    break;
        }
    }

    /**
     * Handler for received Intents: called whenever an Intent  with action named
     * "Error message" is broadcast.
     */

    int errorNumber = 0;

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            MainActivity.numOfNeuronsDetermineByApp = false;

            // Get data included in the Intent
            errorNumber = intent.getIntExtra("ErrorNumber", errorNumber);

            // Use the bundle to pass to the class that displays the error message its error code
            Bundle args = new Bundle();

            // Call the class to display the error message
            android.support.v4.app.DialogFragment dialogFragment = new ErrorDialogFragment();

            // Set the error code
            args.putInt("ErrorNumber", errorNumber);
            dialogFragment.setArguments(args);

            // Display the error message and bring back the home layout
            dialogFragment.show(getSupportFragmentManager(), "Connection failed");
            setContentView(R.layout.pre_connection);
            editText = (EditText) findViewById(R.id.edit_ip);
            editNumOfNeurons = (EditText) findViewById(R.id.edit_num_of_neurons);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        // Used to get the GPU info stored by the OpenGL renderer
        prefs = this.getSharedPreferences("GPUinfo", Context.MODE_PRIVATE);

        setContentView(R.layout.pre_connection);

        editText = (EditText) findViewById(R.id.edit_ip);
        editNumOfNeurons = (EditText) findViewById(R.id.edit_num_of_neurons);

        /**
         * Register an observer (mMessageReceiver) to receive Intents with actions named
         * "ErrorMessage"
         */
        LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver,
                new IntentFilter("ErrorMessage"));
    }

    @Override
    public void onDestroy() {

        // Unregister the receiver since the service is about to be closed
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver);

        SimulationService.shutDown();

        super.onDestroy();
    }

    /**
     * Load the proper OpenGL library based on GPU vendor info provided by the OpenGL renderer
     */

    String vendor;

    public void loadGLLibrary() {
        SharedPreferences prefs = getSharedPreferences("GPUinfo", Context.MODE_PRIVATE);
        vendor = prefs.getString("VENDOR", null);
        assert vendor != null;
        switch (vendor) {
            case "ARM":
                try {
                    System.loadLibrary("ARM");
                } catch (UnsatisfiedLinkError linkError) {
                    Log.e("Unsatisfied link", "libGLES_mali.so not found");
                }
                break;
            case "Qualcomm":
                try {
                    System.loadLibrary("Qualcomm");
                } catch (UnsatisfiedLinkError linkError) {
                    Log.e("Unsatisfied link", "libGLES_mali.so not found");
                }
                break;
            default:
                Intent broadcastError = new Intent("ErrorMessage");
                broadcastError.putExtra("ErrorNumber", 4);
                LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastError);
        }
    }

    /**
     * Get an input stream from the .cl file
     */

    public InputStream getInputStream(String kernelName) {
        try {
            return getAssets().open(kernelName);
        } catch (IOException ioException) {
            Log.e("IO exception", "Cannot retrieve OpenCL kernel");
            return null;
        }
    }

    /**
     * Scan the .cl file and turn it into a string
     */

    static String loadKernelFromAsset(InputStream inputStream) {
        Scanner scanner = new Scanner(inputStream).useDelimiter("\\A");
        return scanner.hasNext() ? scanner.next() : " ";
    }

    public void startSimulation() {

        // The SimulationService Intent
        Intent simulationIntent = new Intent(MainActivity.this, SimulationService.class);

        String kernel;

        switch (renderer) {
            case "Mali-T720":
                // The string used to hold the .cl kernel file
                kernel = loadKernelFromAsset(getInputStream("kernel_vec4.cl"));
                break;
            case "Adreno 306":
                kernel = loadKernelFromAsset(getInputStream("kernel_float.cl"));
                break;
            default:
                kernel = loadKernelFromAsset(getInputStream("kernel_float.cl"));
                break;
        }

        // Put the string holding the kernel in the simulation Intent
        simulationIntent.putExtra("Kernel", kernel);

        SimulationService.shutdown = false;

        // Start the service
        this.startService(simulationIntent);

    }

}


