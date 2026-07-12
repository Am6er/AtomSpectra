package org.fe57.atomspectra;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.Uri;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Dose-rate / sensitivity configuration screen. Owns everything dose-related: the active profile
 * selector, the non-compensated sensitivity + search windows, and the compensated curve + search
 * windows. Built-in profiles are read-only (with a "Customize" action); the custom profile is
 * editable and can be loaded from / saved to a profile file.
 * <p>
 * Edits are held in memory and only persisted on OK; switching profile repopulates the view.
 */
public class AtomSpectraSensitivity extends Activity {

    private static final int REQUEST_SAVE_PROFILE = 401;
    private static final int REQUEST_LOAD_PROFILE = 402;

    private static final String[] PROFILE_IDS = {
            SensitivityProfile.ID_NANO3,
            SensitivityProfile.ID_NANO8,
            SensitivityProfile.ID_NANO15,
            SensitivityProfile.ID_CUSTOM,
    };
    private static final int CUSTOM_INDEX = 3;

    // In-memory editable custom profile; edits accumulate here until OK.
    private SensitivityProfile customWorking;
    // The profile currently shown (a built-in copy, or a reference to customWorking).
    private SensitivityProfile workingProfile;
    private String selectedId;
    private boolean editable;
    // Compensated curve backing the table editor; mirrored into workingProfile on change.
    private TreeMap<Float, Double> curve = new TreeMap<>();

    private Spinner spinnerProfile;
    private ArrayAdapter<String> spinnerAdapter;
    private final List<String> spinnerLabels = new ArrayList<>();
    private boolean spinnerReady = false;

    private EditText editNonCompSens, editNonCompFast, editNonCompMedium, editNonCompSlow;
    private EditText editCompFast, editCompMedium, editCompSlow;

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

        editNonCompSens = findViewById(R.id.editNonCompSens);
        editNonCompFast = findViewById(R.id.editNonCompFast);
        editNonCompMedium = findViewById(R.id.editNonCompMedium);
        editNonCompSlow = findViewById(R.id.editNonCompSlow);
        editCompFast = findViewById(R.id.editCompFast);
        editCompMedium = findViewById(R.id.editCompMedium);
        editCompSlow = findViewById(R.id.editCompSlow);

        customWorking = PrefHelper.getCustomSensitivityProfile(this);
        selectedId = PrefHelper.getActiveSensitivityProfileId(this);

        setupSpinner();
        selectWorkingFor(selectedId);
        populate();

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
    protected void onDestroy() {
        try {
            unregisterReceiver(mDataUpdateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
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

    // --- Profile selection --------------------------------------------------------------------

    private void setupSpinner() {
        spinnerProfile = findViewById(R.id.spinnerProfile);
        rebuildSpinnerLabels();
        spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, spinnerLabels);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProfile.setAdapter(spinnerAdapter);
        spinnerProfile.setSelection(indexOfId(selectedId));
        spinnerProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!spinnerReady) {
                    return;
                }
                String newId = PROFILE_IDS[position];
                if (newId.equals(selectedId)) {
                    return;
                }
                flushScalars();
                selectWorkingFor(newId);
                populate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        spinnerReady = true;
    }

    private void rebuildSpinnerLabels() {
        spinnerLabels.clear();
        spinnerLabels.add(SensitivityProfile.ID_NANO3);
        spinnerLabels.add(SensitivityProfile.ID_NANO8);
        spinnerLabels.add(SensitivityProfile.ID_NANO15);
        spinnerLabels.add(customWorking.name);
        if (spinnerAdapter != null) {
            spinnerAdapter.notifyDataSetChanged();
        }
    }

    private int indexOfId(String id) {
        for (int i = 0; i < PROFILE_IDS.length; i++) {
            if (PROFILE_IDS[i].equals(id)) {
                return i;
            }
        }
        return CUSTOM_INDEX;
    }

    /** Point {@link #workingProfile} at the built-in copy or the in-memory custom for {@code id}. */
    private void selectWorkingFor(String id) {
        selectedId = id;
        if (SensitivityProfile.ID_CUSTOM.equals(id)) {
            workingProfile = customWorking;
            editable = true;
        } else {
            workingProfile = SensitivityProfile.builtInById(id);
            editable = false;
        }
    }

    // --- View <-> model ----------------------------------------------------------------------

    private void populate() {
        editNonCompSens.setText(formatDecimal(workingProfile.nonCompPsvPerCount));
        editNonCompFast.setText(formatInt(workingProfile.nonCompFast));
        editNonCompMedium.setText(formatInt(workingProfile.nonCompMedium));
        editNonCompSlow.setText(formatInt(workingProfile.nonCompSlow));
        editCompFast.setText(formatInt(workingProfile.compFast));
        editCompMedium.setText(formatInt(workingProfile.compMedium));
        editCompSlow.setText(formatInt(workingProfile.compSlow));

        curve = workingProfile.compCurveAsMap();
        renderSensitivityTable();
        applyEditableState();
    }

    private void applyEditableState() {
        setEnabled(editNonCompSens, editable);
        setEnabled(editNonCompFast, editable);
        setEnabled(editNonCompMedium, editable);
        setEnabled(editNonCompSlow, editable);
        setEnabled(editCompFast, editable);
        setEnabled(editCompMedium, editable);
        setEnabled(editCompSlow, editable);

        Button addButton = findViewById(R.id.buttonSensAdd);
        addButton.setEnabled(editable && curve.size() < SensitivityProfile.MAX_BINS);

        findViewById(R.id.builtInActions).setVisibility(editable ? View.GONE : View.VISIBLE);
        findViewById(R.id.customActions).setVisibility(editable ? View.VISIBLE : View.GONE);
    }

    private static void setEnabled(EditText e, boolean enabled) {
        e.setEnabled(enabled);
        e.setFocusable(enabled);
        e.setFocusableInTouchMode(enabled);
    }

    /** Read the inline fields into {@link #workingProfile} (best-effort; keeps prior value on error). */
    private void flushScalars() {
        if (!editable) {
            return;
        }
        workingProfile.nonCompPsvPerCount = parseDoubleOr(editNonCompSens, workingProfile.nonCompPsvPerCount);
        workingProfile.nonCompFast = parseIntOr(editNonCompFast, workingProfile.nonCompFast);
        workingProfile.nonCompMedium = parseIntOr(editNonCompMedium, workingProfile.nonCompMedium);
        workingProfile.nonCompSlow = parseIntOr(editNonCompSlow, workingProfile.nonCompSlow);
        workingProfile.compFast = parseIntOr(editCompFast, workingProfile.compFast);
        workingProfile.compMedium = parseIntOr(editCompMedium, workingProfile.compMedium);
        workingProfile.compSlow = parseIntOr(editCompSlow, workingProfile.compSlow);
        workingProfile.setCompCurveFromMap(curve);
    }

    private boolean validate() {
        flushScalars();
        if (workingProfile.nonCompPsvPerCount <= 0) {
            toast(getString(R.string.profile_err_noncomp_positive));
            return false;
        }
        if (!targetsValid(workingProfile.nonCompFast, workingProfile.nonCompMedium, workingProfile.nonCompSlow)
                || !targetsValid(workingProfile.compFast, workingProfile.compMedium, workingProfile.compSlow)) {
            toast(getString(R.string.profile_err_targets_positive));
            return false;
        }
        if (curve.isEmpty()) {
            toast(getString(R.string.profile_err_no_bins));
            return false;
        }
        return true;
    }

    private static boolean targetsValid(int a, int b, int c) {
        return a > 0 && b > 0 && c > 0;
    }

    // --- Bottom-bar actions ------------------------------------------------------------------

    public void onOkButton(View v) {
        if (!validate()) {
            return;
        }
        PrefHelper.setActiveSensitivityProfile(this, selectedId);
        if (SensitivityProfile.ID_CUSTOM.equals(selectedId)) {
            PrefHelper.setCustomSensitivityProfile(this, customWorking);
        }
        finish();
    }

    public void onCancelButton(View v) {
        finish();
    }

    /** Built-in mode: clone the shown built-in into the custom profile and switch to editing it. */
    public void onCustomizeButton(View v) {
        final String sourceName = workingProfile.name;
        new AlertDialog.Builder(this)
                .setTitle(R.string.profile_customize)
                .setMessage(getString(R.string.profile_customize_confirm, sourceName))
                .setPositiveButton(R.string.profile_customize, (d, w) -> cloneIntoCustom(workingProfile))
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                })
                .show();
    }

    /** Custom mode: reset the custom profile to a chosen built-in's values. */
    public void onResetToButton(View v) {
        final String[] names = {SensitivityProfile.ID_NANO3, SensitivityProfile.ID_NANO8, SensitivityProfile.ID_NANO15};
        new AlertDialog.Builder(this)
                .setTitle(R.string.profile_reset_to)
                .setItems(names, (d, which) -> cloneIntoCustom(SensitivityProfile.builtInById(names[which])))
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                })
                .show();
    }

    /** Clone {@code source} into the custom slot (in memory) and show it in editable mode. */
    private void cloneIntoCustom(SensitivityProfile source) {
        customWorking = source.editableCopy(getString(R.string.profile_custom_name_default));
        rebuildSpinnerLabels();
        if (SensitivityProfile.ID_CUSTOM.equals(selectedId)) {
            selectWorkingFor(SensitivityProfile.ID_CUSTOM);
            populate();
        } else {
            spinnerProfile.setSelection(CUSTOM_INDEX); // triggers selection -> selectWorkingFor + populate
        }
    }

    // --- Profile file I/O ---------------------------------------------------------------------

    public void onSaveProfileButton(View v) {
        if (!validate()) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, sanitizeFileName(customWorking.name) + ".txt");
        try {
            startActivityForResult(intent, REQUEST_SAVE_PROFILE);
        } catch (Exception e) {
            toast(getString(R.string.profile_save_error));
        }
    }

    public void onLoadProfileButton(View v) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.ask_select_profile_file)), REQUEST_LOAD_PROFILE);
        } catch (Exception e) {
            toast(getString(R.string.profile_load_error, ""));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_SAVE_PROFILE) {
            saveProfileToFile(uri);
        } else if (requestCode == REQUEST_LOAD_PROFILE) {
            loadProfileFromFile(uri);
        }
    }

    private void saveProfileToFile(Uri uri) {
        try (OutputStream os = getContentResolver().openOutputStream(uri, "wt");
             OutputStreamWriter writer = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            SensitivityProfileFile.write(writer, customWorking);
            toast(getString(R.string.profile_saved));
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, android.util.Log.getStackTraceString(e));
            toast(getString(R.string.profile_save_error));
        }
    }

    private void loadProfileFromFile(Uri uri) {
        SensitivityProfile loaded;
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            loaded = SensitivityProfileFile.read(reader);
        } catch (SensitivityProfileFile.FormatException e) {
            toast(getString(R.string.profile_load_error, e.getMessage()));
            return;
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, android.util.Log.getStackTraceString(e));
            toast(getString(R.string.profile_load_error, ""));
            return;
        }
        customWorking = loaded;
        rebuildSpinnerLabels();
        if (SensitivityProfile.ID_CUSTOM.equals(selectedId)) {
            selectWorkingFor(SensitivityProfile.ID_CUSTOM);
            populate();
        } else {
            spinnerProfile.setSelection(CUSTOM_INDEX);
        }
        toast(getString(R.string.profile_loaded));
    }

    private static String sanitizeFileName(String name) {
        String s = name == null ? "" : name.trim().replaceAll("[^a-zA-Z0-9-_ ]", "_");
        return s.isEmpty() ? "profile" : s;
    }

    // --- Compensated curve table -------------------------------------------------------------

    public void onRemoveButton(View v) {
        if (!editable) {
            return;
        }
        int rowIndex = getRowIndexById(v.getId());
        new AlertDialog.Builder(this)
                .setTitle(R.string.sens_delete_row_dialog_title)
                .setMessage(getString(R.string.sens_delete_row_dialog_message, rowIndex + 1))
                .setPositiveButton(R.string.sens_delete_row_dialog_delete_btn, (dialog, whichButton) -> {
                    ArrayList<Float> energies = new ArrayList<>(curve.keySet());
                    curve.remove(energies.get(rowIndex));
                    workingProfile.setCompCurveFromMap(curve);
                    renderSensitivityTable();
                    applyEditableState();
                })
                .setNegativeButton(android.R.string.cancel, (d, b) -> {
                })
                .show();
    }

    public void onEditButton(View v) {
        if (!editable) {
            return;
        }
        int rowIndex = getRowIndexById(v.getId());
        ArrayList<Float> energies = new ArrayList<>(curve.keySet());
        float energy = energies.get(rowIndex);
        double sens = curve.get(energy);

        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
        alert.setTitle(R.string.sens_edit_row_dialog_title);
        alert.setMessage(getString(R.string.sens_edit_row_dialog_message, rowIndex + 1));

        final EditText input = new EditText(this);
        input.setText(formatDecimal(sens));
        input.setKeyListener(decimalKeyListener());
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        alert.setView(input);

        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
            String valueStr = input.getText().toString();
            double sensValue;
            try {
                sensValue = Double.parseDouble(valueStr.trim().replace(',', '.'));
            } catch (Exception ignored) {
                toast(getString(R.string.sens_row_err_not_a_number, valueStr));
                return;
            }
            if (sensValue < 0) {
                toast(getString(R.string.profile_err_bin_nonnegative));
                return;
            }
            curve.put(energy, sensValue);
            workingProfile.setCompCurveFromMap(curve);
            renderSensitivityTable();
        });
        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
        });
        alert.show();
    }

    public void onAddButton(View v) {
        if (!editable) {
            return;
        }
        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
        alert.setTitle(R.string.sens_add_row_dialog_title);
        alert.setMessage(R.string.sens_add_row_dialog_message);

        final EditText input = new EditText(this);
        input.setKeyListener(decimalKeyListener());
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        alert.setView(input);

        alert.setPositiveButton(R.string.sens_add_row_dialog_add_btn, (dialog, whichButton) -> {
            String valueStr = input.getText().toString();
            float energyValue;
            try {
                energyValue = Float.parseFloat(valueStr.trim().replace(',', '.'));
            } catch (Exception ignored) {
                toast(getString(R.string.sens_row_err_not_a_number, valueStr));
                return;
            }
            if (energyValue <= 0) {
                toast(getString(R.string.profile_err_edge_positive));
                return;
            }
            if (curve.containsKey(energyValue)) {
                toast(getString(R.string.sens_add_row_err_row_exists));
            } else {
                curve.put(energyValue, 1.0);
                workingProfile.setCompCurveFromMap(curve);
                renderSensitivityTable();
                applyEditableState();
            }
        });
        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
        });
        alert.show();
    }

    private void renderSensitivityTable() {
        TableLayout table = findViewById(R.id.tableSensitivity);
        table.removeAllViews();

        List<Float> sortedEnergyList = new ArrayList<>(curve.keySet());
        float prevEnergy = 0;
        String binSensText = "0";
        for (int i = 0; i < sortedEnergyList.size(); i++) {
            float currentEnergy = sortedEnergyList.get(i);
            double sens = curve.get(currentEnergy);

            String rowNumberText = String.format(Locale.getDefault(), "%d", i + 1);
            String energyBinText = String.format(Locale.getDefault(), "%.0f - %.0f", prevEnergy, currentEnergy);
            binSensText = formatDecimal(sens);

            TableRow newRow = getTableRow(i, rowNumberText, energyBinText, binSensText, false);
            table.addView(newRow);

            prevEnergy = currentEnergy;
        }

        String energyBinText = String.format(Locale.getDefault(), ">%.0f", prevEnergy);
        TableRow newRow = getTableRow(sortedEnergyList.size(), "", energyBinText, binSensText, true);
        table.addView(newRow);
    }

    @NonNull
    private TableRow getTableRow(int rowIndex, String rowNumberText, String energyBinText, String binSensText, boolean isUpperBoundRow) {
        TextView textNumber = new TextView(this);
        textNumber.setText(rowNumberText);
        textNumber.setLayoutParams(new TableRow.LayoutParams(dpToPx(40), TableRow.LayoutParams.MATCH_PARENT));
        textNumber.setTextSize(18);
        textNumber.setGravity(Gravity.CENTER);

        TextView textEnergyBin = new TextView(this);
        textEnergyBin.setLayoutParams(new TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.MATCH_PARENT, 2.0f));
        textEnergyBin.setGravity(Gravity.CENTER);
        textEnergyBin.setText(energyBinText);
        textEnergyBin.setId(getEnergyBinTextId(rowIndex));
        textEnergyBin.setTextSize(18);

        TextView textSensitivity = new TextView(this);
        textSensitivity.setLayoutParams(new TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.MATCH_PARENT, 1.0f));
        textSensitivity.setGravity(Gravity.CENTER);
        textSensitivity.setText(binSensText);
        textSensitivity.setId(getSensInputId(rowIndex));
        textSensitivity.setTextSize(18);

        Button btnEditRow = new Button(this);
        TableRow.LayoutParams btnEditlayoutParams = new TableRow.LayoutParams(dpToPx(64), dpToPx(40));
        btnEditlayoutParams.setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
        btnEditRow.setLayoutParams(btnEditlayoutParams);
        btnEditRow.setText(R.string.sensitivity_edit_row);
        btnEditRow.setId(getEditRowBtnId(rowIndex));
        btnEditRow.setEnabled(editable && !isUpperBoundRow);
        btnEditRow.setTextSize(16);
        btnEditRow.setOnClickListener(this::onEditButton);

        Button btnRemoveRow = new Button(this);
        TableRow.LayoutParams btnRemovelayoutParams = new TableRow.LayoutParams(dpToPx(64), dpToPx(40));
        btnRemovelayoutParams.setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
        btnRemoveRow.setLayoutParams(btnRemovelayoutParams);
        btnRemoveRow.setText(R.string.sensitivity_remove_row);
        btnRemoveRow.setId(getDelRowBtnId(rowIndex));
        btnRemoveRow.setEnabled(editable && !isUpperBoundRow && curve.size() > 1);
        btnRemoveRow.setTextSize(16);
        btnRemoveRow.setOnClickListener(this::onRemoveButton);

        TableRow newRow = new TableRow(this);
        newRow.addView(textNumber);
        newRow.addView(textEnergyBin);
        newRow.addView(textSensitivity);
        newRow.addView(btnEditRow);
        newRow.addView(btnRemoveRow);

        return newRow;
    }

    // --- helpers -----------------------------------------------------------------------------

    private NumberKeyListener decimalKeyListener() {
        return new NumberKeyListener() {
            @NonNull
            @Override
            protected char[] getAcceptedChars() {
                return new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.'};
            }

            @Override
            public int getInputType() {
                return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
            }
        };
    }

    private int parseIntOr(EditText e, int fallback) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private double parseDoubleOr(EditText e, double fallback) {
        try {
            return Double.parseDouble(e.getText().toString().trim().replace(',', '.'));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static String formatInt(int v) {
        return String.format(Locale.US, "%d", v);
    }

    private static String formatDecimal(double v) {
        if (v == 0) {
            return "0";
        }
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return String.format(Locale.US, "%d", (long) v);
        }
        String s = String.format(Locale.US, "%.6f", v);
        s = s.replaceAll("0+$", "");
        s = s.replaceAll("\\.$", "");
        return s;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int getEnergyBinTextId(int rowIndex) {
        return Constants.GROUPS.GROUP_SENSE_TABLE + 4 * rowIndex;
    }

    private int getSensInputId(int rowIndex) {
        return Constants.GROUPS.GROUP_SENSE_TABLE + 4 * rowIndex + 1;
    }

    private int getEditRowBtnId(int rowIndex) {
        return Constants.GROUPS.GROUP_SENSE_TABLE + 4 * rowIndex + 2;
    }

    private int getDelRowBtnId(int rowIndex) {
        return Constants.GROUPS.GROUP_SENSE_TABLE + 4 * rowIndex + 3;
    }

    private int getRowIndexById(int id) {
        return (id - Constants.GROUPS.GROUP_SENSE_TABLE) / 4;
    }

    private int dpToPx(float dp) {
        return ((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, Resources.getSystem().getDisplayMetrics()));
    }
}
