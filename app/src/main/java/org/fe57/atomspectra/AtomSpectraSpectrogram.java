package org.fe57.atomspectra;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Menu;
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

import java.util.List;
import java.util.Objects;

public class AtomSpectraSpectrogram extends Activity implements GestureDetector.OnDoubleTapListener, GestureDetector.OnGestureListener {
    private static boolean isActive = false;
    private static int lastRowCount = 0;

    // exporting state
    private static boolean isExportingSpectrum = false;
    private static CancellationToken exportSpectrumCancellationToken = null;
    private static AlertDialog spectrumExportingDialog = null;

    private SharedPreferences sharedPreferences;
    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleGestureDetector;
    private Menu optionsMenu;

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
        sharedPreferences = PrefHelper.getASSharedPreferences(this);
        SpectrogramUIViewState.instance.loadFromPreferences(sharedPreferences);

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
                    if (preview != null && SpectrogramUIViewState.instance.previewVisible) {
                        preview.setVisibleChannelRange(startChannel, endChannel);
                    }
                }
            });
        }

        // preview visibility
        SpectrogramUIViewState state = SpectrogramUIViewState.instance;
        AtomSpectraSpectrogramPreviewView preview = findViewById(R.id.viewSpectrogramPreview);
        if (preview != null) {
            preview.setVisibility(state.previewVisible ? View.VISIBLE : View.GONE);
            preview.setScale(state.scale);
        }
    }

    private void syncRegionsToPreview() {
        AtomSpectraSpectrogramView spgView = findViewById(R.id.viewSpectrogram);
        AtomSpectraSpectrogramPreviewView preview = findViewById(R.id.viewSpectrogramPreview);
        if (spgView == null) {
            return;
        }
        SpectrogramUIViewState state = SpectrogramUIViewState.instance;
        state.setSelection(
                spgView.getBgLeftHandleRow(),
                spgView.getBgRightHandleRow(),
                spgView.getFgLeftHandleRow(),
                spgView.getFgRightHandleRow());
        if (preview == null || !state.previewVisible) {
            return;
        }
        if (state.bgLeftBound == null || state.bgRightBound == null || state.fgLeftBound == null || state.fgRightBound == null) {
            return;
        }
        double[] bg = state.backgroundVisible
                ? AtomSpectraSpectrogramData.instance.averageSpectrum(
                state.bgLeftBound.segmentIndex, state.bgLeftBound.rowIndex,
                state.bgRightBound.segmentIndex, state.bgRightBound.rowIndex)
                : null;
        double[] fg = AtomSpectraSpectrogramData.instance.averageSpectrum(
                state.fgLeftBound.segmentIndex, state.fgLeftBound.rowIndex,
                state.fgRightBound.segmentIndex, state.fgRightBound.rowIndex);
        double[] energies = computeEnergiesArray(spgView.getVisibleStartSegmentIndex());
        preview.setScale(state.scale);
        preview.setSpectra(bg, fg, energies);
        preview.setVisibleChannelRange(spgView.getVisibleStartChannel(), spgView.getVisibleEndChannel());
    }

    private double[] computeEnergiesArray(int segmentIndex) {
        int channelCount = AtomSpectraSpectrogramData.CHANNEL_COUNT;
        double[] energies = new double[channelCount];
        for (int i = 0; i < channelCount; i++) {
            energies[i] = AtomSpectraSpectrogramData.instance.channelToEnergy(segmentIndex, i);
        }
        return energies;
    }

    @Override
    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
        return false;
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
        switch (SpectrogramUIViewState.instance.scale) {
            case AtomSpectraSpectrogramView.SCALE_LIN:
                applyScale(AtomSpectraSpectrogramView.SCALE_SQRT);
                break;
            case AtomSpectraSpectrogramView.SCALE_SQRT:
                applyScale(AtomSpectraSpectrogramView.SCALE_LOG);
                break;
            case AtomSpectraSpectrogramView.SCALE_LOG:
                applyScale(AtomSpectraSpectrogramView.SCALE_LIN);
                break;
            default:
                applyScale(AtomSpectraSpectrogramView.SCALE_SQRT);
        }

        return true;
    }

    @Override
    public boolean onDoubleTapEvent(@NonNull MotionEvent e) {
        return false;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.atom_spectra_spectrogram, menu);
        optionsMenu = menu;
        updateMapMenu();
        updateScalePaletteMenu();
        updateBackgroundMenu();
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        updateMapMenu();
        updateScalePaletteMenu();
        updateBackgroundMenu();
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == R.id.action_spectrogram_view_map) {
            AtomSpectraMap.openSpectrogramMap(this);
            return true;
        }
        if (item.getItemId() == R.id.action_spectrogram_show_background) {
            boolean next = !SpectrogramUIViewState.instance.backgroundVisible;
            SpectrogramUIViewState.instance.setBackgroundVisible(next, sharedPreferences);
            item.setChecked(next);
            updateSpectrogram(false);
            return true;
        }

        int id = item.getItemId();
        if (id == R.id.action_palette_iron) {
            applyPalette(AtomSpectraSpectrogramView.PALETTE_IRON);
            return true;
        }
        if (id == R.id.action_palette_lime) {
            applyPalette(AtomSpectraSpectrogramView.PALETTE_LIME);
            return true;
        }
        if (id == R.id.action_palette_yellow) {
            applyPalette(AtomSpectraSpectrogramView.PALETTE_YELLOW);
            return true;
        }
        if (id == R.id.action_palette_glow) {
            applyPalette(AtomSpectraSpectrogramView.PALETTE_GLOW);
            return true;
        }
        if (id == R.id.action_palette_gray) {
            applyPalette(AtomSpectraSpectrogramView.PALETTE_GRAY);
            return true;
        }
        if (id == R.id.action_scale_lin) {
            applyScale(AtomSpectraSpectrogramView.SCALE_LIN);
            return true;
        }
        if (id == R.id.action_scale_sqrt) {
            applyScale(AtomSpectraSpectrogramView.SCALE_SQRT);
            return true;
        }
        if (id == R.id.action_scale_log) {
            applyScale(AtomSpectraSpectrogramView.SCALE_LOG);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void applyPalette(String newPalette) {
        if (newPalette.equals(SpectrogramUIViewState.instance.palette)) {
            return;
        }
        SpectrogramUIViewState.instance.setPalette(newPalette, sharedPreferences);
        updateControlPanel();
        updateScalePaletteMenu();
        updateSpectrogram(false);
    }

    private void applyScale(String newScale) {
        if (newScale.equals(SpectrogramUIViewState.instance.scale)) {
            return;
        }
        SpectrogramUIViewState.instance.setScale(newScale, sharedPreferences);
        updateControlPanel();
        updateScalePaletteMenu();
        updateSpectrogram(false);
    }

    private void updateMapMenu() {
        if (optionsMenu == null) {
            return;
        }
        MenuItem mapItem = optionsMenu.findItem(R.id.action_spectrogram_view_map);
        if (mapItem != null) {
            mapItem.setEnabled(AtomSpectraSpectrogramData.instance.hasLocatedRows());
        }
    }

    private void updateBackgroundMenu() {
        if (optionsMenu == null) {
            return;
        }
        MenuItem backgroundItem = optionsMenu.findItem(R.id.action_spectrogram_show_background);
        if (backgroundItem != null) {
            backgroundItem.setChecked(SpectrogramUIViewState.instance.backgroundVisible);
        }
    }

    private void updateScalePaletteMenu() {
        if (optionsMenu == null) {
            return;
        }

        SpectrogramUIViewState state = SpectrogramUIViewState.instance;
        int paletteItemId;
        switch (state.palette) {
            case AtomSpectraSpectrogramView.PALETTE_LIME:
                paletteItemId = R.id.action_palette_lime;
                break;
            case AtomSpectraSpectrogramView.PALETTE_YELLOW:
                paletteItemId = R.id.action_palette_yellow;
                break;
            case AtomSpectraSpectrogramView.PALETTE_GLOW:
                paletteItemId = R.id.action_palette_glow;
                break;
            case AtomSpectraSpectrogramView.PALETTE_GRAY:
                paletteItemId = R.id.action_palette_gray;
                break;
            case AtomSpectraSpectrogramView.PALETTE_IRON:
            default:
                paletteItemId = R.id.action_palette_iron;
                break;
        }
        MenuItem paletteItem = optionsMenu.findItem(paletteItemId);
        if (paletteItem != null) {
            paletteItem.setChecked(true);
        }

        int scaleItemId;
        switch (state.scale) {
            case AtomSpectraSpectrogramView.SCALE_LIN:
                scaleItemId = R.id.action_scale_lin;
                break;
            case AtomSpectraSpectrogramView.SCALE_LOG:
                scaleItemId = R.id.action_scale_log;
                break;
            case AtomSpectraSpectrogramView.SCALE_SQRT:
            default:
                scaleItemId = R.id.action_scale_sqrt;
                break;
        }
        MenuItem scaleItem = optionsMenu.findItem(scaleItemId);
        if (scaleItem != null) {
            scaleItem.setChecked(true);
        }
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
                updateMapMenu();
            }
        }

    };

    @Override
    protected void onStart() {
        super.onStart();
        isActive = true;

        SpectrogramUIViewState state = SpectrogramUIViewState.instance;
        String currentRecordingId = AtomSpectraSpectrogramData.instance.getRecordingId();
        if (!Objects.equals(state.recordingId, currentRecordingId)) {
            state.onNewRecording(currentRecordingId);
            state.setSbin(1, sharedPreferences);
        }

        updateControlPanel();
        // scroll to bottom at first render if recording is in progress
        boolean scrollToBottom = AtomSpectraService.isRecording();
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
            SpectrogramUIViewState state = SpectrogramUIViewState.instance;
            int newRowCount = AtomSpectraSpectrogramData.instance.rowCount();
            if (newRowCount == 0) {
                state.clearSelection();
            } else if (state.bgLeftBound == null || state.bgRightBound == null
                    || state.fgLeftBound == null || state.fgRightBound == null) {
                AtomSpectraSpectrogramView.SelectionBound zero = AtomSpectraSpectrogramView.SelectionBound.zeroIndex();
                state.setSelection(zero, zero, zero, zero);
            } else if (lastRowCount > 0 && newRowCount < lastRowCount) {
                // TODO: is it really a case?
            }
            lastRowCount = newRowCount;

            AtomSpectraSpectrogramView spgView = findViewById(R.id.viewSpectrogram);
            if (spgView != null) {
                spgView.renderSpectrogram(AtomSpectraSpectrogramData.instance, state, scrollToBottom);
            }

            TextView rowCount = findViewById(R.id.textViewRowCount);
            if (rowCount != null) {
                rowCount.setText(getString(R.string.spectrogram_row_count, newRowCount));
            }

            syncRegionsToPreview();
        }
    }

    @SuppressLint("DefaultLocale")
    private void updateControlPanel() {
        SpectrogramUIViewState state = SpectrogramUIViewState.instance;
        int sbin = state.sbin;
        int cbin = state.getCbin(getResources().getConfiguration().orientation);

        TextView textSpectrumBin = findViewById(R.id.textViewSpcBinValue);
        if (textSpectrumBin != null) {
            textSpectrumBin.setText(String.format("↕bin:%dx", sbin));
        }

        ImageButton btnSpectrumBinInc = findViewById(R.id.buttonSpectrumBinInc);
        if (btnSpectrumBinInc != null) {
            btnSpectrumBinInc.setEnabled(sbin < SpectrogramUIViewState.MAX_SBIN);
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
            btnChannelBinInc.setEnabled(cbin < SpectrogramUIViewState.MAX_CBIN);
        }

        ImageButton btnChannelBinDec = findViewById(R.id.buttonChannelBinDec);
        if (btnChannelBinDec != null) {
            btnChannelBinDec.setEnabled(cbin > 1);
        }

        TextView textPalette = findViewById(R.id.textViewSpgPalette);
        if (textPalette != null) {
            textPalette.setText(state.palette);
        }

        TextView textScale = findViewById(R.id.textViewSpgScale);
        if (textScale != null) {
            textScale.setText(state.scale);
        }

        ImageButton toggleBtn = findViewById(R.id.buttonPreviewToggle);
        if (toggleBtn != null) {
            toggleBtn.setImageResource(state.previewVisible
                    ? R.drawable.ic_spg_spectrum_preview_close
                    : R.drawable.ic_spg_spectrum_preview_show);
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
        switch (SpectrogramUIViewState.instance.palette) {
            case AtomSpectraSpectrogramView.PALETTE_IRON:
                applyPalette(AtomSpectraSpectrogramView.PALETTE_LIME);
                break;
            case AtomSpectraSpectrogramView.PALETTE_LIME:
                applyPalette(AtomSpectraSpectrogramView.PALETTE_YELLOW);
                break;
            case AtomSpectraSpectrogramView.PALETTE_YELLOW:
                applyPalette(AtomSpectraSpectrogramView.PALETTE_GLOW);
                break;
            case AtomSpectraSpectrogramView.PALETTE_GLOW:
                applyPalette(AtomSpectraSpectrogramView.PALETTE_GRAY);
                break;
            case AtomSpectraSpectrogramView.PALETTE_GRAY:
            default:
                applyPalette(AtomSpectraSpectrogramView.PALETTE_IRON);
                break;
        }
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
        boolean next = !SpectrogramUIViewState.instance.previewVisible;
        SpectrogramUIViewState.instance.setPreviewVisible(next, sharedPreferences);
        AtomSpectraSpectrogramPreviewView pv = findViewById(R.id.viewSpectrogramPreview);
        if (pv != null) {
            pv.setVisibility(next ? View.VISIBLE : View.GONE);
        }
        ((ImageButton) v).setImageResource(next
                ? R.drawable.ic_spg_spectrum_preview_close
                : R.drawable.ic_spg_spectrum_preview_show);
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
            ToastHelper.showErrorAndLog(this, LogTag.SPECTROGRAM_ACT, message);
            return;
        }

        showSpectrumExportNameDialog();
    }

    private void showSpectrumExportNameDialog() {
        String basePrefix = "";
        Spectrum baseSpectrum = AtomSpectraSpectrogramData.instance.baseSegment().getBaseSpectrum();
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
                    ToastHelper.showToastAndLog(this, LogTag.SPECTROGRAM_ACT, getString(R.string.spectrogram_spectrum_export_name_invalid_char));
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

        final boolean backgroundVisible = SpectrogramUIViewState.instance.backgroundVisible;
        final CheckBox bgCheckBox = new CheckBox(this);
        bgCheckBox.setChecked(backgroundVisible);
        bgCheckBox.setEnabled(backgroundVisible);

        final EditText bgNameInput = new EditText(this);
        bgNameInput.setHint(R.string.spectrogram_spectrum_export_bg_name_hint);
        bgNameInput.setText(basePrefix + getString(R.string.spectrogram_spectrum_export_bg_name_default));
        bgNameInput.setTextColor(Color.GREEN);
        bgNameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        bgNameInput.setFilters(new InputFilter[]{filenameFilter});
        bgNameInput.setEnabled(backgroundVisible);

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
                        ToastHelper.showToastAndLog(this, LogTag.SPECTROGRAM_ACT, getString(R.string.spectrogram_spectrum_export_nothing_selected_error));
                        return;
                    }
                    String bgName = bgNameInput.getText().toString().trim();
                    String fgName = fgNameInput.getText().toString().trim();
                    if ((exportBg && bgName.isEmpty()) || (exportFg && fgName.isEmpty())) {
                        ToastHelper.showToastAndLog(this, LogTag.SPECTROGRAM_ACT, getString(R.string.spectrogram_spectrum_export_name_empty_error));
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
                            SpectrogramUIViewState exportState = SpectrogramUIViewState.instance;
                            if (exportBg) {
                                exportSpectrum(exportState.bgLeftBound, exportState.bgRightBound, bgName, exportSpectrumCancellationToken);
                            }
                            if (exportFg && !exportSpectrumCancellationToken.isCancelled()) {
                                exportSpectrum(exportState.fgLeftBound, exportState.fgRightBound, fgName, exportSpectrumCancellationToken);
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
                .setNegativeButton(R.string.dialog_cancel_button, (dialog, whichButton) -> {
                })
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

    private void exportSpectrum(AtomSpectraSpectrogramView.SelectionBound leftBound, AtomSpectraSpectrogramView.SelectionBound rightBound, String spectrumName, CancellationToken cancellationToken) {
        SpectrumFileAS spectrum = new SpectrumFileAS();
        AtomSpectraSpectrogramView.SelectionBound fromDelta;
        AtomSpectraSpectrogramView.SelectionBound toDelta;
        if (leftBound.compareTo(rightBound) <= 0) {
            fromDelta = leftBound;
            toDelta = rightBound;
        } else {
            fromDelta = rightBound;
            toDelta = leftBound;
        }

        try {
            List<AtomSpectraSpectrogramData.SegmentExportRange> ranges =
                    AtomSpectraSpectrogramData.instance.resolveExportRanges(fromDelta.segmentIndex, fromDelta.rowIndex, toDelta.segmentIndex, toDelta.rowIndex);
            Handler mainHandler = new Handler(Looper.getMainLooper());
            String spectrumFileName = spectrum.exportSpectrogramPartAsSpectrum(ranges, spectrumName, this, progress -> {
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
                ToastHelper.showActionAndLog(this, LogTag.SPECTROGRAM_ACT, getString(R.string.spectrogram_spectrum_export_save_success, spectrumFileName));
            } // otherwise cancelled
        } catch (Exception e) {
            AtomSpectraLog.error(this, LogTag.SPECTROGRAM_ACT, "Cannot export spectrogram selection", e);
            ToastHelper.showToastOnly(this, getString(R.string.spectrogram_spectrum_export_save_error, spectrumName, e.getMessage()));
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
        SpectrogramUIViewState state = SpectrogramUIViewState.instance;
        if (state.sbin > 1) {
            state.setSbin(state.sbin / 2, sharedPreferences);
            updateControlPanel();
            updateSpectrogram(false);
        }
    }

    private void increaseSpectrumBin() {
        SpectrogramUIViewState state = SpectrogramUIViewState.instance;
        if (state.sbin < SpectrogramUIViewState.MAX_SBIN) {
            state.setSbin(state.sbin * 2, sharedPreferences);
            updateControlPanel();
            updateSpectrogram(false);
        }
    }

    private void reduceChannelBin() {
        int orientation = getResources().getConfiguration().orientation;
        SpectrogramUIViewState state = SpectrogramUIViewState.instance;
        int cbin = state.getCbin(orientation);
        if (cbin > 1) {
            state.setCbin(orientation, cbin / 2, sharedPreferences);
            updateControlPanel();
            updateSpectrogram(false);
        }
    }

    private void increaseChannelBin() {
        int orientation = getResources().getConfiguration().orientation;
        SpectrogramUIViewState state = SpectrogramUIViewState.instance;
        int cbin = state.getCbin(orientation);
        if (cbin < SpectrogramUIViewState.MAX_CBIN) {
            state.setCbin(orientation, cbin * 2, sharedPreferences);
            updateControlPanel();
            updateSpectrogram(false);
        }
    }
}
