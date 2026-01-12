package org.fe57.atomspectra;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.NumberKeyListener;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

public class AtomSpectraSensitivity extends Activity {
    private TreeMap<Float, Double> sensitivityTable;

    @Override
    protected void attachBaseContext(Context newBase) {
        String lang = PrefHelper.getLocale(newBase);
        super.attachBaseContext(LocaleContextWrapper.wrap(newBase, lang));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_atom_spectra_sensivity);

        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setDisplayShowHomeEnabled(true);
            bar.setDisplayHomeAsUpEnabled(true);
        }

        sensitivityTable = PrefHelper.getSensitivityTableOrDefault(this);
        renderSensitivityTable();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION.ACTION_CLOSE_SENSITIVITY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(mDataUpdateReceiver, intentFilter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mDataUpdateReceiver, intentFilter);
        }
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
            if (Constants.ACTION.ACTION_CLOSE_SENSITIVITY.equals(action)) {
                finish();
            }
        }

    };

    public void onOkButton(View v) {
        List<Float> sortedEnergyList = new ArrayList<>(sensitivityTable.keySet());
        Collections.sort(sortedEnergyList);
        String sensInputText = "";
        for (int i = 0; i < sortedEnergyList.size(); i++) {
            try {
                EditText sensInput = findViewById(getSensInputId(i));
                sensInputText = sensInput.getText().toString();
                double sens = Double.parseDouble(sensInputText.replaceAll(",", "."));
                sensitivityTable.put(sortedEnergyList.get(i), sens);
            } catch (Exception e) {
                String message = String.format(Locale.getDefault(), "Wrong value (%s) at row %d", sensInputText, i + 1);
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                return;
            }
        }

        PrefHelper.setSensitivityTable(this, sensitivityTable);
        finish();
    }

    public void onCancelButton(View v) {
        finish();
    }

    public void onResetButton(View v) {
        final AlertDialog.Builder alert = new AlertDialog.Builder(this)
                .setTitle(R.string.sens_reset_dialog_title)
                .setMessage(R.string.sens_reset_dialog_message)
                .setPositiveButton(R.string.sens_reset_dialog_reset_btn, (dialog, whichButton) -> {
                    sensitivityTable = PrefHelper.getDefaultSensitivityTable();
                    renderSensitivityTable();
                })
                .setNegativeButton(android.R.string.cancel, (d ,b) -> {});
        alert.show();
    }

    public void onRemoveButton(View v) {
        int rowIndex = getRowIndexById(v.getId());

        final AlertDialog.Builder alert = new AlertDialog.Builder(this)
                .setTitle(R.string.sens_delete_row_dialog_title)
                .setMessage(getString(R.string.sens_delete_row_dialog_message, rowIndex + 1))
                .setPositiveButton(R.string.sens_delete_row_dialog_delete_btn, (dialog, whichButton) -> {
                    ArrayList<Float> energies = new ArrayList<>(sensitivityTable.keySet());
                    sensitivityTable.remove(energies.get(rowIndex));
                    renderSensitivityTable();
                })
                .setNegativeButton(android.R.string.cancel, (d ,b) -> {});
        alert.show();
    }

    public void onSensFocusChange(View v, Boolean hasFocus) {
        if (!hasFocus) {
            int editRowIndex = getRowIndexById(v.getId());
            ArrayList<Float> sortedEnergyList = new ArrayList<>(sensitivityTable.keySet());
            String sensInputText = "";
            double sens = 0;
            try {
                EditText sensInput = (EditText)v;
                sensInputText = sensInput.getText().toString();
                sens = Double.parseDouble(sensInputText.replaceAll(",", "."));
                sensInput.setText(formatSensitivityValue(sens));
            } catch (Exception e) {
                String message = String.format(Locale.getDefault(), "Wrong value (%s) at row %d", sensInputText, editRowIndex + 1);
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                return;
            }

            sensitivityTable.put(sortedEnergyList.get(editRowIndex), sens);
        }
    }

    public void onAddButton(View v) {
        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
        alert.setTitle(R.string.sens_add_row_dialog_title);
        alert.setMessage(R.string.sens_add_row_dialog_message);

        final EditText input = new EditText(this);
        input.setKeyListener(new NumberKeyListener() {
            @NonNull
            @Override
            protected char[] getAcceptedChars() {
                return new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.'};
            }

            @Override
            public int getInputType() {
                return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
            }
        });
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        alert.setView(input);

        alert.setPositiveButton(R.string.sens_add_row_dialog_add_btn, (dialog, whichButton) -> {
            String valueStr = input.getText().toString();
            float energyValue = 0.0f;
            try {
                energyValue = Float.parseFloat(valueStr);
            } catch (Exception ignored) {
                Toast.makeText(this, R.string.sens_add_row_err_not_a_number, Toast.LENGTH_SHORT).show();
                return;
            }

            if (sensitivityTable.containsKey(energyValue)) {
                Toast.makeText(this, R.string.sens_add_row_err_row_exists, Toast.LENGTH_SHORT).show();
            } else {
                sensitivityTable.put(energyValue, 1.0);
                renderSensitivityTable();
            }
        });
        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {});
        alert.show();
    }

    private void renderSensitivityTable() {
        TableLayout table = findViewById(R.id.tableSensitivity);
        table.removeAllViews();
        Paint text = new Paint();
        text.setTextSize(18);

        List<Float> sortedEnergyList = new ArrayList<>(sensitivityTable.keySet());
        float prevEnergy = 0;
        String binSensText = "0";
        for (int i = 0; i < sortedEnergyList.size(); i++) {
            float currentEnergy = sortedEnergyList.get(i);
            double sens = sensitivityTable.get(currentEnergy);

            String rowNumberText = String.format(Locale.getDefault(),"%d", i + 1);
            String energyBinText = String.format(Locale.getDefault(), "%.0f - %.0f", prevEnergy, currentEnergy);
            binSensText = formatSensitivityValue(sens);

            TableRow newRow = getTableRow(i, rowNumberText, energyBinText, binSensText, false);
            table.addView(newRow);

            prevEnergy = currentEnergy;
        }

        String energyBinText = String.format(Locale.getDefault(), ">%.0f", prevEnergy);
        TableRow newRow = getTableRow(sortedEnergyList.size(), "", energyBinText, binSensText, true);
        table.addView(newRow);

        Button addButton = findViewById(R.id.buttonSensAdd);
        addButton.setEnabled(sortedEnergyList.size() < AtomSpectraService.EnergyBinsDefault.length);
    }

    private static @NonNull String formatSensitivityValue(double sens) {
        return sens == 0 ? "0" : String.format(Locale.getDefault(), "%.6f", sens);
    }

    @NonNull
    private TableRow getTableRow(int rowIndex, String rowNumberText, String energyBinText, String binSensText, Boolean isUpperBoundRow) {
        TextView textNumber = new TextView(this);
        textNumber.setText(rowNumberText);
        textNumber.setLayoutParams(new TableRow.LayoutParams(dpToPx(40), TableRow.LayoutParams.WRAP_CONTENT));
        textNumber.setTextSize(18);
        textNumber.setGravity(Gravity.CENTER);

        TextView textEnergyBin = new TextView(this);
        textEnergyBin.setLayoutParams(new TableRow.LayoutParams(dpToPx(150), TableRow.LayoutParams.WRAP_CONTENT));
        textEnergyBin.setGravity(Gravity.CENTER);
        textEnergyBin.setText(energyBinText);
        textEnergyBin.setId(getEnergyBinTextId(rowIndex));
        textEnergyBin.setTextSize(18);

        EditText editSensitivity = new EditText(this);
        editSensitivity.setLayoutParams(new TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT, 1.0f));
        editSensitivity.setGravity(Gravity.CENTER);
        editSensitivity.setText(binSensText);
        editSensitivity.setTextSize(18);
        editSensitivity.setId(getSensInputId(rowIndex));
        editSensitivity.setHint(R.string.hint_sens_show);
        editSensitivity.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        editSensitivity.setEnabled(!isUpperBoundRow);
        editSensitivity.setKeyListener(new NumberKeyListener() {
            @NonNull
            @Override
            protected char[] getAcceptedChars() {
                return new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.'};
            }

            @Override
            public int getInputType() {
                return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
            }
        });
        editSensitivity.setOnFocusChangeListener(this::onSensFocusChange);

        Button btnRemoveRow = new Button(this);
        btnRemoveRow.setLayoutParams(new TableRow.LayoutParams(dpToPx(100), TableRow.LayoutParams.WRAP_CONTENT));
        btnRemoveRow.setText(R.string.sensitivity_remove_row);
        btnRemoveRow.setTextSize(18);
        btnRemoveRow.setId(getDelRowBtnId(rowIndex));
        btnRemoveRow.setEnabled(!isUpperBoundRow && sensitivityTable.size() > 1);
        btnRemoveRow.setOnClickListener(this::onRemoveButton);

        TableRow newRow = new TableRow(this);
        newRow.addView(textNumber);
        newRow.addView(textEnergyBin);
        newRow.addView(editSensitivity);
        newRow.addView(btnRemoveRow);

        return newRow;
    }

    private int getEnergyBinTextId(int rowIndex) {
        return Constants.GROUPS.GROUP_SENSE_TABLE + 3 * rowIndex + 0;
    }

    private int getSensInputId(int rowIndex) {
        return Constants.GROUPS.GROUP_SENSE_TABLE + 3 * rowIndex + 1;
    }

    private int getDelRowBtnId(int rowIndex) {
        return Constants.GROUPS.GROUP_SENSE_TABLE + 3 * rowIndex + 2;
    }

    private int getRowIndexById(int id) {
        int rowIndexBase = id - Constants.GROUPS.GROUP_SENSE_TABLE;
        int rowIndex = rowIndexBase / 3;

        return rowIndex;
    }

    private int dpToPx(float dp) {
        return ((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, Resources.getSystem().getDisplayMetrics()));
    }
}
