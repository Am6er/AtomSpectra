package org.fe57.atomspectra;

import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

import androidx.annotation.NonNull;

import java.util.Locale;

public class GPSLocator implements LocationListener {
    protected LocationManager locationManager;
    private boolean isGPSEnabled = false;
    private boolean isNetworkEnabled = false;
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

    GPSLocator (@NonNull Context a) {
        locationManager = (LocationManager) a.getSystemService(Context.LOCATION_SERVICE);
        context = a;
    }

    private void enableGPS () {
        if (locationManager != null && !isGPSEnabled) {
            isGPSEnabled = locationManager
                    .isProviderEnabled(LocationManager.GPS_PROVIDER);
            hasGPS = isGPSEnabled || isNetworkEnabled;
            if (isGPSEnabled) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        MIN_TIME_BW_UPDATES,
                        MIN_DISTANCE_CHANGE_FOR_UPDATES, this);
//                Log.d("Network", "Network");
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
        locationManager.removeUpdates(this);
        locationGPS = null;
        if (isNetworkEnabled)
            enableNetwork();
    }

    private void enableNetwork () {
        if (locationManager != null && !isNetworkEnabled) {
            isNetworkEnabled = locationManager
                    .isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            hasGPS = isGPSEnabled || isNetworkEnabled;
            if (isNetworkEnabled) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        MIN_TIME_BW_UPDATES,
                        MIN_DISTANCE_CHANGE_FOR_UPDATES, this);
//                    Log.d("GPS Enabled", "GPS Enabled");
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
        locationManager.removeUpdates(this);
        locationNetwork = null;
        if (isGPSEnabled)
            enableGPS();
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
    public void onLocationChanged(@NonNull Location location) {
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
    public void onProviderEnabled(@NonNull String provider) {
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
//        context.sendBroadcast(new Intent(Constants.ACTION.ACTION_SEND_GPS)
//                .putExtra(Constants.ACTION_PARAMETERS.GPS_LATITUDE, latitude)
//                .putExtra(Constants.ACTION_PARAMETERS.GPS_LONGITUDE, longitude)
//                .putExtra(Constants.ACTION_PARAMETERS.GPS_TIME, gpsTime));
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            disableGPS();
        }
        if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
            disableNetwork();
        }
        if (!hasGPS) {
            location = null;
        }
//        context.sendBroadcast(new Intent(Constants.ACTION.ACTION_SEND_GPS)
//                .putExtra(Constants.ACTION_PARAMETERS.GPS_LATITUDE, latitude)
//                .putExtra(Constants.ACTION_PARAMETERS.GPS_LONGITUDE, longitude)
//                .putExtra(Constants.ACTION_PARAMETERS.GPS_TIME, gpsTime));
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

    public Location getLocation() {
        return location;
    }

    public void stopUsingGPS(){
        if(locationManager != null){
            locationManager.removeUpdates(this);
        }
        isNetworkEnabled = false;
        isGPSEnabled = false;
        hasGPS = false;
        location = null;
        locationGPS = null;
        locationNetwork = null;
    }

    public void startUsingGPS(){
        enableGPS();
        enableNetwork();
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
            int longSeconds = (int) Math.round(getLongitude() * 3600000);
            int longDegrees = longSeconds / 3600000;
            longSeconds = Math.abs(longSeconds % 3600000);
            int longMinutes = longSeconds / 60000;
            longSeconds %= 60000;
            String longDegree = longDegrees >= 0 ? "E" : "W";

            return  String.format(Locale.US, "%02d°%02d'%06.3f\" %s", Math.abs(longDegrees), longMinutes, longSeconds / 1000.0, longDegree);
        } catch (Exception e) {
            return String.format(Locale.US, "%8.5f", longitude) ;
        }
    }

    public static String getFormattedLongitude (double longitude) {
        try {
            int longSeconds = (int) Math.round(longitude * 3600000);
            int longDegrees = longSeconds / 3600000;
            longSeconds = Math.abs(longSeconds % 3600000);
            int longMinutes = longSeconds / 60000;
            longSeconds %= 60000;
            String longDegree = longDegrees >= 0 ? "E" : "W";

            return  String.format(Locale.US, "%02d°%02d'%06.3f\" %s", Math.abs(longDegrees), longMinutes, longSeconds / 1000.0, longDegree);
        } catch (Exception e) {
            return String.format(Locale.US, "%8.5f", longitude) ;
        }
    }
}
