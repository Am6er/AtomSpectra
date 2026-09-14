package org.fe57.atomspectra;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

/**
 * Shared spectra and calibration state. The service writes the foreground spectrum, the UI and
 * other consumers read it. The live objects are exposed, not copies: compound reads of the
 * foreground spectrum take {@link #lock}.
 */
public final class SpectrumData {
    public static final SpectrumData instance = new SpectrumData();

    /** Guards the data-handler write of the foreground spectrum and compound reads of it. */
    public final Object lock = new Object();

    public final Spectrum foreground = new Spectrum();
    public final Spectrum background = new Spectrum();
    /** Calibration being edited by the user (points list), not yet applied. */
    public volatile Calibration newCalibration = new Calibration(Constants.DEFAULT_CHANNEL_COUNT);

    public volatile int lastCalibrationChannel = Constants.DEFAULT_CHANNEL_COUNT;

    private SpectrumData() {
    }

    public int getChannelCount() {
        return foreground.getDataArray().length;
    }

    /** Clear both spectra to the default calibration with the given channel count. */
    public void reset(int channelCount) {
        foreground.initSpectrumData(channelCount, Calibration.defaultCalibration(channelCount));
        background.initSpectrumData(channelCount, Calibration.defaultCalibration(channelCount));
        EnergyIntervalData.instance.recalculate();
    }

    // --- calibration ---------------------------------------------------------------------------
    // The active calibration lives on the foreground spectrum. Everything that changes it goes
    // through the apply* methods, which tell the consumers to re-read the data.

    /** Make a calibration the active one and let the consumers know it changed. */
    public void applyCalibration(@NonNull Context context, Calibration calibration) {
        foreground.setSpectrumCalibration(calibration);
        EnergyIntervalData.instance.recalculate();
        notifyDataAvailable(context);
    }

    /** Set the last calibrated channel, store it, and let the consumers know. */
    public void applyLastCalibrationChannel(@NonNull Context context, int channel) {
        lastCalibrationChannel = Constants.MinMax(channel, Constants.MIN_LAST_CALIBRATION_CHANNEL, getChannelCount());
        PrefHelper.setLastCalibrationChannel(context, lastCalibrationChannel);
        notifyDataAvailable(context);
    }

    /** Apply coefficients that came from a device, reporting whether they were usable. */
    public void applyDeviceCalibration(@NonNull Context context, double[] coeffs) {
        Calibration calibration = new Calibration(getChannelCount());
        calibration.Calculate(coeffs);
        if (calibration.isCorrect()) {
            applyCalibration(context, calibration);
            ToastHelper.showToastAndLog(context, R.string.cal_apply_usb);
        } else {
            calibration.Calculate(defaultLinearCalibrationCoeffs(getChannelCount()));
            applyCalibration(context, calibration);
            // TODO: rename string resource so it tells "Incorrect calibration from device, default applied"
            ToastHelper.showToastAndLog(context, R.string.cal_wrong_usb);
        }
    }

    private static void notifyDataAvailable(@NonNull Context context) {
        context.sendBroadcast(new Intent(Constants.ACTION.ACTION_DATA_AVAILABLE).setPackage(Constants.PACKAGE_NAME));
    }

    /** Linear calibration coefficients spanning the device's whole channel range over 0-3MeV, for when the device has none stored. */
    private static double[] defaultLinearCalibrationCoeffs(int channelCount) {
        double[] coeffs = new double[2];
        coeffs[1] = 3000.0 / (channelCount + 1);
        return coeffs;
    }
}
