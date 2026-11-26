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
    public volatile boolean hasGPS = false;
    private volatile Location locationGPS = null;
    private volatile Location locationNetwork = null;
    // the last position that was fresh and accurate when it arrived
    private volatile Location lastUsable = null;
    private final Context context;
    private final long TIME_DELTA = 5000;

    // a fix older or less accurate than this is not reported as the position
    private static final long MAX_FIX_AGE_MS = 60 * 1000;
    private static final float MAX_FIX_ACCURACY_M = 100;

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

    // callers check this first so the message is not built while diagnostics are off
    private boolean logging() {
        return AtomSpectraLog.isDiagnosticsEnabled(context);
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
            if (logging()) log("cannot register " + provider + ": " + e.getClass().getSimpleName());
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
            if (logging()) log("enableGPS: provider enabled=" + isGPSEnabled + ", " + state());
            if (isGPSEnabled && locationGPS == null) {
                locationGPS = locationManager
                        .getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
        }
    }

    private void disableGPS () {
        isGPSEnabled = false;
        hasGPS = isNetworkEnabled;
        locationGPS = null;
        if (logging()) log("disableGPS: " + state());
    }

    private void enableNetwork () {
        if (locationManager != null && !isNetworkEnabled) {
            if (!networkRegistered) {
                networkRegistered = registerProvider(LocationManager.NETWORK_PROVIDER);
            }
            isNetworkEnabled = networkRegistered && locationManager
                    .isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            hasGPS = isGPSEnabled || isNetworkEnabled;
            if (logging()) log("enableNetwork: provider enabled=" + isNetworkEnabled + ", " + state());
            if (isNetworkEnabled && locationNetwork == null) {
                locationNetwork = locationManager
                        .getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
        }
    }

    private void disableNetwork () {
        isNetworkEnabled = false;
        hasGPS = isGPSEnabled;
        locationNetwork = null;
        if (logging()) log("disableNetwork: " + state());
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
            if (logging()) log("fix: provider=" + location.getProvider() + ", accuracy=" + location.getAccuracy()
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
            Location usable = current();
            if (usable != null) {
                lastUsable = usable;
            }
        }
        context.sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GPS).setPackage(Constants.PACKAGE_NAME));
    }

    @Override
    public synchronized void onProviderEnabled(@NonNull String provider) {
        if (logging()) log("onProviderEnabled: " + provider + ", " + state());
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            enableGPS();
        }
        if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
            enableNetwork();
        }
        if (!hasGPS) {
            locationNetwork = null;
            locationGPS = null;
        }
        context.sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GPS).setPackage(Constants.PACKAGE_NAME));
    }

    @Override
    public synchronized void onProviderDisabled(@NonNull String provider) {
        if (logging()) log("onProviderDisabled: " + provider + ", " + state());
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            disableGPS();
        }
        if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
            disableNetwork();
        }
        context.sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GPS).setPackage(Constants.PACKAGE_NAME));
    }

    private static boolean isUsable(Location l) {
        if (l == null || !l.hasAccuracy() || l.getAccuracy() > MAX_FIX_ACCURACY_M) {
            return false;
        }
        long ageMs = (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1000000L;
        return ageMs <= MAX_FIX_AGE_MS;
    }

    // the fresh and accurate position, or null; evaluated on every call so a fix expires by itself
    private Location current() {
        if (!hasGPS) {
            return null;
        }
        Location gps = locationGPS;
        Location network = locationNetwork;
        if (!isUsable(gps)) {
            gps = null;
        }
        if (!isUsable(network)) {
            network = null;
        }
        if (gps == null) {
            return network;
        }
        if (network != null && network.getAccuracy() < gps.getAccuracy() && network.getElapsedRealtimeNanos() > gps.getElapsedRealtimeNanos() + TIME_DELTA * 1000000L) {
            return network;
        }
        return gps;
    }

    public synchronized boolean isRegistered() {
        return gpsRegistered || networkRegistered;
    }

    // the last usable position, kept when later fixes are unusable
    public Location getLocation() {
        return lastUsable;
    }

    // without a usable position the spectrum keeps the one it has
    public void applyTo(@NonNull Spectrum spectrum) {
        Location l = current();
        if (l != null) {
            spectrum.setLocation(l).updateComments();
        }
    }

    public synchronized void stopUsingGPS(){
        if (logging()) log("stopUsingGPS, " + state());
        if(locationManager != null){
            locationManager.removeUpdates(this);
        }
        isNetworkEnabled = false;
        isGPSEnabled = false;
        gpsRegistered = false;
        networkRegistered = false;
        hasGPS = false;
        locationGPS = null;
        locationNetwork = null;
        lastUsable = null;
        context.sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GPS).setPackage(Constants.PACKAGE_NAME));
    }

    public synchronized void startUsingGPS(){
        if (logging()) log("startUsingGPS, " + state());
        enableGPS();
        enableNetwork();
        if (logging()) log("startUsingGPS done, " + state());
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
