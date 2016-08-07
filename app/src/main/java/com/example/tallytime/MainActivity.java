package com.example.tallytime;

import android.Manifest;
import android.accounts.AccountManager;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.DatePicker;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.extensions.android.gms.auth.GooglePlayServicesAvailabilityIOException;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.ArrayMap;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

/**
 * Add/remove calendars and get total hours for all added calendars for the current week.
 *
 * @author michael
 */
public class MainActivity extends AppCompatActivity implements EasyPermissions.PermissionCallbacks,
        SelectCalendarsDialogFragment.SelectCalendarsDialogListener,
        DatePickerDialogFragment.DatePickerDialogListener {

    private static final String TAG = "MainActivity";

    private GoogleAccountCredential mCredential;
    private ProgressDialog mProgress;
    private ArrayMap<String, String> mCalendars;
    private ArrayList<String> mCalendarNames;
    private ArrayList<String> mSelectedCalendars;
    private int mYear;
    private int mMonth;
    private int mDay;

    static final int REQUEST_ACCOUNT_PICKER = 1000;
    static final int REQUEST_AUTHORIZATION = 1001;
    static final int REQUEST_GOOGLE_PLAY_SERVICES = 1002;
    static final int REQUEST_PERMISSION_GET_ACCOUNTS = 1003;

    private static final String PREF_ACCOUNT_NAME = "accountName";
    private static final String[] SCOPES = {CalendarScopes.CALENDAR_READONLY};

    @BindView(R.id.selected_calendars)
    TextView mSelectedCalendarsTextView;

    @BindView(R.id.selected_week)
    TextView mSelectedWeekTextView;

    @BindView(R.id.output_text)
    TextView mOutputText;

    /**
     * Create the main activity.
     *
     * @param savedInstanceState previously saved instance data.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mProgress = new ProgressDialog(this);
        mProgress.setMessage("Getting calendar hours...");
        mCalendars = new ArrayMap<>();
        mCalendarNames = new ArrayList<>();
        mSelectedCalendars = new ArrayList<>();
        mYear = -1;
        mMonth = -1;
        mDay = -1;

        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        // Initialise credentials and service object.
        mCredential = GoogleAccountCredential.usingOAuth2(
                getApplicationContext(), Arrays.asList(SCOPES))
                .setBackOff(new ExponentialBackOff());

        getResultsFromApi();
    }

    /**
     * Attempt to call the API, after verifying that all the preconditions are
     * satisfied. The preconditions are: Google Play Services installed, an
     * account was selected and the device currently has online access. If any
     * of the preconditions are not satisfied, the app will prompt the user as
     * appropriate.
     */
    private void getResultsFromApi() {
        if (!isGooglePlayServicesAvailable()) {
            acquireGooglePlayServices();
        } else if (mCredential.getSelectedAccountName() == null) {
            chooseAccount();
        } else if (!isDeviceOnline()) {
            mOutputText.setText(R.string.no_network);
        } else {
            new MakeRequestTask(mCredential).execute();
        }
    }

    /**
     * Attempts to set the account used with the API credentials. If an account
     * name was previously saved it will use that one; otherwise an account
     * picker dialog will be shown to the user. Note that the setting the
     * account to use with the credentials object requires the app to have the
     * GET_ACCOUNTS permission, which is requested here if it is not already
     * present. The AfterPermissionGranted annotation indicates that this
     * function will be rerun automatically whenever the GET_ACCOUNTS permission
     * is granted.
     */
    @AfterPermissionGranted(REQUEST_PERMISSION_GET_ACCOUNTS)
    private void chooseAccount() {
        if (EasyPermissions.hasPermissions(
                this, Manifest.permission.GET_ACCOUNTS)) {
            String accountName = getPreferences(Context.MODE_PRIVATE)
                    .getString(PREF_ACCOUNT_NAME, null);
            if (accountName != null) {
                mCredential.setSelectedAccountName(accountName);
                getResultsFromApi();
            } else {
                // Start a dialog from which the user can choose an account
                startActivityForResult(
                        mCredential.newChooseAccountIntent(),
                        REQUEST_ACCOUNT_PICKER);
            }
        } else {
            // Request the GET_ACCOUNTS permission via a user dialog
            EasyPermissions.requestPermissions(
                    this,
                    "This app needs to access your Google account (via Contacts).",
                    REQUEST_PERMISSION_GET_ACCOUNTS,
                    Manifest.permission.GET_ACCOUNTS);
        }
    }

    /**
     * Called when an activity launched here (specifically, AccountPicker
     * and authorization) exits, giving you the requestCode you started it with,
     * the resultCode it returned, and any additional data from it.
     *
     * @param requestCode code indicating which activity result is incoming.
     * @param resultCode  code indicating the result of the incoming
     *                    activity result.
     * @param data        Intent (containing result data) returned by incoming
     *                    activity result.
     */
    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_GOOGLE_PLAY_SERVICES:
                if (resultCode != RESULT_OK) {
                    mOutputText.setText(getString(R.string.requires_play_services));
                } else {
                    getResultsFromApi();
                }
                break;
            case REQUEST_ACCOUNT_PICKER:
                if (resultCode == RESULT_OK && data != null &&
                        data.getExtras() != null) {
                    String accountName =
                            data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                    if (accountName != null) {
                        SharedPreferences settings =
                                getPreferences(Context.MODE_PRIVATE);
                        SharedPreferences.Editor editor = settings.edit();
                        editor.putString(PREF_ACCOUNT_NAME, accountName);
                        editor.apply();
                        mCredential.setSelectedAccountName(accountName);
                        getResultsFromApi();
                    }
                }
                break;
            case REQUEST_AUTHORIZATION:
                if (resultCode == RESULT_OK) {
                    getResultsFromApi();
                }
                break;
        }
    }

    /**
     * Respond to requests for permissions at runtime for API 23 and above.
     *
     * @param requestCode  The request code passed in
     *                     requestPermissions(android.app.Activity, String, int, String[])
     * @param permissions  The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions
     *                     which is either PERMISSION_GRANTED or PERMISSION_DENIED. Never null.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(
                requestCode, permissions, grantResults, this);
    }

    /**
     * Callback for when a permission is granted using the EasyPermissions
     * library.
     *
     * @param requestCode The request code associated with the requested
     *                    permission
     * @param list        The requested permission list. Never null.
     */
    @Override
    public void onPermissionsGranted(int requestCode, List<String> list) {
        // Do nothing.
    }

    /**
     * Callback for when a permission is denied using the EasyPermissions
     * library.
     *
     * @param requestCode The request code associated with the requested
     *                    permission
     * @param list        The requested permission list. Never null.
     */
    @Override
    public void onPermissionsDenied(int requestCode, List<String> list) {
        // Do nothing.
    }

    /**
     * Checks whether the device currently has a network connection.
     *
     * @return true if the device has a network connection, false otherwise.
     */
    private boolean isDeviceOnline() {
        ConnectivityManager connMgr =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        return (networkInfo != null && networkInfo.isConnected());
    }

    /**
     * Check that Google Play services APK is installed and up to date.
     *
     * @return true if Google Play Services is available and up to
     * date on this device; false otherwise.
     */
    private boolean isGooglePlayServicesAvailable() {
        GoogleApiAvailability apiAvailability =
                GoogleApiAvailability.getInstance();
        final int connectionStatusCode =
                apiAvailability.isGooglePlayServicesAvailable(this);
        return connectionStatusCode == ConnectionResult.SUCCESS;
    }

    /**
     * Attempt to resolve a missing, out-of-date, invalid or disabled Google
     * Play Services installation via a user dialog, if possible.
     */
    private void acquireGooglePlayServices() {
        GoogleApiAvailability apiAvailability =
                GoogleApiAvailability.getInstance();
        final int connectionStatusCode =
                apiAvailability.isGooglePlayServicesAvailable(this);
        if (apiAvailability.isUserResolvableError(connectionStatusCode)) {
            showGooglePlayServicesAvailabilityErrorDialog(connectionStatusCode);
        }
    }

    /**
     * Display an error dialog showing that Google Play Services is missing
     * or out of date.
     *
     * @param connectionStatusCode code describing the presence (or lack of)
     *                             Google Play Services on this device.
     */
    void showGooglePlayServicesAvailabilityErrorDialog(
            final int connectionStatusCode) {
        GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        Dialog dialog = apiAvailability.getErrorDialog(
                MainActivity.this,
                connectionStatusCode,
                REQUEST_GOOGLE_PLAY_SERVICES);
        dialog.show();
    }

    /**
     * An asynchronous task that handles the Google Calendar API call.
     * Placing the API calls in their own task ensures the UI stays responsive.
     */
    private class MakeRequestTask extends AsyncTask<Void, Void, List<String>> {
        private com.google.api.services.calendar.Calendar mService = null;
        private Exception mLastError = null;

        public MakeRequestTask(GoogleAccountCredential credential) {
            HttpTransport transport = AndroidHttp.newCompatibleTransport();
            JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
            mService = new com.google.api.services.calendar.Calendar.Builder(
                    transport, jsonFactory, credential)
                    .setApplicationName("Tally Time")
                    .build();
        }

        /**
         * Background task to call Google Calendar API.
         *
         * @param params no parameters needed for this task.
         */
        @Override
        protected List<String> doInBackground(Void... params) {
            try {
                return getDataFromApi();
            } catch (Exception e) {
                mLastError = e;
                cancel(true);
                return null;
            }
        }

        /**
         * Fetch a list of the next 10 events from the primary calendar.
         *
         * @return List of Strings describing returned events.
         * @throws IOException
         */
        private List<String> getDataFromApi() throws IOException {

            // Iterate through entries in calendar list
            String pageToken = null;
            do {
                CalendarList calendarList = mService.calendarList().list().setPageToken(pageToken).execute();
                List<CalendarListEntry> items = calendarList.getItems();

                for (CalendarListEntry calendarListEntry : items) {
                    mCalendars.add(calendarListEntry.getSummary(), calendarListEntry.getId());
                    mCalendarNames.add(calendarListEntry.getSummary());
                }
                pageToken = calendarList.getNextPageToken();
            } while (pageToken != null);

            List<String> eventStrings = new ArrayList<>();
            int totalHours = 0;

            Calendar cal = Calendar.getInstance();
            cal.setFirstDayOfWeek(Calendar.MONDAY);

            if (mYear != -1 && mMonth != -1 && mDay != -1) {
                Log.i(TAG, "getDataFromApi: date: " + cal.getTime());
//                cal.set(Calendar.YEAR, mYear);
//                cal.set(Calendar.MONTH, mMonth);
//                cal.set(Calendar.DAY_OF_MONTH, mDay);
                cal.set(mYear, mMonth, mDay);
                Log.i(TAG, "getDataFromApi: date: " + cal.getTime());
            }

            cal.set(Calendar.DAY_OF_WEEK, cal.getFirstDayOfWeek());
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);

            Log.i(TAG, "getDataFromApi: day of last monday " + cal.getTime());
            DateTime now = new DateTime(cal.getTimeInMillis());

            cal.add(Calendar.DAY_OF_WEEK, 6);
            cal.set(Calendar.HOUR_OF_DAY, 23);
            cal.set(Calendar.MINUTE, 59);
            cal.set(Calendar.SECOND, 59);
            cal.set(Calendar.MILLISECOND, 999);
            DateTime endOfWeek = new DateTime(cal.getTimeInMillis());
            Log.i(TAG, "getDataFromApi: day of end of week " + cal.getTime());

            for (int i = 0; i < mSelectedCalendars.size(); i++) {
                int subTotalHours = 0;

                eventStrings.add(
                        String.format("%s %s %s", "\nSubject: ", mSelectedCalendars.get(i), "\n"));

                int calendarIndex = mCalendars.getIndexOfKey(mSelectedCalendars.get(i));
                String calendarId = mCalendars.getValue(calendarIndex);

                Events events = mService.events().list(calendarId)
                        .setTimeMin(now)
                        .setTimeMax(endOfWeek)
                        .setOrderBy("startTime")
                        .setSingleEvents(true)
                        .execute();
                List<Event> items = events.getItems();

                for (Event event : items) {
                    DateTime start = event.getStart().getDateTime();
                    if (start == null) {
                        // All-day events don't have start times, so just use
                        // the start date.
                        start = event.getStart().getDate();
                    }

                    DateTime end = event.getEnd().getDateTime();
                    if (end == null) {
                        // All-day events don't have end times, so just use
                        // the end date.
                        end = event.getEnd().getDate();
                    }

                    long duration = end.getValue() - start.getValue();
                    long hours = duration / 1000 / 60 / 60;
                    subTotalHours += hours;
                    totalHours += hours;

                    eventStrings.add(
                            String.format("%s (%s) %s", event.getSummary(), hours, event.getDescription()));
                }
                eventStrings.add(
                        String.format("%s %s %s %s", "\nTotal hours for ", mSelectedCalendars.get(i), ": ", subTotalHours));
            }

            eventStrings.add(String.format("%s (%s)", "\nTOTAL: ", totalHours));

            Log.i(TAG, "getDataFromApi: total hours from last Monday is " + totalHours);
            return eventStrings;
        }


        @Override
        protected void onPreExecute() {
            mOutputText.setText("");
            mProgress.show();
        }

        @Override
        protected void onPostExecute(List<String> output) {
            mProgress.hide();
            if (output == null || output.size() == 0) {
                mOutputText.setText(R.string.no_results);
            } else {
                output.add(0, "Timesheet for this week:");
                mOutputText.setText(TextUtils.join("\n", output));
            }
        }

        @Override
        protected void onCancelled() {
            mProgress.hide();
            if (mLastError != null) {
                if (mLastError instanceof GooglePlayServicesAvailabilityIOException) {
                    showGooglePlayServicesAvailabilityErrorDialog(
                            ((GooglePlayServicesAvailabilityIOException) mLastError)
                                    .getConnectionStatusCode());
                } else if (mLastError instanceof UserRecoverableAuthIOException) {
                    startActivityForResult(
                            ((UserRecoverableAuthIOException) mLastError).getIntent(),
                            MainActivity.REQUEST_AUTHORIZATION);
                } else {
                    mOutputText.setText(String.format("%s%s", getString(R.string.the_following_error_occured), mLastError.getMessage()));
                }
            } else {
                mOutputText.setText(R.string.request_cancelled);
            }
        }
    }

    public void getHours(View view) {
        view.setEnabled(false);
        mOutputText.setText("");
        getResultsFromApi();
        view.setEnabled(true);
    }

    public void selectCalendars(View view) {
        // Create an instance of the dialog fragment and show it
        DialogFragment dialog = new SelectCalendarsDialogFragment();
        Bundle bundle = new Bundle();
        bundle.putStringArrayList("calendarNames", mCalendarNames);
        bundle.putStringArrayList("selectedCalendars", mSelectedCalendars);
        dialog.setArguments(bundle);
        dialog.show(getSupportFragmentManager(), "SelectCalendarsDialogFragment");
    }

    public void selectWeek(View view) {
        DialogFragment newFragment = new DatePickerDialogFragment();
        newFragment.show(getSupportFragmentManager(), "datePicker");
    }

    // The dialog fragment receives a reference to this Activity through the
    // Fragment.onAttach() callback, which it uses to call the following methods
    // defined by the SelectCalendarsDialogFragment.SelectCalendarsDialogListener interface
    @Override
    public void onSelectCalendarsDialogPositiveClick(String calendar) {
        // User touched the dialog's positive button
        mSelectedCalendars.add(calendar);

        String calendarsToDisplay = "Selected calendars: \n\n";
        for (int i = 0; i < mSelectedCalendars.size(); i++) {
            calendarsToDisplay += mSelectedCalendars.get(i) + "\n";
        }
        mSelectedCalendarsTextView.setText(calendarsToDisplay);
    }

    @Override
    public void onSelectCalendarsDialogNegativeClick(String calendar) {
        // User touched the dialog's negative button
        mSelectedCalendars.remove(calendar);

        String calendarsToDisplay = "Selected calendars: \n\n";
        for (int i = 0; i < mSelectedCalendars.size(); i++) {
            calendarsToDisplay += mSelectedCalendars.get(i) + "\n";
        }
        mSelectedCalendarsTextView.setText(calendarsToDisplay);
    }

    @Override
    public void onDatePickerDialogPositiveClick(DatePicker view, int year, int month, int day) {
        mYear = year;
        mMonth = month;
        mDay = day;

        String selectedWeek = "Selected week: \n\n" + mDay + "/" + (mMonth + 1) + "/" + mYear;
        mSelectedWeekTextView.setText(selectedWeek);
    }

    @Override
    public void onDatePickerDialogNegativeClick(DatePicker view, int year, int month, int day) {

    }
}