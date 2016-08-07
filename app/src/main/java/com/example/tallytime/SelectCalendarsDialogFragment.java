package com.example.tallytime;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.AlertDialog;

import java.util.ArrayList;

/**
 * Class to enable a dialog to select calendars.
 *
 * @author michael
 */
public class SelectCalendarsDialogFragment extends DialogFragment {

    ArrayList<String> mCalendarNames;
    ArrayList<String> mSelectedCalendars;

    // Use this instance of the interface to deliver action events
    SelectCalendarsDialogListener mListener;

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {

        if (getArguments() != null) {
            mCalendarNames = getArguments().getStringArrayList("calendarNames");
            mSelectedCalendars = getArguments().getStringArrayList("selectedCalendars");
        } else {
            mCalendarNames = new ArrayList<>();
            mSelectedCalendars = new ArrayList<>();
        }

        final String[] calendarsArray;
        final boolean[] selectedCalendarsItems;

        if (mCalendarNames != null) {
            calendarsArray = mCalendarNames.toArray(new String[mCalendarNames.size()]);
            selectedCalendarsItems = new boolean[mCalendarNames.size()];

            for (int i = 0; i < mCalendarNames.size(); i++) {
                String calendar = mCalendarNames.get(i);
                selectedCalendarsItems[i] = false;

                for (int j = 0; j < mSelectedCalendars.size(); j++) {
                    if (mSelectedCalendars.get(j).contentEquals(calendar)) {
                        selectedCalendarsItems[i] = true;
                    }
                }
            }
        } else {
            calendarsArray = new String[]{};
            selectedCalendarsItems = new boolean[]{};
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        // Set the dialog title
        builder.setTitle("Select calendars")
                // Specify the list array, the items to be selected by default (null for none),
                // and the listener through which to receive callbacks when items are selected
                .setMultiChoiceItems(calendarsArray, selectedCalendarsItems,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which,
                                                boolean isChecked) {
                                if (isChecked) {
                                    mListener.onSelectCalendarsDialogPositiveClick(mCalendarNames.get(which));

                                } else {
                                    mListener.onSelectCalendarsDialogNegativeClick(mCalendarNames.get(which));
                                }
                            }
                        })
                // Set the action buttons
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        // User clicked OK, so save the mSelectedItems results somewhere
                        // or return them to the component that opened the dialog

                    }
                });
//                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
//                    @Override
//                    public void onClick(DialogInterface dialog, int id) {
//
//                    }
//                });

        return builder.create();
    }

    // Override the Fragment.onAttach() method to instantiate the SelectCalendarsDialogListener
    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        // Verify that the host activity implements the callback interface
        try {
            // Instantiate the SelectCalendarsDialogListener so we can send events to the host
            mListener = (SelectCalendarsDialogListener) context;
        } catch (ClassCastException e) {
            // The activity doesn't implement the interface, throw exception
            throw new ClassCastException(context.toString()
                    + " must implement SelectCalendarsDialogListener");
        }
    }

    /* The activity that creates an instance of this dialog fragment must
     * implement this interface in order to receive event callbacks.
     * Each method passes the DialogFragment in case the host needs to query it.
     */
    public interface SelectCalendarsDialogListener {
        void onSelectCalendarsDialogPositiveClick(String calendar);
        void onSelectCalendarsDialogNegativeClick(String calendar);
    }
}
