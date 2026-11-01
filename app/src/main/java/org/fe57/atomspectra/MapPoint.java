package org.fe57.atomspectra;

/**
 * Immutable per-row map metadata aligned with a spectrogram row.
 */
public final class MapPoint {
    public final double latitude;
    public final double longitude;
    public final long totalCounts;
    public final long timestamp;
    public final float durationSeconds;

    public MapPoint(double latitude, double longitude, long totalCounts, long timestamp, double durationSeconds) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.totalCounts = totalCounts;
        this.timestamp = timestamp;
        this.durationSeconds = (float) durationSeconds;
    }
}
