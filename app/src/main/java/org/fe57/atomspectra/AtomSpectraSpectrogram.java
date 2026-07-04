package org.fe57.atomspectra;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
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
    private static String recordingId = "";
    private static int bgLeftBound = -1;
    private static int bgRightBound = -1;
    private static int fgLeftBound = -1;
    private static int fgRightBound = -1;
    private static int lastRowCount = 0;
    private static boolean previewVisible = true;

    // exporting state
    private static boolean isExportingSpectrum = false;
    private static CancellationToken exportSpectrumCancellationToken = null;
    private static AlertDialog spectrumExportingDialog = null;

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
                    AtomSpectraSpectrogramPreviewView preview = findViewById(R.id.viewSpectrogramPreview);
                    if (preview != null && previewVisible) {
                        preview.setVisibleChannelRange(startChannel, endChannel);
                    }
                }
            });
        }

        // preview visibility
        AtomSpectraSpectrogramPreviewView preview = findViewById(R.id.viewSpectrogramPreview);
        if (preview != null) {
            preview.setVisibility(previewVisible ? View.VISIBLE : View.GONE);
            preview.setScale(scale);
        }
    }

    private void syncRegionsToPreview() {
        AtomSpectraSpectrogramView spgView = findViewById(R.id.viewSpectrogram);
        AtomSpectraSpectrogramPreviewView preview = findViewById(R.id.viewSpectrogramPreview);
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
        AtomSpectraSpectrogramView spgView = findViewById(R.id.viewSpectrogram);
        if (spgView != null && !spgView.isTouchInContentArea(e.getX(), e.getY())) {
            if (spgView.isTouchInColorBarArea(e.getX(), e.getY())) {
                spgView.resetColorRange();
                return true;
            }
            return false;
        }
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

        // reset state for each new spectrogram
        if (recordingId != AtomSpectraSpectrogramData.instance.getRecordingId()) {
            sbin = 1;
            bgLeftBound = -1;
            bgRightBound = -1;
            fgLeftBound = -1;
            fgRightBound = -1;
            recordingId = AtomSpectraSpectrogramData.instance.getRecordingId();

        }

        updateControlPanel();
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
    protected void onResume() {
        super.onResume();

        checkSpectrumIsExporting();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mDataUpdateReceiver);
        dismissSpectrumExportingDialog();
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
                spgView.renderSpectrogram(AtomSpectraSpectrogramData.instance, sbin, cbin, scale, palette, scrollToBottom,
                        bgLeftBound, bgRightBound, fgLeftBound, fgRightBound);
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

        ImageButton btnSpectrumBinInc = findViewById(R.id.buttonSpectrumBinInc);
        if (btnSpectrumBinInc != null) {
            btnSpectrumBinInc.setEnabled(sbin < MAX_SBIN);
        }

        ImageButton btnSpectrumBinDec = findViewById(R.id.buttonSpectrumBinDec);
        if (btnSpectrumBinDec != null) {
            btnSpectrumBinDec.setEnabled(sbin > 1);
        }

        TextView textChannelBin = findViewById(R.id.textViewChBinValue);
        if (textChannelBin != null) {
            textChannelBin.setText(String.format("↔bin:%dx", cbin));
        }

        ImageButton btnChannelBinInc = findViewById(R.id.buttonChannelBinInc);
        if (btnChannelBinInc != null) {
            btnChannelBinInc.setEnabled(cbin < MAX_CBIN);
        }

        ImageButton btnChannelBinDec = findViewById(R.id.buttonChannelBinDec);
        if (btnChannelBinDec != null) {
            btnChannelBinDec.setEnabled(cbin > 1);
        }

        TextView textPalette = findViewById(R.id.textViewSpgPalette);
        if (textPalette != null) {
            textPalette.setText(palette);
        }

        TextView textScale = findViewById(R.id.textViewSpgScale);
        if (textScale != null) {
            textScale.setText(scale);
        }

        ImageButton toggleBtn = findViewById(R.id.buttonPreviewToggle);
        if (toggleBtn != null) {
            toggleBtn.setImageResource(previewVisible ? R.drawable.ic_spg_spectrum_preview_close : R.drawable.ic_spg_spectrum_preview_show);
        }

        ImageButton exportButton = findViewById(R.id.buttonExportSpectrum);
        if (exportButton != null) {
            boolean filenameIsSet = AtomSpectraSpectrogramData.instance.getSpectrogramFileName() != null;
            boolean rowsAvailable = AtomSpectraSpectrogramData.instance.rowCount() > 0;
            exportButton.setEnabled(!isExportingSpectrum && filenameIsSet && rowsAvailable);
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
        AtomSpectraSpectrogramView spgView = findViewById(R.id.viewSpectrogram);
        if (spgView != null && !spgView.isTouchInContentArea(e.getX(), e.getY())) {
            return;
        }
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

    public void onClick_spectrumBinInc(View v) {
        increaseSpectrumBin();
    }

    public void onClick_spectrumBinDec(View v) {
        reduceSpectrumBin();
    }

    public void onClick_channelBinInc(View v) {
        increaseChannelBin();
    }

    public void onClick_channelBinDec(View v) {
        reduceChannelBin();
    }

    public void onClick_previewToggle(View v) {
        previewVisible = !previewVisible;
        AtomSpectraSpectrogramPreviewView pv = findViewById(R.id.viewSpectrogramPreview);
        if (pv != null) {
            pv.setVisibility(previewVisible ? View.VISIBLE : View.GONE);
        }
        ((ImageButton) v).setImageResource(previewVisible ? R.drawable.ic_spg_spectrum_preview_close : R.drawable.ic_spg_spectrum_preview_show);
        updateSpectrogram(false);
    }

    public void onClick_exportSpectrum(View v) {
        exportSpectrogramSelection();
    }

    public float pxToDp(float px) {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        return px / displayMetrics.density;
    }

    private void exportSpectrogramSelection() {
        if (isExportingSpectrum) {
            String message = "ERROR: exportSpectrogramSelection called while spectrum is already exporting.";
            AtomSpectraLog.addMessage(this, message);
            ToastHelper.showToast(this, message);
            return;
        }

        showSpectrumExportNameDialog();
    }

    private void showSpectrumExportNameDialog() {
        String basePrefix = "";
        Spectrum baseSpectrum = AtomSpectraSpectrogramData.instance.getBaseSpectrum();
        if (baseSpectrum != null && !baseSpectrum.getSuffix().isEmpty()) {
            basePrefix = baseSpectrum.getSuffix() + "-";
        }

        int paddingPx = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(paddingPx, paddingPx, paddingPx, 0);

        InputFilter filenameFilter = (source, start, end, dest, dstart, dend) -> {
            for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                if ("/\\*?<>|:\"'".indexOf(c) >= 0) {
                    ToastHelper.showToast(this, getString(R.string.spectrogram_spectrum_export_name_invalid_char));
                    return "";
                }
            }
            return null;
        };

        final CheckBox fgCheckBox = new CheckBox(this);
        fgCheckBox.setChecked(true);

        final EditText fgNameInput = new EditText(this);
        fgNameInput.setHint(R.string.spectrogram_spectrum_export_fg_name_hint);
        fgNameInput.setText(basePrefix + getString(R.string.spectrogram_spectrum_export_fg_name_default));
        fgNameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        fgNameInput.setFilters(new InputFilter[]{filenameFilter});

        LinearLayout fgRow = new LinearLayout(this);
        fgRow.setOrientation(LinearLayout.HORIZONTAL);
        fgRow.addView(fgCheckBox);
        fgRow.addView(fgNameInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        container.addView(fgRow);

        final CheckBox bgCheckBox = new CheckBox(this);
        bgCheckBox.setChecked(true);

        final EditText bgNameInput = new EditText(this);
        bgNameInput.setHint(R.string.spectrogram_spectrum_export_bg_name_hint);
        bgNameInput.setText(basePrefix + getString(R.string.spectrogram_spectrum_export_bg_name_default));
        bgNameInput.setTextColor(Color.GREEN);
        bgNameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        bgNameInput.setFilters(new InputFilter[]{filenameFilter});

        LinearLayout bgRow = new LinearLayout(this);
        bgRow.setOrientation(LinearLayout.HORIZONTAL);
        bgRow.addView(bgCheckBox);
        bgRow.addView(bgNameInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        container.addView(bgRow);

        new AlertDialog.Builder(this)
                .setTitle(R.string.spectrogram_spectrum_export_dialog_title)
                .setMessage(getString(R.string.spectrogram_spectrum_export_dialog_message))
                .setView(container)
                .setPositiveButton(R.string.dialog_continue_button, (dialog, whichButton) -> {
                    boolean exportFg = fgCheckBox.isChecked();
                    boolean exportBg = bgCheckBox.isChecked();
                    if (!exportFg && !exportBg) {
                        ToastHelper.showToast(this, getString(R.string.spectrogram_spectrum_export_nothing_selected_error));
                        return;
                    }
                    String bgName = bgNameInput.getText().toString().trim();
                    String fgName = fgNameInput.getText().toString().trim();
                    if ((exportBg && bgName.isEmpty()) || (exportFg && fgName.isEmpty())) {
                        ToastHelper.showToast(this, getString(R.string.spectrogram_spectrum_export_name_empty_error));
                        return;
                    }

                    exportSpectrumCancellationToken = new CancellationToken();
                    showSpectrumExportingDialog();

                    Handler mainHandler = new Handler(Looper.getMainLooper());
                    new Thread(() -> {
                        isExportingSpectrum = true;
                        mainHandler.post(() -> {
                            updateControlPanel();
                        });
                        try {
                            if (exportBg) {
                                exportSpectrum(bgLeftBound, bgRightBound, bgName, exportSpectrumCancellationToken);
                            }
                            if (exportFg && !exportSpectrumCancellationToken.isCancelled()) {
                                exportSpectrum(fgLeftBound, fgRightBound, fgName, exportSpectrumCancellationToken);
                            }
                        } finally {
                            isExportingSpectrum = false;
                            mainHandler.post(() -> {
                                dismissSpectrumExportingDialog();
                                updateControlPanel();
                            });
                        }
                    }).start();
                })
                .setNegativeButton(R.string.dialog_cancel_button, (dialog, whichButton) -> {})
                .show();
    }

    private void showSpectrumExportingDialog() {
        final AlertDialog.Builder alert = new AlertDialog.Builder(this)
                .setTitle(R.string.spectrogram_spectrum_exporting_dialog_title)
                .setMessage(getString(R.string.spectrogram_spectrum_exporting_dialog_default_message))
                .setPositiveButton(getString(R.string.dialog_cancel_button), (dialog, whichButton) -> {
                    if (exportSpectrumCancellationToken != null) {
                        exportSpectrumCancellationToken.cancel();
                    }
                })
                .setCancelable(false);

        spectrumExportingDialog = alert.show();
    }

    private void dismissSpectrumExportingDialog() {
        if (spectrumExportingDialog != null) {
            spectrumExportingDialog.dismiss();
            spectrumExportingDialog = null;
        }
    }

    private void checkSpectrumIsExporting() {
        if (isActive && isExportingSpectrum && spectrumExportingDialog == null) {
            showSpectrumExportingDialog();
        }
    }

    private void exportSpectrum(int leftBound, int rightBound, String spectrumName, CancellationToken cancellationToken) {
        SpectrumFileAS spectrum = new SpectrumFileAS();
        int fromDelta = Math.min(leftBound, rightBound);
        fromDelta = Constants.MinMax(fromDelta, 0, AtomSpectraSpectrogramData.instance.rowCount() - 1);
        int toDelta = Math.max(leftBound, rightBound);
        toDelta = Constants.MinMax(toDelta, 0, AtomSpectraSpectrogramData.instance.rowCount() - 1);
        try {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            String spectrumFileName = spectrum.exportSpectrogramPartAsSpectrum(AtomSpectraSpectrogramData.instance.getSpectrogramFileName(), spectrumName, fromDelta, toDelta, this, progress -> {
                mainHandler.post(() -> {
                    if (isActive && spectrumExportingDialog != null) {
                        spectrumExportingDialog.setMessage(progress);
                    }
                });
            }, cancellationToken);
            if (spectrumFileName == null && !cancellationToken.isCancelled()) {
                throw new IllegalStateException("Spectrum name is null while operation was not cancelled");
            }

            if (spectrumFileName != null) {
                ToastHelper.showToast(this, getString(R.string.spectrogram_spectrum_export_save_success, spectrumFileName));
            } // otherwise cancelled
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToast(this, getString(R.string.spectrogram_spectrum_export_save_error, spectrumName, e.getMessage()));
        }
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
                    reduceChannelBin();
                }

                if (horizontalFactor < 0.7) {
                    increaseChannelBin();
                }
            }

            if (deltaYDp > 50) {
                if (verticalFactor > 1.4) {
                    reduceSpectrumBin();
                }

                if (verticalFactor < 0.7) {
                    increaseSpectrumBin();
                }
            }
        }
    }

    private void reduceSpectrumBin() {
        if (sbin > 1) {
            sbin /= 2;
            updateControlPanel();
            updateSpectrogram(false);
        }
    }

    private void increaseSpectrumBin() {
        if (sbin < MAX_SBIN) {
            sbin *= 2;
            updateControlPanel();
            updateSpectrogram(false);
        }
    }

    private void reduceChannelBin() {
        if (cbin > 1) {
            cbin /= 2;
            updateControlPanel();
            updateSpectrogram(false);
        }
    }

    private void increaseChannelBin() {
        if (cbin < MAX_CBIN) {
            cbin *= 2;
            updateControlPanel();
            updateSpectrogram(false);
        }
    }
}
