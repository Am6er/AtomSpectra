package org.fe57.atomspectra;

import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.util.JsonWriter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Immutable read-only device position for the map. Separate from measurement GPS tagging.
 */
public final class DeviceLocationSnapshot {
    public static final long STALE_THRESHOLD_MS = 5L * 60L * 1000L;

    public static final DeviceLocationSnapshot UNAVAILABLE =
            new DeviceLocationSnapshot(false, 0, 0, 0, Float.NaN, "");

    public final boolean available;
    public final double latitude;
    public final double longitude;
    public final long fixTimestampMs;
    /** {@link Float#NaN} when the provider did not report accuracy. */
    public final float accuracyMeters;
    @NonNull
    public final String provider;

    private DeviceLocationSnapshot(boolean available, double latitude, double longitude,
                                   long fixTimestampMs, float accuracyMeters, @NonNull String provider) {
        this.available = available;
        this.latitude = latitude;
        this.longitude = longitude;
        this.fixTimestampMs = fixTimestampMs;
        this.accuracyMeters = accuracyMeters;
        this.provider = provider;
    }

    @NonNull
    public static DeviceLocationSnapshot capture(@NonNull Context context,
                                                 @Nullable GPSLocator locator) {
        boolean taggingEnabled = PrefHelper.getASSharedPreferences(context)
                .getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, Constants.ADD_GPS_TO_FILES_DEFAULT);
        if (!taggingEnabled || !AppPermissions.isLocationGranted(context) || locator == null || !locator.hasGPS) {
            return UNAVAILABLE;
        }
        Location location = locator.getLocation();
        if (location == null) {
            return UNAVAILABLE;
        }
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        if (!MapHelper.isFiniteInRange(lat, lon)) {
            return UNAVAILABLE;
        }
        long fixTime = location.getTime();
        if (fixTime <= 0 || isStale(fixTime, System.currentTimeMillis())) {
            return UNAVAILABLE;
        }
        float accuracy = location.hasAccuracy() ? location.getAccuracy() : Float.NaN;
        String provider = location.getProvider() != null ? location.getProvider() : "";
        return new DeviceLocationSnapshot(true, lat, lon, fixTime, accuracy, provider);
    }

    public static boolean isStale(long fixTimestampMs, long nowMs) {
        return nowMs - fixTimestampMs > STALE_THRESHOLD_MS;
    }

    @NonNull
    public Intent toBroadcastIntent() {
        Intent intent = new Intent(Constants.ACTION.ACTION_DEVICE_LOCATION_UPDATED)
                .setPackage(Constants.PACKAGE_NAME);
        intent.putExtra(Constants.ACTION_PARAMETERS.DEVICE_LOCATION_AVAILABLE, available);
        intent.putExtra(Constants.ACTION_PARAMETERS.DEVICE_LOCATION_LATITUDE, latitude);
        intent.putExtra(Constants.ACTION_PARAMETERS.DEVICE_LOCATION_LONGITUDE, longitude);
        intent.putExtra(Constants.ACTION_PARAMETERS.DEVICE_LOCATION_TIME, fixTimestampMs);
        intent.putExtra(Constants.ACTION_PARAMETERS.DEVICE_LOCATION_ACCURACY, accuracyMeters);
        intent.putExtra(Constants.ACTION_PARAMETERS.DEVICE_LOCATION_PROVIDER, provider);
        return intent;
    }

    @NonNull
    public byte[] toJson() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        JsonWriter writer = new JsonWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.beginObject();
        writer.name("available").value(available);
        if (available) {
            writer.name("latitude").value(latitude);
            writer.name("longitude").value(longitude);
            writer.name("timestamp").value(fixTimestampMs);
            if (Float.isFinite(accuracyMeters)) {
                writer.name("accuracyMeters").value(accuracyMeters);
            } else {
                writer.name("accuracyMeters").nullValue();
            }
            writer.name("provider").value(provider);
        }
        writer.endObject();
        writer.close();
        return out.toByteArray();
    }
}
