package org.fe57.atomspectra;

/** Immutable per-row map metadata aligned with a spectrogram row. */
public final class MapPoint {
    public final double latitude;
    public final double longitude;
    public final float totalCps;
    public final long timestamp;

    public MapPoint(double latitude, double longitude, float totalCps, long timestamp) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.totalCps = totalCps;
        this.timestamp = timestamp;
    }
}
