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

    /**
     * Guards the data-handler write of the foreground spectrum and compound reads of it.
     */
    public final Object lock = new Object();

    public final Spectrum foreground = new Spectrum();
    public final Spectrum background = new Spectrum();
    /**
     * Calibration being edited by the user (points list), not yet applied.
     */
    public volatile Calibration newCalibration = new Calibration(Constants.DEFAULT_CHANNEL_COUNT);

    // channels cut off the top end of the spectrum, the calibration ends below them
    public volatile int ignoredTopChannels = 0;

    private SpectrumData() {
    }

    public int getChannelCount() {
        return foreground.getDataArray().length;
    }

    /**
     * Clear both spectra to the default calibration with the given channel count.
     * Returns whether the count changed; the caller then resets the view state.
     */
    public boolean reset(int channelCount) {
        synchronized (lock) {
            final boolean resized = channelCount != getChannelCount();
            foreground.initSpectrumData(channelCount, Calibration.defaultCalibration(channelCount));
            background.initSpectrumData(channelCount, Calibration.defaultCalibration(channelCount));
            if (resized) {
                onChannelCountChanged(channelCount);
            }
            EnergyIntervalData.instance.recalculate();
            return resized;
        }
    }

    /**
     * Replace the foreground with a loaded spectrum. A different channel count clears the background.
     * Returns whether the count changed; the caller then resets the view state.
     */
    public boolean replaceForeground(@NonNull Spectrum spectrum) {
        synchronized (lock) {
            final int channelCount = spectrum.getDataArray().length;
            final boolean resized = channelCount != getChannelCount();
            foreground.ReinitializeFrom(spectrum);
            if (resized) {
                background.initSpectrumData(channelCount, Calibration.defaultCalibration(channelCount));
                onChannelCountChanged(channelCount);
            }
            EnergyIntervalData.instance.recalculate();
            return resized;
        }
    }

    // the arrays already have the new size (called under lock)
    private void onChannelCountChanged(int channelCount) {
        newCalibration = new Calibration(channelCount);
    }

    // --- calibration ---------------------------------------------------------------------------
    // The active calibration lives on the foreground spectrum. Everything that changes it goes
    // through the apply* methods, which tell the consumers to re-read the data.

    /**
     * Make a calibration the active one and let the consumers know it changed.
     */
    public void applyCalibration(@NonNull Context context, Calibration calibration) {
        foreground.setSpectrumCalibration(calibration);
        EnergyIntervalData.instance.recalculate();
        notifyDataAvailable(context);
    }

    /**
     * The channel the calibration ends at: the channel count without the ignored top channels.
     */
    public int getLastCalibrationChannel() {
        final int count = getChannelCount();
        return Math.min(count, Math.max(Constants.MIN_LAST_CALIBRATION_CHANNEL, count - Math.max(0, ignoredTopChannels)));
    }

    /**
     * Set how many top channels are ignored and let the consumers know. In-memory change only.
     */
    public void applyIgnoredTopChannels(@NonNull Context context, int count) {
        ignoredTopChannels = Math.max(0, Math.min(count, getChannelCount() - Constants.MIN_LAST_CALIBRATION_CHANNEL));
        AtomSpectraLog.action(context, LogTag.SPECTRUM_DATA, "Ignored top channels set to " + ignoredTopChannels);
        notifyDataAvailable(context);
    }

    /**
     * Apply coefficients that came from a device. The success toast is for an explicit user action only;
     * unusable coefficients are always reported.
     */
    public void applyDeviceCalibration(@NonNull Context context, double[] coeffs, boolean announce) {
        Calibration calibration = new Calibration(getChannelCount());
        calibration.Calculate(coeffs);
        if (calibration.isCorrect()) {
            applyCalibration(context, calibration);
            if (announce) {
                ToastHelper.showActionAndLog(context, LogTag.SPECTRUM_DATA, context.getString(R.string.cal_apply_device));
            }
        } else {
            calibration.Calculate(defaultLinearCalibrationCoeffs(getChannelCount()));
            applyCalibration(context, calibration);
            ToastHelper.showWarningAndLog(context, LogTag.SPECTRUM_DATA, R.string.cal_wrong_device);
        }
    }

    private static void notifyDataAvailable(@NonNull Context context) {
        context.sendBroadcast(new Intent(Constants.ACTION.ACTION_DATA_AVAILABLE).setPackage(Constants.PACKAGE_NAME));
    }

    /**
     * Linear calibration coefficients spanning the device's whole channel range over 0-3MeV, for when the device has none stored.
     */
    private static double[] defaultLinearCalibrationCoeffs(int channelCount) {
        double[] coeffs = new double[2];
        coeffs[1] = 3000.0 / (channelCount + 1);
        return coeffs;
    }
}
