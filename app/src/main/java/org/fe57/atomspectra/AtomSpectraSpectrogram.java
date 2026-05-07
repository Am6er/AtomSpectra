package org.fe57.atomspectra;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashSet;

public class AtomSpectraSpectrogram extends Activity implements GestureDetector.OnDoubleTapListener, GestureDetector.OnGestureListener {
    private static boolean isActive = false;
    private static final int MAX_SBIN = 128;
    private static final int MAX_CBIN = 4;
    private static int sbin = 1;
    private static int cbin = 1;
    private static String scale = AtomSpectraSpectrogramView.SCALE_SQRT;
    private static String palette = AtomSpectraSpectrogramView.PALETTE_IRON;

    // region selection state - kept static so it survives orientation changes / activity recreate
    private static int bgLeftBound = -1;
    private static int bgRightBound = -1;
    private static int fgLeftBound = -1;
    private static int fgRightBound = -1;
    private static int lastRowCount = 0;
    private static boolean previewVisible = true;

    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleGestureDetector;

    @Override
    protected void attachBaseContext(Context newBase) {
        String lang = PrefHelper.getLocale(newBase);
        super.attachBaseContext(LocaleContextWrapper.wrap(newBase, lang));
    }

    @SuppressLint({"SetTextI18n", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_atom_spectra_spectrogram);
        // action bar
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setDisplayShowHomeEnabled(true);
            bar.setDisplayHomeAsUpEnabled(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // intents
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION.ACTION_CLOSE_SPECTROGRAM);
        intentFilter.addAction(Constants.ACTION.ACTION_SPECTROGRAM_UPDATED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(mDataUpdateReceiver, intentFilter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mDataUpdateReceiver, intentFilter);
        }

        // validate sbin and scale, init control panel
        if (sbin < 1) {
            sbin = 1;
        }
        if (sbin > MAX_SBIN) {
            sbin = MAX_SBIN;
        }
        int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            cbin = 2;
        } else {
            cbin = 1;
        }

        HashSet<String> allowedScale = new HashSet<>(Arrays.asList(AtomSpectraSpectrogramView.SCALE_SQRT, AtomSpectraSpectrogramView.SCALE_LOG, AtomSpectraSpectrogramView.SCALE_LIN));
        if (!allowedScale.contains(scale)) {
            scale = AtomSpectraSpectrogramView.SCALE_SQRT;
        }
        HashSet<String> allowedPalette = new HashSet<>(Arrays.asList(AtomSpectraSpectrogramView.PALETTE_IRON, AtomSpectraSpectrogramView.PALETTE_LIME, AtomSpectraSpectrogramView.PALETTE_YELLOW, AtomSpectraSpectrogramView.PALETTE_GLOW, AtomSpectraSpectrogramView.PALETTE_GRAY));
        if (!allowedPalette.contains(palette)) {
            palette = AtomSpectraSpectrogramView.PALETTE_IRON;
        }
        updateControlPanel();

        // gestures
        gestureDetector = new GestureDetector(this, this);
        gestureDetector.setOnDoubleTapListener(this);
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());
        findViewById(R.id.viewSpectrogram).setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            scaleGestureDetector.onTouchEvent(event);

            return false;
        });

        // wire region handles + preview
        AtomSpectraSpectrogramView spgView = findViewById(R.id.viewSpectrogram);
        if (spgView != null) {
            spgView.setOnSpectrogramStateChangedListener(new AtomSpectraSpectrogramView.OnSpectrogramStateChangedListener() {
                @Override
                public void onRowSelectionChanged() {
                    syncRegionsToPreview();
                }

                @Override
                public void onVisibleChannelsChanged(int startChannel, int endChannel) {
                    SpectrogramPreviewView preview = findViewById(R.id.viewSpectrogramPreview);
                    if (preview != null && previewVisible) {
                        preview.setVisibleChannelRange(startChannel, endChannel);
                    }
                }
            });
        }

        // preview visibility + toggle button
        SpectrogramPreviewView preview = findViewById(R.id.viewSpectrogramPreview);
        if (preview != null) {
            preview.setVisibility(previewVisible ? View.VISIBLE : View.GONE);
            preview.setScale(scale);
        }
        ImageButton toggleBtn = findViewById(R.id.buttonPreviewToggle);
        if (toggleBtn != null) {
            toggleBtn.setImageResource(previewVisible ? R.drawable.ic_preview_close : R.drawable.ic_preview_show);
            toggleBtn.setOnClickListener(v -> {
                previewVisible = !previewVisible;
                SpectrogramPreviewView pv = findViewById(R.id.viewSpectrogramPreview);
                if (pv != null) {
                    pv.setVisibility(previewVisible ? View.VISIBLE : View.GONE);
                }
                ((ImageButton) v).setImageResource(previewVisible ? R.drawable.ic_preview_close : R.drawable.ic_preview_show);
                if (previewVisible) {
                    updateSpectrogram(false);
                }
            });
        }
    }

    private void syncRegionsToPreview() {
        AtomSpectraSpectrogramView spgView = findViewById(R.id.viewSpectrogram);
        SpectrogramPreviewView preview = findViewById(R.id.viewSpectrogramPreview);
        if (spgView == null) {
            return;
        }
        bgLeftBound = spgView.getBgLeftHandleRow();
        bgRightBound = spgView.getBgRightHandleRow();
        fgLeftBound = spgView.getFgLeftHandleRow();
        fgRightBound = spgView.getFgRightHandleRow();
        if (preview == null || !previewVisible) {
            return;
        }
        double[] bg = AtomSpectraSpectrogramData.instance.averageSpectrum(bgLeftBound, bgRightBound);
        double[] fg = AtomSpectraSpectrogramData.instance.averageSpectrum(fgLeftBound, fgRightBound);
        double[] energies = computeEnergiesArray();
        preview.setScale(scale);
        preview.setSpectra(bg, fg, energies);
        preview.setVisibleChannelRange(spgView.getVisibleStartChannel(), spgView.getVisibleEndChannel());
    }

    private double[] computeEnergiesArray() {
        int channelCount = AtomSpectraSpectrogramData.CHANNEL_COUNT;
        double[] energies = new double[channelCount];
        for (int i = 0; i < channelCount; i++) {
            energies[i] = AtomSpectraSpectrogramData.instance.channelToEnergy(i);
        }
        return energies;
    }

    @Override
    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
        return false;
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
        // update scale
        switch (scale) {
            case AtomSpectraSpectrogramView.SCALE_LIN:
                scale = AtomSpectraSpectrogramView.SCALE_SQRT;
                break;
            case AtomSpectraSpectrogramView.SCALE_SQRT:
                scale = AtomSpectraSpectrogramView.SCALE_LOG;
                break;
            case AtomSpectraSpectrogramView.SCALE_LOG:
                scale = AtomSpectraSpectrogramView.SCALE_LIN;
                break;
            default:
                scale = AtomSpectraSpectrogramView.SCALE_SQRT;
        }

        updateControlPanel();
        updateSpectrogram(false);

        return true;
    }

    @Override
    public boolean onDoubleTapEvent(@NonNull MotionEvent e) {
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private final BroadcastReceiver mDataUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Constants.ACTION.ACTION_CLOSE_SPECTROGRAM.equals(action)) {
                finish();
            }

            if (Constants.ACTION.ACTION_SPECTROGRAM_UPDATED.equals(action)) {
                updateSpectrogram(false);
            }
        }

    };

    @Override
    protected void onStart() {
        super.onStart();
        isActive = true;

        // scroll to bottom at first render if recording is in progress
        boolean scrollToBottom = !AtomSpectraService.getFreeze();
        updateSpectrogram(scrollToBottom);
    }

    @Override
    protected void onStop() {
        super.onStop();
        isActive = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mDataUpdateReceiver);
    }

    private void updateSpectrogram(boolean scrollToBottom) {
        if (isActive) {
            int newRowCount = AtomSpectraSpectrogramData.instance.rowCount();

            // shift handle indices when oldest rows have been dropped (MAX_ROWS truncation)
            if (newRowCount == 0) {
                bgLeftBound = bgRightBound = fgLeftBound = fgRightBound = -1;
            } else if (newRowCount > 0 && (bgLeftBound < 0 || bgRightBound < 0 || fgLeftBound < 0 || fgRightBound < 0)) {
                bgLeftBound = bgRightBound = fgLeftBound = fgRightBound = 0;
            } else if (lastRowCount > 0 && newRowCount < lastRowCount) {
                int dropped = lastRowCount - newRowCount;
                bgLeftBound = shiftRowIndex(bgLeftBound, dropped);
                bgRightBound = shiftRowIndex(bgRightBound, dropped);
                fgLeftBound = shiftRowIndex(fgLeftBound, dropped);
                fgRightBound = shiftRowIndex(fgRightBound, dropped);
            }
            lastRowCount = newRowCount;

            AtomSpectraSpectrogramView spgView = findViewById(R.id.viewSpectrogram);
            if (spgView != null) {
                spgView.setRegionRows(bgLeftBound, bgRightBound, fgLeftBound, fgRightBound);
                spgView.renderSpectrogram(AtomSpectraSpectrogramData.instance, sbin, cbin, scale, palette, scrollToBottom);
                bgLeftBound = spgView.getBgLeftHandleRow();
                bgRightBound = spgView.getBgRightHandleRow();
                fgLeftBound = spgView.getFgLeftHandleRow();
                fgRightBound = spgView.getFgRightHandleRow();
            }

            TextView rowCount = findViewById(R.id.textViewRowCount);
            if (rowCount != null) {
                rowCount.setText(getString(R.string.spectrogram_row_count, newRowCount));
            }

            syncRegionsToPreview();
        }
    }

    private static int shiftRowIndex(int row, int dropped) {
        if (row < 0) return row;
        return Math.max(0, row - dropped);
    }

    @SuppressLint("DefaultLocale")
    private void updateControlPanel() {
        TextView textSpectrumBin = findViewById(R.id.textViewSpcBinValue);
        if (textSpectrumBin != null) {
            textSpectrumBin.setText(String.format("↕bin:%dx", sbin));
        }

        TextView textChannelBin = findViewById(R.id.textViewChBinValue);
        if (textChannelBin != null) {
            textChannelBin.setText(String.format("↔bin:%dx", cbin));
        }

        TextView textPalette = findViewById(R.id.textViewSpgPalette);
        if (textPalette != null) {
            textPalette.setText(palette);
        }

        TextView textScale = findViewById(R.id.textViewSpgScale);
        if (textScale != null) {
            textScale.setText(scale);
        }
    }

    @Override
    public boolean onDown(@NonNull MotionEvent e) {
        return false;
    }

    @Override
    public void onShowPress(@NonNull MotionEvent e) {

    }

    @Override
    public boolean onSingleTapUp(@NonNull MotionEvent e) {
        return false;
    }

    @Override
    public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
        return false;
    }

    @Override
    public void onLongPress(@NonNull MotionEvent e) {
        // update palette
        switch (palette) {
            case AtomSpectraSpectrogramView.PALETTE_IRON:
                palette = AtomSpectraSpectrogramView.PALETTE_LIME;
                break;
            case AtomSpectraSpectrogramView.PALETTE_LIME:
                palette = AtomSpectraSpectrogramView.PALETTE_YELLOW;
                break;
            case AtomSpectraSpectrogramView.PALETTE_YELLOW:
                palette = AtomSpectraSpectrogramView.PALETTE_GLOW;
                break;
            case AtomSpectraSpectrogramView.PALETTE_GLOW:
                palette = AtomSpectraSpectrogramView.PALETTE_GRAY;
                break;
            case AtomSpectraSpectrogramView.PALETTE_GRAY:
            default:
                palette = AtomSpectraSpectrogramView.PALETTE_IRON;
                break;
        }

        updateControlPanel();
        updateSpectrogram(false);
    }

    @Override
    public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
        return false;
    }

    public float pxToDp(float px) {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        return px / displayMetrics.density;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            float currentSpanX = detector.getCurrentSpanX();
            float previousSpanX = detector.getPreviousSpanX();
            float deltaXDp = pxToDp(Math.abs(currentSpanX - previousSpanX));
            float currentSpanY = detector.getCurrentSpanY();
            float previousSpanY = detector.getPreviousSpanY();
            float deltaYDp = pxToDp(Math.abs(currentSpanY - previousSpanY));

            float horizontalFactor = currentSpanX / previousSpanX;
            float verticalFactor = currentSpanY / previousSpanY;

            if (deltaXDp > 50) {
                if (horizontalFactor > 1.4) {
                    // reduce channel bin
                    if (cbin > 1) {
                        cbin /= 2;
                        updateControlPanel();
                        updateSpectrogram(false);
                    }
                }

                if (horizontalFactor < 0.7) {
                    // increase channel bin
                    if (cbin < MAX_CBIN) {
                        cbin *= 2;
                        updateControlPanel();
                        updateSpectrogram(false);
                    }
                }
            }

            if (deltaYDp > 50) {
                if (verticalFactor > 1.4) {
                    // reduce spectrum bin
                    if (sbin > 1) {
                        sbin /= 2;
                        updateControlPanel();
                        updateSpectrogram(false);
                    }
                }

                if (verticalFactor < 0.7) {
                    // increase spectrum bin
                    if (sbin < MAX_SBIN) {
                        sbin *= 2;
                        updateControlPanel();
                        updateSpectrogram(false);
                    }
                }
            }
        }
    }
}
