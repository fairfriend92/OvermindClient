package com.example.overmind;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;

public class ErrorDialogFragment extends android.support.v4.app.DialogFragment {

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        int errorNumber = getArguments().getInt("ErrorNumber");
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        switch (errorNumber) {
            case 0:
                builder.setMessage(R.string.connection_failed_message)
                        .setTitle(R.string.connection_failed_title)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dismiss();
                            }
                        });
                break;
            case 1:
                builder.setMessage(R.string.connection_lost_message)
                        .setTitle(R.string.connection_lost_title)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dismiss();
                            }
                        });
                break;
            case 2:
                builder.setMessage(R.string.udp_socket_timeout_message)
                        .setTitle(R.string.udp_socket_timeout_title)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dismiss();
                            }
                        });
                break;
            case 3:
                builder.setMessage(R.string.stream_error_message)
                        .setTitle(R.string.stream_error_title)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dismiss();
                            }
                        });
                break;
            case 4:
                builder.setMessage(R.string.gpu_id_error_message)
                        .setTitle(R.string.gpu_id_error_title)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dismiss();
                            }
                        });
                break;
            case 5:
                builder.setMessage(R.string.num_of_neurons_error_message)
                        .setTitle(R.string.num_of_neurons_error_title)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dismiss();
                            }
                        });
                break;
            case 6:
                builder.setMessage(R.string.opencl_failure_message)
                        .setTitle(R.string.opencl_failure_title)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dismiss();
                            }
                        });
                break;
        }

        return builder.create();
    }

}
