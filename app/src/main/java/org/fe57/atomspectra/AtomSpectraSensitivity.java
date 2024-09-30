package org.fe57.atomspectra;

import android.app.ActionBar;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.NumberKeyListener;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.fe57.atomspectra.data.Constants;

import java.util.Locale;

/**
 * Created by S. Epiphanov.
 */
public class AtomSpectraSensitivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_atom_spectra_sensivity);
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setDisplayShowHomeEnabled(true);
            bar.setDisplayHomeAsUpEnabled(true);
        }
        fillSensitivityTable();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION.ACTION_CLOSE_SENSITIVITY);
        registerReceiver(mDataUpdateReceiver, intentFilter);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
//            onBackPressed();
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private final BroadcastReceiver mDataUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Constants.ACTION.ACTION_CLOSE_SENSITIVITY.equals(action)) {
                finish();
            }

        }

    };

    public void onOkButton(View v) {
        float[] energy = new float[AtomSpectraService.ETomSvDefault.length];
        float lastEnergy = -1;
        double[] sensitivity = new double[AtomSpectraService.ETomSvDefault.length];
        for (int i = 0; i < AtomSpectraService.ETomSvDefault.length; i++) {
            try {
                energy[i] = Float.parseFloat(((EditText)findViewById(Constants.GROUPS.GROUP_SENSE_TABLE + 2 * i)).getText().toString().replaceAll(",", "."));
                if (energy[i] < 0 || energy[i] >= 5000.0 || lastEnergy >= energy[i]) {
                    Toast.makeText(this, getText(R.string.update_data_error), Toast.LENGTH_LONG).show();
                    return;
                }
                lastEnergy = energy[i];
                sensitivity[i] = Double.parseDouble(((EditText)findViewById(Constants.GROUPS.GROUP_SENSE_TABLE + 2 * i + 1)).getText().toString().replaceAll(",", "."));
                if(sensitivity[i] < 0 || sensitivity[i] >= 10) {
                    Toast.makeText(this, getText(R.string.update_data_error), Toast.LENGTH_LONG).show();
                    return;
                }
            } catch (Exception e) {
                //
                Toast.makeText(this, getText(R.string.update_data_error), Toast.LENGTH_LONG).show();
                return;
            }
        }
        SharedPreferences.Editor editor = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE).edit();
        editor.putInt(Constants.CONFIG.CONF_CALIBRATION_SIZE, AtomSpectraService.ETomSvDefault.length);
        for (int i = 0; i < AtomSpectraService.ETomSvDefault.length; i++) {
            editor.putFloat(Constants.configCalibrationEnergy(i), energy[i]);
            editor.putLong(Constants.configCalibration(i), Double.doubleToRawLongBits(sensitivity[i]));
        }
        editor.apply();
        finish();
    }

    public void onCancelButton(View v) {
        if (v.getId() == R.id.buttonSensCancel) {
            finish();
        }
    }

    public void onResetButton(View v) {
        if (v.getId() == R.id.buttonSensReset) {
            SharedPreferences.Editor editor = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE).edit();
            EditText view;
            for (int i = 0; i < AtomSpectraService.ETomSvDefault.length; i++) {
                editor.putFloat(Constants.configCalibrationEnergy(i), (float)AtomSpectraService.EnergyListDefault[i]);
                editor.putLong(Constants.configCalibration(i), Double.doubleToRawLongBits(AtomSpectraService.ETomSvDefault[i]));
                view = findViewById(Constants.GROUPS.GROUP_SENSE_TABLE + 2 * i);
                view.setText(String.format(Locale.US, "%.1f", (float)AtomSpectraService.EnergyListDefault[i]));
                view = findViewById(Constants.GROUPS.GROUP_SENSE_TABLE + 2 * i + 1);
                view.setText(String.format(Locale.US, "%.10e", AtomSpectraService.ETomSvDefault[i]));
            }
            editor.apply();
        }
    }

    private void fillSensitivityTable() {
        TextView textNumber;
        TableRow newRow;
        EditText editEnergy;
        EditText editSensitivity;
        TableLayout table = findViewById(R.id.tableIDs);
        SharedPreferences sp = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        NumberKeyListener energyListener = new NumberKeyListener() {
            @NonNull
            @Override
            protected char[] getAcceptedChars() {
                return new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ','};
            }

            @Override
            public int getInputType() {
                return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
            }
        };
        NumberKeyListener senseListener = new NumberKeyListener() {
            @NonNull
            @Override
            protected char[] getAcceptedChars() {
                return new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ',', 'e', 'E'};
            }

            @Override
            public int getInputType() {
                return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
            }
        };
        table.removeAllViews();
        Paint text = new Paint();
        text.setTextSize(18);

        for (int i = 0; i < sp.getInt(Constants.CONFIG.CONF_E_TO_MSV_COUNT, AtomSpectraService.ETomSvDefault.length); i++) {
            newRow = new TableRow(this);
            textNumber = new TextView(this);
            textNumber.setText(String.format(Locale.getDefault(),"%d", i + 1));
            textNumber.setLayoutParams(new TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT));
            textNumber.setTextSize(18);
            textNumber.setWidth(100);
            textNumber.setGravity(Gravity.CENTER);
            editEnergy = new EditText(this);
            editEnergy.setLayoutParams(new TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT, 1.0f));
            editEnergy.setGravity(Gravity.CENTER);
            editEnergy.setText(String.format(Locale.getDefault(), "%.1f", sp.getFloat(Constants.configCalibrationEnergy(i), i * 100.0f)));
            editEnergy.setId(Constants.GROUPS.GROUP_SENSE_TABLE + 2 * i);
            editEnergy.setTextSize(18);
            editEnergy.setEms(10);
            editEnergy.setHint(R.string.hint_keV_show);
            editEnergy.setImeOptions(EditorInfo.IME_ACTION_NEXT);
            editEnergy.setKeyListener(energyListener);
            editEnergy.setEnabled(i != 0);
            editSensitivity = new EditText(this);
            editSensitivity.setLayoutParams(new TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT, 1.0f));
            editSensitivity.setGravity(Gravity.CENTER);
            editSensitivity.setText(String.format(Locale.getDefault(), "%.8e", Double.longBitsToDouble(sp.getLong(Constants.configCalibration(i), Double.doubleToRawLongBits(AtomSpectraService.ETomSvDefault[i])))));
            editSensitivity.setTextSize(18);
            editSensitivity.setId(Constants.GROUPS.GROUP_SENSE_TABLE + 2 * i + 1);
            editSensitivity.setEms(15);
            editSensitivity.setHint(R.string.hint_sens_show);
            editSensitivity.setImeOptions(EditorInfo.IME_ACTION_NEXT);
            editSensitivity.setKeyListener(senseListener);
            editSensitivity.setEnabled(i != 0);
            newRow.addView(textNumber);
            newRow.addView(editEnergy);
            newRow.addView(editSensitivity);
            table.addView(newRow);
        }
    }
}