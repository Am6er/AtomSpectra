package org.fe57.atomspectra;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * Spectrogram view state: display prefs (palette, scale, bins, toggles) and session viewport
 * (pan, color bar, selection). Written by the activity and view, read by the view.
 * Lives as a process singleton so values survive Activity recreate (e.g. rotation).
 */
public final class SpectrogramUIViewState {
    public static final SpectrogramUIViewState instance = new SpectrogramUIViewState();

    public static final int MAX_SBIN = 256;
    public static final int MAX_CBIN = 8;
    public static final int DEFAULT_CBIN_PORTRAIT = 2;
    public static final int DEFAULT_CBIN_LANDSCAPE = 1;

    public volatile String scale = AtomSpectraSpectrogramView.SCALE_SQRT;
    public volatile String palette = AtomSpectraSpectrogramView.PALETTE_IRON;
    public volatile int sbin = 1;
    public volatile int cbinPortrait = DEFAULT_CBIN_PORTRAIT;
    public volatile int cbinLandscape = DEFAULT_CBIN_LANDSCAPE;
    public volatile boolean previewVisible = true;
    public volatile boolean backgroundVisible = true;

    /** Vertical pan in px (not persisted). */
    public volatile int verticalOffsetPx = 0;
    /** Horizontal pan in px (not persisted). */
    public volatile int horizontalOffsetPx = 0;
    /** Follow latest rows when pinned to bottom (not persisted). */
    public volatile boolean autoScroll = true;
    /** Color-bar contrast window (not persisted). */
    public volatile float colorBarMinFraction = 0f;
    public volatile float colorBarMaxFraction = 1f;

    /** Recording id that selection / sbin reset are scoped to (not persisted). */
    public volatile String recordingId = "";
    public volatile AtomSpectraSpectrogramView.SelectionBound bgLeftBound = null;
    public volatile AtomSpectraSpectrogramView.SelectionBound bgRightBound = null;
    public volatile AtomSpectraSpectrogramView.SelectionBound fgLeftBound = null;
    public volatile AtomSpectraSpectrogramView.SelectionBound fgRightBound = null;

    private SpectrogramUIViewState() {
    }

    public int getCbin(int orientation) {
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            return cbinPortrait;
        }
        return cbinLandscape;
    }

    @SuppressLint("ApplySharedPref")
    public void setCbin(int orientation, int value, SharedPreferences sp) {
        int clamped = clampCbin(value);
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            cbinPortrait = clamped;
            sp.edit().putInt(Constants.CONFIG.CONF_SPG_CBIN_PORTRAIT, clamped).commit();
        } else {
            cbinLandscape = clamped;
            sp.edit().putInt(Constants.CONFIG.CONF_SPG_CBIN_LANDSCAPE, clamped).commit();
        }
    }

    @SuppressLint("ApplySharedPref")
    public void setSbin(int value, SharedPreferences sp) {
        sbin = clampSbin(value);
        sp.edit().putInt(Constants.CONFIG.CONF_SPG_SBIN, sbin).commit();
    }

    @SuppressLint("ApplySharedPref")
    public void setScale(String value, SharedPreferences sp) {
        scale = sanitizeScale(value);
        sp.edit().putString(Constants.CONFIG.CONF_SPG_SCALE, scale).commit();
    }

    @SuppressLint("ApplySharedPref")
    public void setPalette(String value, SharedPreferences sp) {
        palette = sanitizePalette(value);
        sp.edit().putString(Constants.CONFIG.CONF_SPG_PALETTE, palette).commit();
    }

    @SuppressLint("ApplySharedPref")
    public void setPreviewVisible(boolean value, SharedPreferences sp) {
        previewVisible = value;
        sp.edit().putBoolean(Constants.CONFIG.CONF_SPG_PREVIEW_VISIBLE, value).commit();
    }

    @SuppressLint("ApplySharedPref")
    public void setBackgroundVisible(boolean value, SharedPreferences sp) {
        backgroundVisible = value;
        sp.edit().putBoolean(Constants.CONFIG.CONF_SPG_BACKGROUND_VISIBLE, value).commit();
    }

    /**
     * New spectrogram recording: clear selection and reset spectrum binning.
     * Does not reset palette, scale, channel bins, or visibility toggles.
     */
    public void onNewRecording(String newRecordingId) {
        recordingId = newRecordingId != null ? newRecordingId : "";
        sbin = 1;
        bgLeftBound = null;
        bgRightBound = null;
        fgLeftBound = null;
        fgRightBound = null;
        verticalOffsetPx = 0;
        horizontalOffsetPx = 0;
        autoScroll = true;
        colorBarMinFraction = 0f;
        colorBarMaxFraction = 1f;
    }

    public void clearSelection() {
        bgLeftBound = null;
        bgRightBound = null;
        fgLeftBound = null;
        fgRightBound = null;
    }

    public void setSelection(
            AtomSpectraSpectrogramView.SelectionBound bgLeft,
            AtomSpectraSpectrogramView.SelectionBound bgRight,
            AtomSpectraSpectrogramView.SelectionBound fgLeft,
            AtomSpectraSpectrogramView.SelectionBound fgRight) {
        bgLeftBound = bgLeft;
        bgRightBound = bgRight;
        fgLeftBound = fgLeft;
        fgRightBound = fgRight;
    }

    public void loadFromPreferences(SharedPreferences sp) {
        scale = sanitizeScale(sp.getString(Constants.CONFIG.CONF_SPG_SCALE, AtomSpectraSpectrogramView.SCALE_SQRT));
        palette = sanitizePalette(sp.getString(Constants.CONFIG.CONF_SPG_PALETTE, AtomSpectraSpectrogramView.PALETTE_IRON));
        sbin = clampSbin(sp.getInt(Constants.CONFIG.CONF_SPG_SBIN, 1));
        cbinPortrait = clampCbin(sp.getInt(Constants.CONFIG.CONF_SPG_CBIN_PORTRAIT, DEFAULT_CBIN_PORTRAIT));
        cbinLandscape = clampCbin(sp.getInt(Constants.CONFIG.CONF_SPG_CBIN_LANDSCAPE, DEFAULT_CBIN_LANDSCAPE));
        previewVisible = sp.getBoolean(Constants.CONFIG.CONF_SPG_PREVIEW_VISIBLE, true);
        backgroundVisible = sp.getBoolean(Constants.CONFIG.CONF_SPG_BACKGROUND_VISIBLE, true);
    }

    private static int clampSbin(int value) {
        int v = value;
        if (v < 1) {
            v = 1;
        }
        if (v > MAX_SBIN) {
            v = MAX_SBIN;
        }
        return nearestPowerOfTwo(v, MAX_SBIN);
    }

    private static int clampCbin(int value) {
        int v = value;
        if (v < 1) {
            v = 1;
        }
        if (v > MAX_CBIN) {
            v = MAX_CBIN;
        }
        return nearestPowerOfTwo(v, MAX_CBIN);
    }

    private static int nearestPowerOfTwo(int value, int max) {
        int p = 1;
        while (p < value && p < max) {
            p <<= 1;
        }
        if (p > max) {
            p = max;
        }
        return p;
    }

    private static String sanitizeScale(String value) {
        if (AtomSpectraSpectrogramView.SCALE_LIN.equals(value)
                || AtomSpectraSpectrogramView.SCALE_SQRT.equals(value)
                || AtomSpectraSpectrogramView.SCALE_LOG.equals(value)) {
            return value;
        }
        return AtomSpectraSpectrogramView.SCALE_SQRT;
    }

    private static String sanitizePalette(String value) {
        if (AtomSpectraSpectrogramView.PALETTE_IRON.equals(value)
                || AtomSpectraSpectrogramView.PALETTE_LIME.equals(value)
                || AtomSpectraSpectrogramView.PALETTE_YELLOW.equals(value)
                || AtomSpectraSpectrogramView.PALETTE_GLOW.equals(value)
                || AtomSpectraSpectrogramView.PALETTE_GRAY.equals(value)) {
            return value;
        }
        return AtomSpectraSpectrogramView.PALETTE_IRON;
    }
}
