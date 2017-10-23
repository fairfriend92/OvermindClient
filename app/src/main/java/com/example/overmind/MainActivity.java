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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends AppCompatActivity {

    // Flag that signals eventual errors occurred while connecting to the server, including
    // out of range errors for the number of neurons and synapses
    static boolean serverConnectFailed = false;
    static short serverConnectErrorNumber; // Type of error occurred

    // GUI elements
    private EditText editServerIP = null;
    private EditText editNumOfNeurons = null;
    private EditText editNumOfSynapses = null;
    private TextView currentView = null; // Field displaying the value of the current injected by the user

    private float current = 0.0f; // Current injected by the user
    private String vendor; // Holds the name of the GPU vendor

    private static SharedPreferences prefs; // Used to store GPU info by the renderer class
    static SocketInfo thisClient; // Socket used for TCP communications with the Overmind server
    static Terminal server = new Terminal(); // Terminal object holding info about the server
    static String serverIP;
    static boolean numOfNeuronsDeterminedByApp = false; // Flag that is set if the user allows the app to determine the appropriate number of neurons
    static String renderer; // Name of the GPU model

    // Countdown timer used to delay the moment when the connection with the server is attempted
    // since it takes some time to retrieve the necessaryi info regarding the GPU model
    private CountdownToSimulation timerToSimulation = new CountdownToSimulation(1000, 500);

    /*
    Method called whenever the pre_connection layout is brought back and all the fields need to be
    restored to the default values
     */

    private void RestoreHomeMenu() {
        // Reset the flags and the settings
        MainActivity.numOfNeuronsDeterminedByApp = false;
        serverConnectFailed = false;
        Constants.NUMBER_OF_NEURONS = 1;
        Constants.NUMBER_OF_SYNAPSES = 1024;
        Constants.LATERAL_CONNECTIONS = false;

        // Bring back the home menu view
        setContentView(R.layout.pre_connection);
        editServerIP = (EditText) findViewById(R.id.edit_ip);
        editNumOfNeurons = (EditText) findViewById(R.id.edit_num_of_neurons);
        editNumOfSynapses = (EditText) findViewById(R.id.edit_num_of_synapses);
    }

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
     * OpenGL surface view called to retrieve GPU info
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
        editServerIP.setText(ip);

    }

    private class CountdownToSimulation extends CountDownTimer {

        CountdownToSimulation (long millisInFuture, long countDownInterval) {

            super (millisInFuture, countDownInterval);
        }

        @Override
        public void onTick(long millisUntilFinished) {

        }

        @Override
        public void onFinish() {

            // Get the GPU model to set the number of neurons of the local network
            SharedPreferences prefs = getSharedPreferences("GPUinfo", Context.MODE_PRIVATE);
            renderer = prefs.getString("RENDERER", null);

            // Load the appropriate OpenCL library based on the GPU vendor
            loadGLLibrary();

            // Create and launch the AsyncTask to retrieve the global IP of the terminal and to send the info
            // of the terminal to the Overmind server
            ServerConnect serverConnect = new ServerConnect();
            serverConnect.execute(getApplicationContext());

            // Get from the AsyncTask the struct holding all the info regarding the terminal
            try {
                thisClient = serverConnect.get();
            } catch (InterruptedException | ExecutionException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("MainActivity", stackTrace);
            }

            // Display error message and bring back the home layout if the connection with the
            // Overmind server fails
            if (serverConnectFailed) {
                // Show the appropriate error message
                android.support.v4.app.DialogFragment dialogFragment = new ErrorDialogFragment();
                Bundle args = new Bundle();
                args.putInt("ErrorNumber", serverConnectErrorNumber);
                dialogFragment.setArguments(args);
                dialogFragment.show(getSupportFragmentManager(), "Connection failed");

                // When going back to the home menu the default settings must be restored
                RestoreHomeMenu();
            } else {

                /*
                 * If the async task server connect succeeded the simulation can be started
                 */

                // Now that the GPU info are available display the proper application layout
                setContentView(R.layout.activity_main);

                // By default the network is made of regular spiking neurons, so the appropriate parameters
                // must be passed to the simulation and the default radio button must be selected
                RadioButton regularSpikingRadioButton = (RadioButton) findViewById(R.id.radio_rs);

                assert regularSpikingRadioButton != null;

                regularSpikingRadioButton.setChecked(true);

                SimulationParameters.setParameters(0.02f, 0.2f, -65.0f, 8.0f, 0.0f);

                Resources res = getResources();

                /*
                 * Show some info about the terminal running the simulation
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

                /*
                 * Display text info about selected stimulation
                 */

                currentView = new TextView(MainActivity.this);
                String currentString = String.format(res.getString(R.string.current), 0.0f);
                currentView.setText(currentString);
                ViewGroup stimulusSelectionLayout = (ViewGroup) findViewById(R.id.stimulus_selection_layout);
                assert stimulusSelectionLayout != null;
                stimulusSelectionLayout.addView(currentView);

                startSimulation();

            }

            assert thisClient != null;

        }

    }

    /*
    Called when the start simulation button is pressed. Retrieve the settings defined by the user
    (server ip, number of neurons and of synapses) and create the openGL surface, which is necessary
    to retrieve the info about the GPU. Since it takes some time to initialize the openGL context,
    before actually querying for the GPU model, wait a little bit. To this purpose start a
    countdown timer and when it's finished retrieve the info and try to connect with the server.
     */

    public void startSimulation(View view) {
        assert editServerIP != null;

        // Get the server ip from the text box
        serverIP = editServerIP.getText().toString();
        server.ip = serverIP;

        try {
            // Get the number of neurons for the local netwowrk from the text box
            if (!MainActivity.numOfNeuronsDeterminedByApp) // The field must be read only if the number of neurons is chosen by the user
                Constants.NUMBER_OF_NEURONS = (short) Integer.parseInt(editNumOfNeurons.getText().toString());

            // If the number of neurons is out of range, and if and only if the number must be defined
            // by the user, set the error flag and write down the appropriate error number.
            if ((Constants.NUMBER_OF_NEURONS < 1 | Constants.NUMBER_OF_NEURONS > 65535) &&
                    !MainActivity.numOfNeuronsDeterminedByApp) {
                serverConnectFailed = true;
                serverConnectErrorNumber = 5;
            }

            // Get the number of synapses per neuron.
            Constants.NUMBER_OF_SYNAPSES = (short) Integer.parseInt(editNumOfSynapses.getText().toString());
            // As before, if the number is out of range set the error flag
            if (Constants.NUMBER_OF_SYNAPSES < 1 | Constants.NUMBER_OF_SYNAPSES > 65535) {
                serverConnectFailed = true;
                serverConnectErrorNumber = 5; // TODO: make additional error message.
            }

            // If lateral connections have been enabled, the number of synapses must be at least
            // greater than that of neurons
            if (Constants.LATERAL_CONNECTIONS & Constants.NUMBER_OF_NEURONS > Constants.NUMBER_OF_SYNAPSES) {
                serverConnectFailed = true;
                serverConnectErrorNumber = 5; // TODO: make additional error message.
            }
        } catch (NumberFormatException e) { // Catch eventual errors caused by blank field that must bet filled by the user
            serverConnectFailed = true;
            serverConnectErrorNumber = 5; // TODO: make additional error message.
        }

        // OpenGL surface view
        MyGLSurfaceView mGlSurfaceView = new MyGLSurfaceView(this);

        // Set on display the OpenGL surface view in order to call the OpenGL renderer and retrieve the GPU info
        setContentView(mGlSurfaceView);

        // Use this countdown timer to give enough time to the renderer to retrieve the info
        timerToSimulation.start();
    }

    /**
     * Called when the user clicks the buttons to increase or decrease the simulation current.
     */

    public void increaseCurrent(View view) {
        float[] parameters = SimulationParameters.getParameters();

        Resources res = getResources();
        parameters[4] += 0.5f;
        current = parameters[4];
        String currentString = String.format(res.getString(R.string.current), current);
        currentView.setText(currentString);

        SimulationParameters.setParameters(parameters[0], parameters[1], parameters[2], parameters[3], parameters[4]);

    }

    public void decreaseCurrent(View view) {
        float[] parameters = SimulationParameters.getParameters();

        if (parameters[4] > 0) {
            Resources res = getResources();
            parameters[4] -= 0.5f;
            current = parameters[4];
            String currentString = String.format(res.getString(R.string.current), current);
            currentView.setText(currentString);
        }

        SimulationParameters.setParameters(parameters[0], parameters[1], parameters[2], parameters[3], parameters[4]);

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
                    numOfNeuronsDeterminedByApp = true;
                } else {
                    editNumOfNeurons.setText("1");
                    numOfNeuronsDeterminedByApp = false;
                }
                break;
            case R.id.checkbox_lateral_connections:
                Constants.LATERAL_CONNECTIONS = checked;
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
                    SimulationParameters.setParameters(0.1f, 0.2f, -65.0f, 2.0f, current);
                    break;
            case R.id.radio_rs:
                if (checked)
                    SimulationParameters.setParameters(0.02f, 0.2f, -65.0f, 8.0f, current);
                    break;
            case R.id.radio_ch:
                if (checked)
                    SimulationParameters.setParameters(0.02f, 0.2f, -50.0f, 2.0f, current);
                    break;
        }
    }

    /*
    Action performed when user click on button in the establish_connection layout
     */

    public void backHomeMenu(View view) {

        CountdownToConnectionService.shutdown = true;

        RestoreHomeMenu();
    }

    /*
    Called when the cdToConnectionService attempts to establish a new connection
     */

    private BroadcastReceiver attemptConnectionReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {

            setContentView(R.layout.pre_connection);

            Button button = (Button)findViewById(R.id.startSimulationButton);

            button.performClick();
            button.setPressed(true);
            button.invalidate();
            button.setPressed(false);
            button.invalidate();

        }

    };

    /*
    Handler for received Intents: called whenever an Intent  with action named
    "Error message" is broadcast.
     */

    int numfOfDisconnections = 0;

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            numfOfDisconnections++;

            Intent connectionServiceIntent = new Intent(MainActivity.this, CountdownToConnectionService.class);

            // Start the service that periodically attempts to connect with the server after a
            // disconnection
            MainActivity.this.startService(connectionServiceIntent);

            int errorNumber = 0;

            // Get data included in the Intent about the kind of error that has occurred
            errorNumber = intent.getIntExtra("ErrorNumber", errorNumber);

            /*
            // Use the bundle to pass to the class that displays the error message its error code
            Bundle args = new Bundle();

            // Call the class to display the error message
            android.support.v4.app.DialogFragment dialogFragment = new ErrorDialogFragment();

            // Set the error code
            args.putInt("ErrorNumber", errorNumber);
            dialogFragment.setArguments(args);

            // Display the error message and bring back the home layout
            dialogFragment.show(getSupportFragmentManager(), "Connection failed");
            */

            setContentView(R.layout.establish_connection);

            Resources res = getResources();

            // The field which displays the details about the disconnection error
            TextView errorTextView = new TextView(MainActivity.this);

            // The field containing the diagnostics
            TextView diagnosticsView = new TextView(MainActivity.this);

            String errorString = "Undefined error with errornumber " + errorNumber;

            switch (errorNumber) {
                case 2:
                    errorString = String.format(res.getString(R.string.error_text),
                            res.getString(R.string.udp_socket_timeout_message));
                    break;
                case 3:
                    errorString = String.format(res.getString(R.string.error_text),
                            res.getString(R.string.stream_error_message));
                    break;
                case 6:
                    errorString = String.format(res.getString(R.string.error_text),
                            res.getString(R.string.opencl_failure_message));
                    break;
            }

            String diagnosticsString = String.format(res.getString(R.string.diagnostics_info), numfOfDisconnections);

            // Put the text about the error in the field
            errorTextView.setText(errorString);

            // Put the various info in the diagnostics view
            diagnosticsView.setText(diagnosticsString);

            ViewGroup errorTextLayout = (ViewGroup) findViewById(R.id.error_text);

            ViewGroup diagnosticsTextLayout = (ViewGroup) findViewById(R.id.diagnostics_info);

            assert errorTextLayout != null;
            assert diagnosticsTextLayout != null;

            // Add the view about the error to the layout
            errorTextLayout.addView(errorTextView);

            // Append the diagnostics info to the proper layout
            diagnosticsTextLayout.addView(diagnosticsView);

        }

    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        // Used to get the GPU info stored by the OpenGL renderer
        prefs = this.getSharedPreferences("GPUinfo", Context.MODE_PRIVATE);

        setContentView(R.layout.pre_connection);

        // Create the editable fields
        editServerIP = (EditText) findViewById(R.id.edit_ip);
        editNumOfNeurons = (EditText) findViewById(R.id.edit_num_of_neurons);
        editNumOfSynapses = (EditText) findViewById(R.id.edit_num_of_synapses);

        /*
        Register an observer (mMessageReceiver) to receive Intents with actions named
        "ErrorMessage"
         */
        LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver,
                new IntentFilter("ErrorMessage"));
        /*
        Register an observer to listen for the signal to attempt a new connection
         */
        LocalBroadcastManager.getInstance(this).registerReceiver(attemptConnectionReceiver,
                new IntentFilter("AttemptConnection"));
    }

    @Override
    public void onDestroy() {

        // Unregister the receivers since the services are about to be closed
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(attemptConnectionReceiver);

        SimulationService.shutDown();

        CountdownToConnectionService.shutdown = true;

        super.onDestroy();
    }

    /**
     * Load the proper OpenGL library based on GPU vendor info provided by the OpenGL renderer
     */


    public void loadGLLibrary() {

        SharedPreferences prefs = getSharedPreferences("GPUinfo", Context.MODE_PRIVATE);
        vendor = prefs.getString("VENDOR", null);
        assert vendor != null;
        switch (vendor) {
            case "ARM":try {
                    System.loadLibrary("ARM");
                } catch (UnsatisfiedLinkError linkError) {
                    Log.e("Unsatisfied link", "libGLES_mali.so not found");
                }
                break;
            case "Qualcomm":
                try {
                    System.loadLibrary("Qualcomm");
                } catch (UnsatisfiedLinkError linkError) {
                    Log.e("Unsatisfied link", "libOpenCL.so not found");
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
            case "Mali-T880":
            case "Mali-T720":
                // The string used to hold the .cl kernel file
                kernel = loadKernelFromAsset(getInputStream("kernel_vec4.cl"));
                break;
            default:
                kernel = loadKernelFromAsset(getInputStream("kernel.cl"));
                break;
        }

        // Put the string holding the kernel in the simulation Intent
        simulationIntent.putExtra("Kernel", kernel);

        //SimulationService.shutdown = false;

        // Start the service
        this.startService(simulationIntent);

    }

}


