package org.fe57.atomspectra;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.fe57.atomspectra.data.Constants;
import org.fe57.atomspectra.data.Isotope;
import org.fe57.atomspectra.data.Matrix;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Created by S. Epiphanov.
 */
public class AtomSpectraFindIsotope extends Activity implements OnItemSelectedListener {
//    final static String LIST_ISOTOPE_CHANNELS = "Isotope channels";
    private int compression = Constants.ADC_MAX;
    private int poli_order = 5;
    private int library = 0;
    private final static double IRREG_COEFF = 1.5;

    @SuppressLint("RtlHardcoded")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences sp = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        setContentView(R.layout.activity_atom_spectra_find_isotope);
        ((EditText) findViewById(R.id.editWindow)).setText(String.format(Locale.getDefault(), "%d", sp.getInt(Constants.SEARCH.PREF_WINDOW_SIZE, Constants.WINDOW_SEARCH_DEFAULT)));
        ((EditText) findViewById(R.id.editTolerance)).setText(String.format(Locale.getDefault(), "%.2f", sp.getFloat(Constants.SEARCH.PREF_TOLERANCE, Constants.TOLERANCE_DEFAULT)));
        ((EditText) findViewById(R.id.editThreshold)).setText(String.format(Locale.getDefault(), "%.2f", sp.getFloat(Constants.SEARCH.PREF_THRESHOLD, Constants.THRESHOLD_DEFAULT)));
        ((Button) findViewById(R.id.showIsotopes)).setText(AtomSpectraIsotopes.showFoundIsotopes ? getString(R.string.show_no_isotopes) : getString(R.string.show_isotopes));
        compression = Constants.MinMax(sp.getInt(Constants.SEARCH.PREF_COMPRESSION, Constants.ADC_MAX), Constants.ADC_MIN, Constants.ADC_MAX);
        poli_order = sp.getInt(Constants.SEARCH.PREF_ORDER, Constants.ORDER_DEFAULT);
        library = sp.getInt(Constants.SEARCH.PREF_LIBRARY, 0);

        Spinner spinner = findViewById(R.id.selectIsotopeList);
        spinner.setOnItemSelectedListener(this);
        spinner.setSelection(library);

        spinner = findViewById(R.id.selectCompressionList);
        ArrayAdapter<CharSequence> adapter;
        //do not touch this case
        switch (Constants.ADC_MAX) {
            case 13:
                adapter = ArrayAdapter.createFromResource(this, R.array.find_isotopes_bits_13, android.R.layout.simple_spinner_item);
                break;
            case 14:
                adapter = ArrayAdapter.createFromResource(this, R.array.find_isotopes_bits_14, android.R.layout.simple_spinner_item);
                break;
            case 15:
                adapter = ArrayAdapter.createFromResource(this, R.array.find_isotopes_bits_15, android.R.layout.simple_spinner_item);
                break;
            case 16:
                adapter = ArrayAdapter.createFromResource(this, R.array.find_isotopes_bits_16, android.R.layout.simple_spinner_item);
                break;
            default:
                adapter = ArrayAdapter.createFromResource(this, R.array.find_isotopes_bits_13, android.R.layout.simple_spinner_item);
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(this);
        spinner.setSelection(compression - Constants.ADC_MIN);

        spinner = findViewById(R.id.selectOrderList);
        spinner.setOnItemSelectedListener(this);
        spinner.setSelection(poli_order - 2);

        updateIsotopeList();

        try {
            ActionBar bar = getActionBar();
            if (bar != null) {
                bar.setDisplayShowHomeEnabled(true);
                bar.setDisplayHomeAsUpEnabled(true);
            }
        } catch (Exception ignored) {
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION.ACTION_CLOSE_SEARCH);
        intentFilter.addAction(Constants.ACTION.ACTION_UPDATE_ISOTOPE_LIST);
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

    public static Matrix getSavitzkyGolayMatrix(int poli_order, int window) {
        Matrix matrix = new Matrix(poli_order + 1, 2 * window + 1);
        for (int i = 0; i < 2 * window + 1; i++) {
            matrix.array[0][i] = 1.0;
            matrix.array[1][i] = i - window;
        }
        for (int j = 2; j <= poli_order; j++) {
            for (int k = 0; k < 2 * window + 1; k++) {
                matrix.array[j][k] = matrix.array[j - 1][k] * (double) (k - window);
            }
        }
        Matrix b = matrix.Transpose();
        return matrix.Times(b).Inverse().Times(matrix);
    }

    public static double[] calcSavitzkyGolayWeight(int k, int poli_order, int window) {
        Matrix matrix = getSavitzkyGolayMatrix(poli_order, window);
        double[] array = new double[2 * window + 1];
        System.arraycopy(matrix.array[k], 0, array, 0, 2 * window + 1);
        return array;
    }

    public static long[] applySavitzkyGolay(long[] vals, double[] coeffs) {
        long[] res = new long[vals.length];
        int size = (coeffs.length - 1) / 2;
        double temp;
        for (int i = 0; i < vals.length; i++) {
            if (i < size || i >= (vals.length - size))
                res[i] = vals[i];
            else {
                temp = 0.0;
                for (int j = -size; j <= size; j++) {
                    temp += vals[i + j] * coeffs[j + size];
                }
                res[i] = (long) temp;
            }
        }
        return res;
    }

    public static void updateFoundIsotopes() {
        SharedPreferences sp = AtomSpectra.getContext().getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);

        int window = sp.getInt(Constants.SEARCH.PREF_WINDOW_SIZE, Constants.WINDOW_SEARCH_DEFAULT);
        window = Constants.MinMax(window, 5, 200);

        float threshold = sp.getFloat(Constants.SEARCH.PREF_THRESHOLD, Constants.THRESHOLD_DEFAULT);
        threshold = (float) Math.rint(Constants.MinMax(threshold * 100, 0, 1000000)) / 100.0f;

        float tolerance = sp.getFloat(Constants.SEARCH.PREF_TOLERANCE, Constants.TOLERANCE_DEFAULT);
        tolerance = (float) Math.rint(Constants.MinMax(tolerance * 100, 1, 5000)) / 100.0f;
        tolerance /= 100.0;

        int adc_effective_bits = Constants.MinMax(sp.getInt(Constants.CONFIG.CONF_ROUNDED, Constants.ADC_DEFAULT), Constants.ADC_MIN, Constants.ADC_MAX);

        int compression = Constants.MinMax(sp.getInt(Constants.SEARCH.PREF_COMPRESSION, Constants.ADC_MAX), Constants.ADC_MIN, Constants.ADC_MAX);
        int poli_order = sp.getInt(Constants.SEARCH.PREF_ORDER, Constants.ORDER_DEFAULT);
        int library = sp.getInt(Constants.SEARCH.PREF_LIBRARY, 0);

        int num_lines = Constants.NUM_HIST_POINTS >> (Constants.ADC_MAX - compression);
        int num_scale = 1 << (Constants.ADC_MAX - compression);
        long[] chan_raw = new long[num_lines];

        if (AtomSpectra.background_subtract) {
            double backgroundScale = (double) AtomSpectraService.ForegroundSpectrum.getSpectrumTime() / (double) AtomSpectraService.BackgroundSpectrum.getSpectrumTime();
            long[] data = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toChannel(AtomSpectraService.BackgroundSpectrum.getSpectrum(), adc_effective_bits, AtomSpectraService.BackgroundSpectrum.getSpectrumCalibration(), AtomSpectraService.lastCalibrationChannel);
            for (int i = 0; i < num_lines; i++) {
                for (int j = i * num_scale; j < (i + 1) * num_scale; j++)
                    chan_raw[i] += StrictMath.max(0.0, AtomSpectraService.ForegroundSpectrum.getSpectrum()[j] - data[j] * backgroundScale);
            }
        } else {
            for (int i = 0; i < num_lines; i++) {
                for (int j = i * num_scale; j < (i + 1) * num_scale; j++)
                    chan_raw[i] += AtomSpectraService.ForegroundSpectrum.getSpectrum()[j];
            }
        }
        //filter for high single peaks or drops
//        for (int i = 0; i < num_lines; i++) {
//            if (i < 2 || i >= (num_lines - 2)) {
//                channels[i] = chan_raw[i];
//            } else {
//                long mid = (chan_raw[i - 2] + chan_raw[i - 1] + chan_raw[i] + chan_raw[i + 1] + chan_raw[i + 2]) / 5;
//                long mid_without = (chan_raw[i - 2] + chan_raw[i - 1] + chan_raw[i + 1] + chan_raw[i + 2]) / 4;
//                if (chan_raw[i] > mid_without * IRREG_COEFF || chan_raw[i] < mid_without / IRREG_COEFF)
//                    channels[i] = mid_without;
//                else
//                    channels[i] = mid;
//            }
//        }
        //search for lines
        double max_energy = 0;
        for (int i = 0; i < num_lines; i++)
            max_energy = StrictMath.max(chan_raw[i], max_energy);
//        Matrix SavitzkyGolay = getSavitzkyGolayMatrix(poli_order, window);
        double[] coeffs = new double[2 * window + 1];
        int shift_window, old_window = 0;
        int channel_0 = StrictMath.max (100, AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toChannel(662.0));
        double[] peak_array = new double[num_lines];
        for (int i = 0; i < num_lines; i++) {
            shift_window = StrictMath.max((int)((0.3 + 0.7 * StrictMath.sqrt(i / (double)channel_0)) * window), 4);
            shift_window = StrictMath.min(shift_window, 200);
            if (old_window != shift_window) {
                old_window = shift_window;
                coeffs = calcSavitzkyGolayWeight(1, poli_order, shift_window);
            }
            if (i < shift_window)
                continue;
            if (i >= (num_lines - shift_window))
                break;
            for (int j = i - shift_window; j <= i + shift_window; j++) {
                peak_array[i] += chan_raw[j] * coeffs[j - (i - shift_window)];
            }
        }
        double prev_Peak = 0.0;
        double cur_Peak;
        double max_Peak = 0.0;
        double peak_energy;
        long max_Channel = 0;
        AtomSpectraIsotopes.foundList.clear();
        for (int i = window; i < num_lines - window; i++) {
            cur_Peak = peak_array[i];
            if (max_Peak < cur_Peak) {
                max_Peak = cur_Peak;
            }
            if (max_Channel < chan_raw[i]) {
                max_Channel = chan_raw[i];
            }
//            if ((prev_Peak > 0.0) && (cur_Peak < 0.0) && (channels[i] > 3) && (max_Peak > threshold / Math.cbrt(max_energy / max_Channel) / Math.sqrt((i + 100.0) / 100.0)))
//            if (prev_Peak > 0.0 && cur_Peak < 0.0 && channels[i] > 3 && max_Peak > threshold / Math.sqrt(max_energy / channels[i] * (i + 100.0) / 100.0))
//            if (prev_Peak > 0.0 && cur_Peak < 0.0 && channels[i] > 3 && max_Peak > threshold / (Math.sqrt(max_energy / channels[i]) * (i + 100.0) / 100.0))
//            if (prev_Peak > 0.0 && cur_Peak < 0.0 && channels[i] > 3 && max_Peak > threshold / (max_energy / channels[i] * Math.sqrt((i + 100.0) / 100.0)))
            if (prev_Peak > 0.0 && cur_Peak < 0.0 && max_Channel > 3 && max_Peak > threshold) {
//            if (prev_Peak > 0.0 && cur_Peak < 0.0 && max_Channel > 5 && (golay_array[i] > threshold * square_golay_array[i]) ) {
                if (chan_raw[i] > chan_raw[i - 1]) {
                    peak_energy = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(i * num_scale);
                } else {
                    peak_energy = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy((i - 1) * num_scale);
                }
                if (peak_energy <= 0)
                    continue;
                double delta = peak_energy * tolerance;
                int pos = -1;
                String name = Isotope.NonElement;
                double halfLife = Isotope.Stable;
                for (int j = 0; j < AtomSpectraIsotopes.isotopeLineArray.size(); j++) {
                    if (delta > StrictMath.abs(peak_energy - AtomSpectraIsotopes.isotopeLineArray.get(j).getEnergy(0))) {
                        if (library <= 1 || AtomSpectraIsotopes.IAEAList[library - 2].isInChain(AtomSpectraIsotopes.isotopeLineArray.get(j).getName())) {
                            delta = StrictMath.abs(peak_energy - AtomSpectraIsotopes.isotopeLineArray.get(j).getEnergy(0));
                            name = AtomSpectraIsotopes.isotopeLineArray.get(j).getName();
                            pos = j;
                            halfLife = AtomSpectraIsotopes.isotopeLineArray.get(j).getHalfLife();
                        }
                    }
                }
                if (library == 0) {
                    AtomSpectraIsotopes.foundList.addLast(new Isotope(name, halfLife).addEnergy(peak_energy, 100));
                } else /*if (library == 1)*/ {
                    if (pos != -1)
                        AtomSpectraIsotopes.foundList.addLast(new Isotope(name, Isotope.Stable).addEnergy(peak_energy, 100));
                }
                max_Peak = 0.0;
                max_Channel = 0;
            }
            prev_Peak = cur_Peak;
        }
    }

    @SuppressLint("RtlHardcoded")
    public void onClickFindIsotopes(View view) {
        SharedPreferences sp = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int window = sp.getInt(Constants.SEARCH.PREF_WINDOW_SIZE, Constants.WINDOW_SEARCH_DEFAULT);
        try {
            Number val = NumberFormat.getIntegerInstance().parse(((EditText) findViewById(R.id.editWindow)).getText().toString());
            if (val != null)
                window = val.intValue();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.find_error_window), Toast.LENGTH_LONG).show();
            return;
        }
        window = Constants.MinMax(window, 5, 200);
        ((EditText) findViewById(R.id.editWindow)).setText(String.format(Locale.getDefault(), "%d", window));

        float threshold = sp.getFloat(Constants.SEARCH.PREF_THRESHOLD, Constants.THRESHOLD_DEFAULT);
        try {
            Number val = NumberFormat.getNumberInstance().parse(((EditText) findViewById(R.id.editThreshold)).getText().toString());
            if (val != null)
                threshold = val.floatValue();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.find_error_threshold), Toast.LENGTH_LONG).show();
            return;
        }
        threshold = (float) Math.rint(Constants.MinMax(threshold * 100, 0, 1000000)) / 100.0f;
        ((EditText) findViewById(R.id.editThreshold)).setText(String.format(Locale.getDefault(), "%.2f", threshold));

        float tolerance = sp.getFloat(Constants.SEARCH.PREF_TOLERANCE, Constants.TOLERANCE_DEFAULT);
        try {
            Number val = NumberFormat.getNumberInstance().parse(((EditText) findViewById(R.id.editTolerance)).getText().toString());
            if (val != null)
                tolerance = val.floatValue();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.find_error_tolerance), Toast.LENGTH_LONG).show();
            return;
        }
        tolerance = (float) Math.rint(Constants.MinMax(tolerance * 100, 1, 5000)) / 100.0f;
        ((EditText) findViewById(R.id.editTolerance)).setText(String.format(Locale.getDefault(), "%.2f", tolerance));

        SharedPreferences.Editor spEditor = sp.edit();
        spEditor.putInt(Constants.SEARCH.PREF_COMPRESSION, compression);
        spEditor.putInt(Constants.SEARCH.PREF_ORDER, poli_order);
        spEditor.putInt(Constants.SEARCH.PREF_LIBRARY, library);
        spEditor.putInt(Constants.SEARCH.PREF_WINDOW_SIZE, window);
        spEditor.putFloat(Constants.SEARCH.PREF_THRESHOLD, threshold);
        spEditor.putFloat(Constants.SEARCH.PREF_TOLERANCE, tolerance);
        spEditor.commit();
        ((Button) findViewById(R.id.showIsotopes)).setText(getString(R.string.show_isotopes));
        AtomSpectraIsotopes.showFoundIsotopes = false;

        updateFoundIsotopes();
        updateIsotopeList();
    }

    public void updateIsotopeList() {
        LinearLayout layout = findViewById(R.id.listIsotopeFound);
        if (layout == null)
            return;
        layout.removeAllViews();
        for (int i = 0; i < AtomSpectraIsotopes.foundList.size(); i++) {
            TextView textView = new TextView(this);
            textView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            textView.setGravity(Gravity.LEFT);
            textView.setText(getString(R.string.find_isotopes_info, AtomSpectraIsotopes.foundList.get(i).getName(), AtomSpectraIsotopes.foundList.get(i).getEnergy(0)));
            textView.setTextColor(0xFFFFFFFF);
            textView.setTextSize(22);
            textView.setMinHeight(28);
            textView.setId(Constants.GROUPS.GROUP_ISOTOPES_DETECTED + i);
            layout.addView(textView);
        }
    }

    public void onClickShowIsotopes(View view) {
        AtomSpectraIsotopes.showFoundIsotopes = !AtomSpectraIsotopes.showFoundIsotopes;
        ((Button) findViewById(R.id.showIsotopes)).setText(AtomSpectraIsotopes.showFoundIsotopes ? getString(R.string.show_no_isotopes) : getString(R.string.show_isotopes));
        if (!AtomSpectraIsotopes.showFoundIsotopes) {
            for (Isotope isotope: AtomSpectraIsotopes.foundList) {
                isotope.setCoord(null);
            }
        }
    }

    private final BroadcastReceiver mDataUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Constants.ACTION.ACTION_CLOSE_SEARCH.equals(action)) {
                finish();
            }
            if (Constants.ACTION.ACTION_UPDATE_ISOTOPE_LIST.equals(action)) {
                updateIsotopeList();
            }
        }

    };

    public void onCloseButton(View v) {
        if (v.getId() == R.id.findClose) {
//            onBackPressed();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mDataUpdateReceiver);
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (parent.getId() == R.id.selectIsotopeList) {
            library = position;
        }
        if (parent.getId() == R.id.selectCompressionList) {
            compression = 10 + position;
        }
        if (parent.getId() == R.id.selectOrderList) {
            poli_order = 2 + position;
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        //Nothing to do
    }
}