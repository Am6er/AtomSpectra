package org.fe57.atomspectra;

import java.security.InvalidParameterException;

/**
 * Shared GPS coordinate rules for map metadata.
 */
public final class MapHelper {
    private MapHelper() {
    }

    public static double parseCoordinate(String value, String fieldName) {
        try {
            return Double.parseDouble(value);
        } catch (Exception e) {
            throw new InvalidParameterException(String.format("Unable to parse gps %s: %s", fieldName, value));
        }
    }

    public static boolean isFiniteInRange(double latitude, double longitude) {
        return Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -90.0
                && latitude <= 90.0
                && longitude >= -180.0
                && longitude <= 180.0;
    }

    public static boolean isValidLocation(double latitude, double longitude) {
        return latitude != 0.0 && longitude != 0.0 && isFiniteInRange(latitude, longitude);
    }

    /** Spectrum map menu: GPSDate is authoritative; coordinates must be finite and in range. */
    public static boolean hasValidSpectrumLocation(Spectrum spectrum) {
        return spectrum != null
                && spectrum.getGPSDate() != 0
                && isFiniteInRange(spectrum.getLatitude(), spectrum.getLongitude());
    }
}
