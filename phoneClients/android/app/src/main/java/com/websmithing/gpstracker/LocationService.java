package com.websmithing.gpstracker;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public class LocationService extends Service implements
        ConnectionCallbacks,
        OnConnectionFailedListener,
        LocationListener {

    private static final String TAG = "LocationService";

    // use the websmithing defaultUploadWebsite for testing and then check your
    // location with your browser here: https://www.websmithing.com/gpstracker/displaymap.php
    private String defaultUploadWebsite;

    private boolean currentlyProcessingLocation = false;

    private GoogleApiClient mGoogleApiClient;
    private LocationRequest mLocationRequest;
    private Location mCurrentLocation;

    @Override
    public void onCreate() {
        Log.d(TAG, "on create command");
        super.onCreate();
        buildGoogleApiClient();
        createLocationRequest();
        defaultUploadWebsite = getString(R.string.default_upload_website);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // if we are currently trying to get a location and the alarm manager has called this again,
        // no need to start processing a new location.
        Log.d(TAG, "on start command");
        if (!currentlyProcessingLocation) {
            currentlyProcessingLocation = true;
            Log.d(TAG, "connecting to google api");
            mGoogleApiClient.connect();
        }

        return START_NOT_STICKY;
    }

    protected void sendLocationDataToWebsite(Location location) {
        // formatted for mysql datetime format
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        dateFormat.setTimeZone(TimeZone.getDefault());
        Date date = new Date(location.getTime());

        SharedPreferences sharedPreferences = this.getSharedPreferences("com.websmithing.gpstracker.prefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        float totalDistanceInMeters = sharedPreferences.getFloat("totalDistanceInMeters", 0f);

        boolean firstTimeGettingPosition = sharedPreferences.getBoolean("firstTimeGettingPosition", true);

        if (firstTimeGettingPosition) {
            editor.putBoolean("firstTimeGettingPosition", false);
        } else {
            Location previousLocation = new Location("");
            previousLocation.setLatitude(sharedPreferences.getFloat("previousLatitude", 0f));
            previousLocation.setLongitude(sharedPreferences.getFloat("previousLongitude", 0f));

            float distance = location.distanceTo(previousLocation);
            totalDistanceInMeters += distance;
            editor.putFloat("totalDistanceInMeters", totalDistanceInMeters);
        }

        editor.putFloat("previousLatitude", (float)location.getLatitude());
        editor.putFloat("previousLongitude", (float)location.getLongitude());
        editor.apply();

        final RequestParams requestParams = new RequestParams();
        requestParams.put("latitude", Double.toString(location.getLatitude()));
        requestParams.put("longitude", Double.toString(location.getLongitude()));

        Double speedInMilesPerHour = location.getSpeed()* 2.2369;
        requestParams.put("speed",  Integer.toString(speedInMilesPerHour.intValue()));

        try {
            requestParams.put("date", URLEncoder.encode(dateFormat.format(date), "UTF-8"));
        } catch (UnsupportedEncodingException e) {}

        requestParams.put("locationmethod", location.getProvider());

        if (totalDistanceInMeters > 0) {
            requestParams.put("distance", String.format("%.1f", totalDistanceInMeters / 1609)); // in miles,
        } else {
            requestParams.put("distance", "0.0"); // in miles
        }

        requestParams.put("username", sharedPreferences.getString("userName", ""));
        requestParams.put("phonenumber", sharedPreferences.getString("appID", "")); // uuid
        requestParams.put("sessionid", sharedPreferences.getString("sessionID", "")); // uuid

        Double accuracyInFeet = location.getAccuracy()* 3.28;
        requestParams.put("accuracy",  Integer.toString(accuracyInFeet.intValue()));

        Double altitudeInFeet = location.getAltitude() * 3.28;
        requestParams.put("extrainfo",  Integer.toString(altitudeInFeet.intValue()));

        requestParams.put("eventtype", "android");

        Float direction = location.getBearing();
        requestParams.put("direction",  Integer.toString(direction.intValue()));

        final String uploadWebsite = sharedPreferences.getString("defaultUploadWebsite", defaultUploadWebsite);

        LoopjHttpClient.get(uploadWebsite, requestParams, new AsyncHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, org.apache.http.Header[] headers, byte[] responseBody) {
                LoopjHttpClient.debugLoopJ(TAG, "sendLocationDataToWebsite - success", uploadWebsite, requestParams, responseBody, headers, statusCode, null);
                stopSelf();
            }
            @Override
            public void onFailure(int statusCode, org.apache.http.Header[] headers, byte[] errorResponse, Throwable e) {
                LoopjHttpClient.debugLoopJ(TAG, "sendLocationDataToWebsite - failure", uploadWebsite, requestParams, errorResponse, headers, statusCode, e);
                stopSelf();
            }
        });
    }

    @Override
    public void onDestroy() {
        mGoogleApiClient.disconnect();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) {
            Log.e(TAG, "position: " + location.getLatitude() + ", " + location.getLongitude() + " accuracy: " + location.getAccuracy());

            // we have our desired accuracy of 500 meters so lets quit this service,
            // onDestroy will be called and stop our location uodates
            if (location.getAccuracy() < 500.0f) {
                stopLocationUpdates();
                sendLocationDataToWebsite(location);
            }
        }
    }

    private void stopLocationUpdates() {
        LocationServices.FusedLocationApi.removeLocationUpdates(
                mGoogleApiClient, this);
    }

    /**
     * Called by Location Services when the request to connect the
     * client finishes successfully. At this point, you can
     * request the current location or start periodic updates
     */
    @Override
    public void onConnected(Bundle bundle) {
        Log.d(TAG, "onConnected");

        if (mGoogleApiClient.isConnected()) {
            startLocationUpdates();
        }
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.e(TAG, "onDisconnected");

        stopLocationUpdates();
        stopSelf();
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.e(TAG, "onConnectionFailed");

        stopLocationUpdates();
        stopSelf();
    }

    protected synchronized void buildGoogleApiClient() {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
    }

    protected void createLocationRequest() {
        mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(1000);
        mLocationRequest.setFastestInterval(1000);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }

    protected void startLocationUpdates() {
        LocationServices.FusedLocationApi.requestLocationUpdates(
                mGoogleApiClient, mLocationRequest, this);
    }
}
