package org.fe57.atomspectra;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.res.Resources;

/**
 * Shared view state: what the user is looking at (display mode, pan/zoom, overlays) and the
 * preference-derived render settings. Written by the activity, read by the view.
 * Lives as a process singleton so values survive Activity recreate (e.g. rotation).
 */
public final class UIViewState {
    public static final UIViewState instance = new UIViewState();

    private final Object syncFactor = new Object();
    private int xScaleFactor = Constants.scaleMinFor(Constants.DEFAULT_CHANNEL_COUNT);
    private int firstChannel = 0;

    public volatile int displayMode = Constants.DISPLAY_MODE_DEFAULT;
    public volatile boolean backgroundShow = false;
    public volatile boolean backgroundSubtract = false;
    public volatile boolean smooth = false;
    public volatile boolean showCalibrationFunction = false;

    /** Draw the x axis in energy (CONF_CALIBRATED) instead of channels. */
    public volatile boolean energyAxis = true;
    /** CONF_COMPRESS_GRAPH */
    public volatile int compressMode = Constants.COMPRESS_GRAPH_SUM;
    /** CONF_GOLAY_WINDOW */
    public volatile int smoothWindow = Constants.DEFAULT_GOLAY_WINDOW;
    /** CONF_LOG_SCALE */
    public volatile boolean logScale = Constants.LOG_SCALE_DEFAULT;
    /** CONF_BAR_MODE */
    public volatile boolean barMode = true;
    /** CONF_REDUCED_TO — number of abscissa points drawn on the spectrum graph. */
    public volatile int reducedTo = Constants.VIEW_CHANNELS_DEFAULT;
    /** CONF_DISPLAY_DOSE — dose / interval search display mode. */
    public volatile String displayDose = Constants.DISPLAY_DOSE_DEFAULT;

    /** Y-axis zoom for spectrum / search graphs (not persisted). */
    public volatile float yZoomFactor = 1f;
    /** Cursor channel, or -1 when hidden (not persisted). */
    public volatile int cursorX = -1;
    /** Seek-bar / +/- cursor controls visible (not persisted). */
    public volatile boolean showPlusMinusButtons = false;

    private UIViewState() {
    }

    public int getxScaleFactor() {
        synchronized (syncFactor) {
            return xScaleFactor;
        }
    }

    public void setxScaleFactor(int factor) {
        synchronized (syncFactor) {
            int channelCount = SpectrumData.instance.getChannelCount();
            int scaleMin = Constants.scaleMinFor(channelCount);
            xScaleFactor = Constants.MinMax(
                    factor,
                    scaleMin,
                    Constants.SCALE_MAX);
            firstChannel = Constants.MinMax(
                    firstChannel,
                    0,
                    channelCount - Constants.WINDOW_OUTPUT_SIZE * (1 << (Constants.SCALE_MAX - xScaleFactor)));
        }
    }

    @SuppressLint("ApplySharedPref")
    public void setxScaleFactor(int factor, SharedPreferences sp) {
        setxScaleFactor(factor);
        sp.edit().putInt(Constants.CONFIG.CONF_SCALE_FACTOR, getxScaleFactor()).commit();
    }

    public int getFirstChannel() {
        synchronized (syncFactor) {
            return firstChannel;
        }
    }

    public void setFirstChannel(int channel) {
        synchronized (syncFactor) {
            int channelCount = SpectrumData.instance.getChannelCount();
            int scaleMin = Constants.scaleMinFor(channelCount);
            firstChannel = Constants.MinMax(
                    channel,
                    0,
                    channelCount - Constants.WINDOW_OUTPUT_SIZE * (1 << (Constants.SCALE_MAX - Constants.MinMax(
                            xScaleFactor,
                            scaleMin,
                            Constants.SCALE_MAX))));
        }
    }

    @SuppressLint("ApplySharedPref")
    public void setFirstChannel(int channel, SharedPreferences sp) {
        setFirstChannel(channel);
        sp.edit().putInt(Constants.CONFIG.CONF_FIRST_CHANNEL, getFirstChannel()).commit();
    }

    /** Scale factor and first channel taken together, for a consistent render. */
    public int[] getScaleAndFirstChannel() {
        synchronized (syncFactor) {
            return new int[]{xScaleFactor, firstChannel};
        }
    }

    @SuppressLint("ApplySharedPref")
    public void setLogScale(boolean value, SharedPreferences sp) {
        logScale = value;
        sp.edit().putBoolean(Constants.CONFIG.CONF_LOG_SCALE, value).commit();
    }

    @SuppressLint("ApplySharedPref")
    public void setBarMode(boolean value, SharedPreferences sp) {
        barMode = value;
        sp.edit().putBoolean(Constants.CONFIG.CONF_BAR_MODE, value).commit();
    }

    @SuppressLint("ApplySharedPref")
    public void setEnergyAxis(boolean value, SharedPreferences sp) {
        energyAxis = value;
        sp.edit().putBoolean(Constants.CONFIG.CONF_CALIBRATED, value).commit();
    }

    @SuppressLint("ApplySharedPref")
    public void setReducedTo(int value, SharedPreferences sp) {
        reducedTo = value;
        sp.edit().putInt(Constants.CONFIG.CONF_REDUCED_TO, value).commit();
    }

    @SuppressLint("ApplySharedPref")
    public void setDisplayDose(String value, SharedPreferences sp) {
        displayDose = value;
        sp.edit().putString(Constants.CONFIG.CONF_DISPLAY_DOSE, value).commit();
    }

    /** Refresh the preference-derived render settings. */
    public void loadFromPreferences(SharedPreferences sp, Resources resources) {
        energyAxis = sp.getBoolean(Constants.CONFIG.CONF_CALIBRATED, true);
        smoothWindow = sp.getInt(Constants.CONFIG.CONF_GOLAY_WINDOW, Constants.DEFAULT_GOLAY_WINDOW);
        logScale = sp.getBoolean(Constants.CONFIG.CONF_LOG_SCALE, Constants.LOG_SCALE_DEFAULT);
        barMode = sp.getBoolean(Constants.CONFIG.CONF_BAR_MODE, true);
        reducedTo = sp.getInt(Constants.CONFIG.CONF_REDUCED_TO, Constants.VIEW_CHANNELS_DEFAULT);
        displayDose = sp.getString(Constants.CONFIG.CONF_DISPLAY_DOSE, Constants.DISPLAY_DOSE_DEFAULT);
        setxScaleFactor(sp.getInt(Constants.CONFIG.CONF_SCALE_FACTOR, Constants.scaleMinFor(SpectrumData.instance.getChannelCount())));
        setFirstChannel(sp.getInt(Constants.CONFIG.CONF_FIRST_CHANNEL, 0));

        int compress;
        try {
            compress = sp.getInt(Constants.CONFIG.CONF_COMPRESS_GRAPH, Constants.COMPRESS_GRAPH_SUM);
        } catch (Exception e) {
            compress = Constants.COMPRESS_GRAPH_SUM;
        }
        CharSequence[] data = resources.getTextArray(R.array.compress_graph_array);
        compressMode = Constants.MinMax(compress, 0, data.length - 1);
    }
}
