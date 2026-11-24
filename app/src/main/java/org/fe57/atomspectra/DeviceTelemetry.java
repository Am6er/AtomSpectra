package org.fe57.atomspectra;

import android.content.Context;

import java.util.Locale;

/**
 * Device health readings a source may report. A null field is a reading the source does not have.
 */
public final class DeviceTelemetry {
    public static final DeviceTelemetry EMPTY = new DeviceTelemetry(null, null, null, null);

    /**
     * Bluetooth link strength, dBm.
     */
    public final Integer linkRssiDbm;
    /**
     * Device temperature, degrees Celsius.
     */
    public final Integer temperatureC;
    public final Integer batteryPercent;
    /**
     * Battery voltage, shown only when the source has no percent.
     */
    public final Double batteryVolts;

    public DeviceTelemetry(Integer linkRssiDbm, Integer temperatureC, Integer batteryPercent, Double batteryVolts) {
        this.linkRssiDbm = linkRssiDbm;
        this.temperatureC = temperatureC;
        this.batteryPercent = batteryPercent;
        this.batteryVolts = batteryVolts;
    }

    /**
     * The present readings on one line, e.g. "-67dBm 31°C 82%"; empty when there are none.
     */
    public String format(Context context) {
        StringBuilder text = new StringBuilder();
        if (linkRssiDbm != null) {
            append(text, context.getString(R.string.telemetry_rssi, linkRssiDbm));
        }
        if (temperatureC != null) {
            append(text, context.getString(R.string.telemetry_temperature, temperatureC));
        }
        if (batteryPercent != null) {
            append(text, context.getString(R.string.telemetry_battery_percent, batteryPercent));
        } else if (batteryVolts != null) {
            // fixed decimal point so the text does not change with the locale
            append(text, String.format(Locale.US, context.getString(R.string.telemetry_battery_volts), batteryVolts));
        }
        return text.toString();
    }

    private static void append(StringBuilder text, String part) {
        if (text.length() > 0) text.append(' ');
        text.append(part);
    }
}
