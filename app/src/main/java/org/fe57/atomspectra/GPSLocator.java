package org.fe57.atomspectra;

import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.util.Locale;

public class GPSLocator implements LocationListener {
    protected LocationManager locationManager;
    private boolean isGPSEnabled = false;
    private boolean isNetworkEnabled = false;
    private boolean gpsRegistered = false;
    private boolean networkRegistered = false;
    public boolean hasGPS = false;
    private Location location = null; // Location
    private Location locationGPS = null;
    private Location locationNetwork = null;
    private final Context context;
    private final long TIME_DELTA = 5000;

    // The minimum distance to change Updates in meters
    private static final long MIN_DISTANCE_CHANGE_FOR_UPDATES = 0; // 0 meters

    // The minimum time between updates in milliseconds
    private static final long MIN_TIME_BW_UPDATES = 1000 * 5; // 5 seconds

    // fixes are logged only after a gap this long, or at this interval, to keep the log small
    private static final long FIX_GAP_LOG_MS = 2 * 60 * 1000;
    private static final long FIX_HEARTBEAT_LOG_MS = 10 * 60 * 1000;
    private long lastFixElapsed = 0;
    private long lastFixLoggedElapsed = 0;

    GPSLocator (@NonNull Context a) {
        locationManager = (LocationManager) a.getSystemService(Context.LOCATION_SERVICE);
        context = a;
    }

    private void log(String message) {
        AtomSpectraLog.detail(context, LogTag.GPS, message);
    }

    private String fixAge() {
        return lastFixElapsed == 0 ? "no fix yet" : ((SystemClock.elapsedRealtime() - lastFixElapsed) / 1000) + " s since last fix";
    }

    private String state() {
        return "gpsFlag=" + isGPSEnabled + ", networkFlag=" + isNetworkEnabled + ", hasGPS=" + hasGPS + ", " + fixAge();
    }

    // the listener stays registered while a provider is off, so onProviderEnabled can reach it
    private boolean registerProvider(String provider) {
        try {
            locationManager.requestLocationUpdates(provider, MIN_TIME_BW_UPDATES, MIN_DISTANCE_CHANGE_FOR_UPDATES, this);
            return true;
        } catch (IllegalArgumentException | SecurityException e) {
            log("cannot register " + provider + ": " + e.getClass().getSimpleName());
            return false;
        }
    }

    private void enableGPS () {
        if (locationManager != null && !isGPSEnabled) {
            if (!gpsRegistered) {
                gpsRegistered = registerProvider(LocationManager.GPS_PROVIDER);
            }
            isGPSEnabled = gpsRegistered && locationManager
                    .isProviderEnabled(LocationManager.GPS_PROVIDER);
            hasGPS = isGPSEnabled || isNetworkEnabled;
            log("enableGPS: provider enabled=" + isGPSEnabled + ", " + state());
            if (isGPSEnabled) {
                if (locationGPS == null) {
                    locationGPS = locationManager
                            .getLastKnownLocation(LocationManager.GPS_PROVIDER);
                }
                if (locationGPS != null) {
                    if (locationNetwork != null && locationNetwork.getAccuracy() < locationGPS.getAccuracy() && locationNetwork.getTime() > locationGPS.getTime() + TIME_DELTA)
                        location = locationNetwork;
                    else
                        location = locationGPS;
                } else {
                    location = locationNetwork;
                }
            }
        }
    }

    private void disableGPS () {
        isGPSEnabled = false;
        hasGPS = isNetworkEnabled;
        locationGPS = null;
        location = locationNetwork;
        log("disableGPS: " + state());
    }

    private void enableNetwork () {
        if (locationManager != null && !isNetworkEnabled) {
            if (!networkRegistered) {
                networkRegistered = registerProvider(LocationManager.NETWORK_PROVIDER);
            }
            isNetworkEnabled = networkRegistered && locationManager
                    .isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            hasGPS = isGPSEnabled || isNetworkEnabled;
            log("enableNetwork: provider enabled=" + isNetworkEnabled + ", " + state());
            if (isNetworkEnabled) {
                if (locationNetwork == null) {
                    locationNetwork = locationManager
                            .getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    if (locationNetwork != null) {
                        if (locationGPS != null) {
                            if ((locationNetwork.getAccuracy() < locationGPS.getAccuracy() && locationNetwork.getTime() > locationGPS.getTime() + TIME_DELTA))
                                location = locationNetwork;
                            else
                                location = locationGPS;
                        }
                        else
                            location = locationNetwork;
                    } else {
                        location = locationGPS;
                    }
                }
            }
        }
    }

    private void disableNetwork () {
        isNetworkEnabled = false;
        hasGPS = isGPSEnabled;
        locationNetwork = null;
        location = locationGPS;
        log("disableNetwork: " + state());
    }

    //for Android 7.0 not to drop
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        //
    }

    @Override
    public void onFlushComplete(int requestCode) {
        //
    }

    @Override
    public synchronized void onLocationChanged(@NonNull Location location) {
        long now = SystemClock.elapsedRealtime();
        if (lastFixElapsed == 0 || now - lastFixElapsed > FIX_GAP_LOG_MS || now - lastFixLoggedElapsed > FIX_HEARTBEAT_LOG_MS) {
            log("fix: provider=" + location.getProvider() + ", accuracy=" + location.getAccuracy()
                    + " m, " + (lastFixElapsed == 0 ? "first fix" : ((now - lastFixElapsed) / 1000) + " s since previous fix")
                    + ", hasGPS=" + hasGPS);
            lastFixLoggedElapsed = now;
        }
        lastFixElapsed = now;
        if (hasGPS) {
            if (LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
                locationGPS = new Location(location);
            } else if (LocationManager.NETWORK_PROVIDER.equals(location.getProvider())) {
                locationNetwork = new Location(location);
            }
            if (locationGPS == null) {
                this.location = locationNetwork;
            } else {
                if (locationNetwork == null) {
                    this.location = locationGPS;
                } else {
                    if(locationNetwork.getAccuracy() < locationGPS.getAccuracy() && locationNetwork.getTime() > locationGPS.getTime() + TIME_DELTA)
                    this.location = locationNetwork;
                    else
                        this.location = locationGPS;
                }
            }
        } else {
            this.location = null;
        }
        context.sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GPS).setPackage(Constants.PACKAGE_NAME));
    }

    @Override
    public synchronized void onProviderEnabled(@NonNull String provider) {
        log("onProviderEnabled: " + provider + ", " + state());
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            enableGPS();
        }
        if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
            enableNetwork();
        }
        if (!hasGPS) {
            locationNetwork = null;
            locationGPS = null;
            location = null;
        }
        context.sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GPS).setPackage(Constants.PACKAGE_NAME));
    }

    @Override
    public synchronized void onProviderDisabled(@NonNull String provider) {
        log("onProviderDisabled: " + provider + ", " + state());
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            disableGPS();
        }
        if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
            disableNetwork();
        }
        if (!hasGPS) {
            location = null;
        }
        context.sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GPS).setPackage(Constants.PACKAGE_NAME));
    }

    public double getLatitude() {
        return hasGPS && location != null ? location.getLatitude() : 0;
    }

    public double getLongitude() {
        return hasGPS && location != null ? location.getLongitude() : 0;
    }

    public long getTime () {
        return hasGPS  && location != null ? location.getTime() : 0;
    }

    public synchronized boolean isRegistered() {
        return gpsRegistered || networkRegistered;
    }

    public Location getLocation() {
        return location;
    }

    public synchronized void stopUsingGPS(){
        log("stopUsingGPS, " + state());
        if(locationManager != null){
            locationManager.removeUpdates(this);
        }
        isNetworkEnabled = false;
        isGPSEnabled = false;
        gpsRegistered = false;
        networkRegistered = false;
        hasGPS = false;
        location = null;
        locationGPS = null;
        locationNetwork = null;
        context.sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GPS).setPackage(Constants.PACKAGE_NAME));
    }

    public synchronized void startUsingGPS(){
        log("startUsingGPS, " + state());
        enableGPS();
        enableNetwork();
        log("startUsingGPS done, " + state());
    }

    public String getFormattedLatitude () {
        double latitude = location != null ? location.getLatitude() : 0;
        try {
            long latSeconds = Math.round(getLatitude() * 3600000);
            long latDegrees = latSeconds / 3600000;
            latSeconds = Math.abs(latSeconds % 3600000);
            long latMinutes = latSeconds / 60000;
            latSeconds %= 60000;
            String latDegree = latDegrees >= 0 ? "N" : "S";

            return  String.format(Locale.US, "%02d°%02d'%06.3f\" %s", Math.abs(latDegrees), latMinutes, latSeconds / 1000.0, latDegree);
        } catch (Exception ignored) {
            return String.format(Locale.US, "%8.5f", latitude);
        }
    }

    public static String getFormattedLatitude (double latitude) {
        try {
            long latSeconds = Math.round(latitude * 3600000);
            long latDegrees = latSeconds / 3600000;
            latSeconds = Math.abs(latSeconds % 3600000);
            long latMinutes = latSeconds / 60000;
            latSeconds %= 60000;
            String latDegree = latDegrees >= 0 ? "N" : "S";

            return  String.format(Locale.US, "%02d°%02d'%06.3f\" %s", Math.abs(latDegrees), latMinutes, latSeconds / 1000.0, latDegree);
        } catch (Exception ignored) {
            return String.format(Locale.US, "%8.5f", latitude);
        }
    }

    public String getFormattedLongitude () {
        double longitude = location != null ? location.getLongitude() : 0;
        try {
            long longSeconds = Math.round(getLongitude() * 3600000);
            long longDegrees = longSeconds / 3600000;
            longSeconds = Math.abs(longSeconds % 3600000);
            long longMinutes = longSeconds / 60000;
            longSeconds %= 60000;
            String longDegree = longDegrees >= 0 ? "E" : "W";

            return  String.format(Locale.US, "%02d°%02d'%06.3f\" %s", Math.abs(longDegrees), longMinutes, longSeconds / 1000.0, longDegree);
        } catch (Exception e) {
            return String.format(Locale.US, "%8.5f", longitude) ;
        }
    }

    public static String getFormattedLongitude (double longitude) {
        try {
            long longSeconds = Math.round(longitude * 3600000);
            long longDegrees = longSeconds / 3600000;
            longSeconds = Math.abs(longSeconds % 3600000);
            long longMinutes = longSeconds / 60000;
            longSeconds %= 60000;
            String longDegree = longDegrees >= 0 ? "E" : "W";

            return  String.format(Locale.US, "%02d°%02d'%06.3f\" %s", Math.abs(longDegrees), longMinutes, longSeconds / 1000.0, longDegree);
        } catch (Exception e) {
            return String.format(Locale.US, "%8.5f", longitude) ;
        }
    }
}
