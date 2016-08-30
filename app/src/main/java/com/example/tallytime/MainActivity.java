package com.example.tallytime;

import android.Manifest;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.DatePicker;
import android.widget.TextView;

import com.example.tallytime.util.Globals;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.drive.DriveApi;
import com.google.android.gms.drive.MetadataChangeSet;
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
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.GenericTypeIndicator;
import com.google.firebase.database.ValueEventListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
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
        DatePickerDialogFragment.DatePickerDialogListener,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {

    private static final String TAG = "MainActivity";

    private GoogleAccountCredential mCredential;
    private ProgressDialog mProgress;
    private ArrayMap<String, String> mCalendars;
    private ArrayList<String> mCalendarNames;
    private ArrayList<String> mSelectedCalendars;
    private int mYear;
    private int mMonth;
    private int mDay;

    // firebase
    private DatabaseReference mDatabase;
    private String mUserId;

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

    // Drive stuff
    private static final int REQUEST_CODE_CAPTURE_IMAGE = 1;
    private static final int REQUEST_CODE_CREATOR = 2;
    private static final int REQUEST_CODE_RESOLUTION = 3;

    private GoogleApiClient mGoogleApiClient;
    private Bitmap mBitmapToSave;

    /**
     * Create the main activity.
     *
     * @param savedInstanceState previously saved instance data.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Firebase database
        if (!Globals.getInstance().isFirebasePersistenceSet()) {
            Globals.getInstance().setFirebasePersistenceEnabled();
        }

        mDatabase = FirebaseDatabase.getInstance().getReference();

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            // User is signed in
            mUserId = user.getUid();
        } else {
            // User is signed out
            mUserId = "invalidUser";
        }

        mProgress = new ProgressDialog(this);
        mProgress.setMessage("Getting calendar hours...");
        mCalendars = new ArrayMap<>();
        mCalendarNames = new ArrayList<>();
        mYear = -1;
        mMonth = -1;
        mDay = -1;

        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        // Initialise credentials and service object.
        mCredential = GoogleAccountCredential.usingOAuth2(
                getApplicationContext(), Arrays.asList(SCOPES))
                .setBackOff(new ExponentialBackOff());

        getSelectedCalendarsFromFirebase();
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

    private void getSelectedCalendarsFromFirebase() {
        ValueEventListener calendarListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                GenericTypeIndicator<List<String>> t = new GenericTypeIndicator<List<String>>() {};
                ArrayList<String> calendars = (ArrayList<String>) dataSnapshot.getValue(t);

                if (calendars != null) {
                    mSelectedCalendars = calendars;
                    displaySelectedCalendars();
                } else {
                    mSelectedCalendars = new ArrayList<>();
                    displaySelectedCalendars();
                }

                getResultsFromApi();
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                // Getting calendars failed, log a message
                Log.w(TAG, "loadCalendars:onCancelled", databaseError.toException());
            }
        };
        mDatabase.child("selectedCalendars").child(mUserId).addValueEventListener(calendarListener);
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
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

            /*
            * Drive stuff
            * */
            case REQUEST_CODE_CAPTURE_IMAGE:
                // Called after a photo has been taken.
                if (resultCode == Activity.RESULT_OK) {
                    // Store the image data as a bitmap for writing later.
                    mBitmapToSave = (Bitmap) data.getExtras().get("data");
                    saveFileToDrive();
                }
                break;
            case REQUEST_CODE_CREATOR:
                // Called after a file is saved to Drive.
                if (resultCode == RESULT_OK) {
                    Log.i(TAG, "Successfully saved to drive.");
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
            // Clear existing lists
            mCalendars.clear();
            mCalendarNames.clear();

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
            double totalHours = 0;

            Calendar cal = Calendar.getInstance();
            cal.setFirstDayOfWeek(Calendar.MONDAY);

            if (mYear != -1 && mMonth != -1 && mDay != -1) {
                Log.i(TAG, "getDataFromApi: date: " + cal.getTime());
                cal.set(mYear, mMonth, mDay);
                Log.i(TAG, "getDataFromApi: date: " + cal.getTime());
            } else {
                Log.d(TAG, "getDataFromApi: YEAR: " + cal.get(Calendar.YEAR));
                Log.d(TAG, "getDataFromApi: MONTH: " + cal.get(Calendar.MONTH));
                Log.d(TAG, "getDataFromApi: DAY: " + cal.get(Calendar.DAY_OF_MONTH));
                mYear = cal.get(Calendar.YEAR);
                mMonth = cal.get(Calendar.MONTH);
                mDay = cal.get(Calendar.DAY_OF_MONTH);
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
                double subTotalHours = 0;

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

                    double duration = end.getValue() - start.getValue();
                    double hours = duration / 1000 / 60 / 60;
                    subTotalHours += hours;
                    totalHours += hours;

                    DateFormat dateInstance = SimpleDateFormat.getDateInstance(DateFormat.SHORT);
                    DateFormat timeInstance = SimpleDateFormat.getTimeInstance(DateFormat.SHORT);

                    eventStrings.add(
                            String.format("%s %s %s",
                                    dateInstance.format(start.getValue()) + ", ",
                                    timeInstance.format(start.getValue()) + ", ",
                                    hours + ", " + event.getDescription()));
                }
                eventStrings.add(
                        String.format("%s %s %s %s", "\nTotal hours for ", mSelectedCalendars.get(i), ": ", subTotalHours));
            }

            eventStrings.add(String.format("%s %s", "\nTOTAL: ", totalHours));

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
            displaySelectedWeek();
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
    }

    @Override
    public void onSelectCalendarsDialogNegativeClick(String calendar) {
        // User touched the dialog's negative button
        mSelectedCalendars.remove(calendar);
    }

    @Override
    public void onSelectCalendarsDialogOkClick() {
        mDatabase.child("selectedCalendars").child(mUserId).setValue(mSelectedCalendars);
        displaySelectedCalendars();
    }


    public void displaySelectedCalendars() {
        String calendarsToDisplay = "";
        for (int i = 0; i < mSelectedCalendars.size(); i++) {
            calendarsToDisplay += mSelectedCalendars.get(i);
            if ((i + 1) < mSelectedCalendars.size()) {
                calendarsToDisplay += "\n";
            }
        }
        if (calendarsToDisplay.contentEquals("")) {
            mSelectedCalendarsTextView.setText(R.string.no_calendars_selected);
        } else {
            mSelectedCalendarsTextView.setText(calendarsToDisplay);
        }
    }

    @Override
    public void onDatePickerDialogPositiveClick(DatePicker view, int year, int month, int day) {
        mYear = year;
        mMonth = month;
        mDay = day;
        displaySelectedWeek();
        getResultsFromApi();
    }

    public void displaySelectedWeek() {
        String selectedWeek = mDay + "/" + (mMonth + 1) + "/" + mYear;
        mSelectedWeekTextView.setText(selectedWeek);
    }

    /*
    * Drive stuff
    *
    * */
    public void addImageToDrive(View view) {
        if (mBitmapToSave == null) {
            // This activity has no UI of its own. Just start the camera.
            startActivityForResult(new Intent(MediaStore.ACTION_IMAGE_CAPTURE),
                    REQUEST_CODE_CAPTURE_IMAGE);
        }
    }

    public void addToDrive(View view) {
//        String filename = "myfile";
//        String string = "Hello world!";
//        FileOutputStream outputStream;
//
//        try {
//            outputStream = openFileOutput(filename, Context.MODE_PRIVATE);
//            outputStream.write(string.getBytes());
//            outputStream.close();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }

        // Start by creating a new contents, and setting a callback.
        Log.i(TAG, "Creating new contents.");
//        final Bitmap image = mBitmapToSave;
        Drive.DriveApi.newDriveContents(mGoogleApiClient)
                .setResultCallback(new ResultCallback<DriveApi.DriveContentsResult>() {

                    @Override
                    public void onResult(DriveApi.DriveContentsResult result) {
                        // If the operation was not successful, we cannot do anything
                        // and must
                        // fail.
                        if (!result.getStatus().isSuccess()) {
                            Log.i(TAG, "Failed to create new contents.");
                            return;
                        }
                        // Otherwise, we can write our data to the new contents.
                        Log.i(TAG, "New contents created.");
                        // Get an output stream for the contents.
                        OutputStream outputStream = result.getDriveContents().getOutputStream();
                        // Write the bitmap data from it.
//                        ByteArrayOutputStream bitmapStream = new ByteArrayOutputStream();
//                        image.compress(Bitmap.CompressFormat.PNG, 100, bitmapStream);

                        try {
                            outputStream.write(mOutputText.getText().toString().getBytes());
                        } catch (IOException e1) {
                            Log.i(TAG, "Unable to write file contents.");
                        }
                        // Create the initial metadata - MIME type and title.
                        // Note that the user will be able to change the title later.
                        MetadataChangeSet metadataChangeSet = new MetadataChangeSet.Builder()
                                .setMimeType("text/csv").setTitle("Timesheet.csv").build();
                        // Create an intent for the file chooser, and start it.
                        IntentSender intentSender = Drive.DriveApi
                                .newCreateFileActivityBuilder()
                                .setInitialMetadata(metadataChangeSet)
                                .setInitialDriveContents(result.getDriveContents())
                                .build(mGoogleApiClient);
                        try {
                            startIntentSenderForResult(
                                    intentSender, REQUEST_CODE_CREATOR, null, 0, 0, 0);
                        } catch (IntentSender.SendIntentException e) {
                            Log.i(TAG, "Failed to launch file chooser.");
                        }
                    }
                });
    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        // Called whenever the API client fails to connect.
        Log.i(TAG, "GoogleApiClient connection failed: " + result.toString());
        if (!result.hasResolution()) {
            // show the localized error dialog.
            GoogleApiAvailability.getInstance().getErrorDialog(this, result.getErrorCode(), 0).show();
            return;
        }
        // The failure has a resolution. Resolve it.
        // Called typically when the app is not yet authorized, and an
        // authorization
        // dialog is displayed to the user.
        try {
            result.startResolutionForResult(this, REQUEST_CODE_RESOLUTION);
        } catch (IntentSender.SendIntentException e) {
            Log.e(TAG, "Exception while starting resolution activity", e);
        }
    }

    @Override
    public void onConnected(Bundle connectionHint) {
        Log.i(TAG, "API client connected.");
    }

    @Override
    public void onConnectionSuspended(int cause) {
        Log.i(TAG, "GoogleApiClient connection suspended");
    }

    /**
     * Create a new file and save it to Drive.
     */
    private void saveFileToDrive() {
        // Start by creating a new contents, and setting a callback.
        Log.i(TAG, "Creating new contents.");
        final Bitmap image = mBitmapToSave;
        Drive.DriveApi.newDriveContents(mGoogleApiClient)
                .setResultCallback(new ResultCallback<DriveApi.DriveContentsResult>() {

                    @Override
                    public void onResult(DriveApi.DriveContentsResult result) {
                        // If the operation was not successful, we cannot do anything
                        // and must
                        // fail.
                        if (!result.getStatus().isSuccess()) {
                            Log.i(TAG, "Failed to create new contents.");
                            return;
                        }
                        // Otherwise, we can write our data to the new contents.
                        Log.i(TAG, "New contents created.");
                        // Get an output stream for the contents.
                        OutputStream outputStream = result.getDriveContents().getOutputStream();
                        // Write the bitmap data from it.
                        ByteArrayOutputStream bitmapStream = new ByteArrayOutputStream();
                        image.compress(Bitmap.CompressFormat.PNG, 100, bitmapStream);
                        try {
                            outputStream.write(bitmapStream.toByteArray());
                        } catch (IOException e1) {
                            Log.i(TAG, "Unable to write file contents.");
                        }
                        // Create the initial metadata - MIME type and title.
                        // Note that the user will be able to change the title later.
                        MetadataChangeSet metadataChangeSet = new MetadataChangeSet.Builder()
                                .setMimeType("image/jpeg").setTitle("Android Photo.png").build();
                        // Create an intent for the file chooser, and start it.
                        IntentSender intentSender = Drive.DriveApi
                                .newCreateFileActivityBuilder()
                                .setInitialMetadata(metadataChangeSet)
                                .setInitialDriveContents(result.getDriveContents())
                                .build(mGoogleApiClient);
                        try {
                            startIntentSenderForResult(
                                    intentSender, REQUEST_CODE_CREATOR, null, 0, 0, 0);
                        } catch (IntentSender.SendIntentException e) {
                            Log.i(TAG, "Failed to launch file chooser.");
                        }
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mGoogleApiClient == null) {
            // Create the API client and bind it to an instance variable.
            // We use this instance as the callback for connection and connection
            // failures.
            // Since no account name is passed, the user is prompted to choose.
            mGoogleApiClient = new GoogleApiClient.Builder(this)
                    .addApi(Drive.API)
                    .addScope(Drive.SCOPE_FILE)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
        }
        // Connect the client. Once connected, the camera is launched.
        mGoogleApiClient.connect();
    }

    @Override
    protected void onPause() {
        if (mGoogleApiClient != null) {
            mGoogleApiClient.disconnect();
        }
        super.onPause();
    }
}