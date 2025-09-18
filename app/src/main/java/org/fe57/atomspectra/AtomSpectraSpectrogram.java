package org.fe57.atomspectra;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;

public class AtomSpectraSpectrogram extends Activity implements GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener {
    private static boolean isActive = false;
    private static int sbin = 1;
    private static String scale = AtomSpectraSpectrogramView.SCALE_SQRT;
    private static String palette = AtomSpectraSpectrogramView.PALETTE_IRON;
    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleGestureDetector;

    @Override
    protected void attachBaseContext(Context newBase) {
        SharedPreferences sharedPreferences = newBase.getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = sharedPreferences.getInt(Constants.CONFIG.CONF_LOCALE_ID, 0);
        r = r < Constants.LOCALES_ID.length ? r : (Constants.LOCALES_ID.length - 1);
        String lang = Locale.getDefault().getLanguage();
        if (r > 0) {
            lang = Constants.LOCALES_ID[r];
        }
        super.attachBaseContext(LocaleContextWrapper.wrap(newBase, lang));
    }

    @SuppressLint("SetTextI18n")
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

        // validate sbin and scale, init cintrol panel
        if (sbin < 1) {
            sbin = 1;
        }
        if (sbin > 128) {
            sbin = 128;
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
        findViewById(R.id.viewSpectrogram).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureDetector.onTouchEvent(event);
                scaleGestureDetector.onTouchEvent(event);
                return true;
            }
        });
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
    public boolean onOptionsItemSelected(MenuItem item) {
        if(item.getItemId() == android.R.id.home){
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
            AtomSpectraSpectrogramView spgView = findViewById(R.id.viewSpectrogram);
            if (spgView != null) {
                int channelBin = 1;
                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                    channelBin = 2;
                }

                spgView.renderSpectrogram(AtomSpectraSpectrogramData.instance, sbin, channelBin, scale, palette, scrollToBottom);
            }

            TextView rowCount = findViewById(R.id.textViewRowCount);
            if (rowCount != null) {
                rowCount.setText(getString(R.string.spectrogram_row_count, AtomSpectraSpectrogramData.instance.rowCount()));
            }
        }
    }

    @SuppressLint("DefaultLocale")
    private void updateControlPanel() {
        TextView textSpectrumBin = findViewById(R.id.textViewSpcBinValue);
        if (textSpectrumBin != null) {
            textSpectrumBin.setText(String.format("↕bin:%dx", sbin));
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

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleEnd(ScaleGestureDetector detector) {
            double factor = detector.getScaleFactor();
            if (factor > 1) {
              if (sbin > 1) {
                sbin /= 2;

                updateControlPanel();
                updateSpectrogram(false);
              }
            }

            if (factor < 1) {
              if (sbin < 128) {
                sbin *= 2;

                updateControlPanel();
                updateSpectrogram(false);
              }
            }

            return true;
        }
    }
}
