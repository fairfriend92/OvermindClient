package com.example.overmind;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;

public class ConnectionFailedDialogFragment extends android.support.v4.app.DialogFragment {

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
                builder.setMessage(R.string.stream_error_message)
                        .setTitle(R.string.stream_error_title)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dismiss();
                            }
                        });
        }

        return builder.create();
    }

}
