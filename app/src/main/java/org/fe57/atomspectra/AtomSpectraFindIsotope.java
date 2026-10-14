package org.fe57.atomspectra;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
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

import java.text.NumberFormat;
import java.util.Locale;

public class AtomSpectraFindIsotope extends Activity implements OnItemSelectedListener {
    private static final int POLI_ORDER = 5;
    private static final double REFERENCE_ENERGY = 662.0;
    private static final int FALLBACK_WINDOW_DIVIDER = 128;
    private static final int MIN_WINDOW = 5;
    private static final int MAX_WINDOW = 200;
    private static final float MIN_RESOLUTION = 1.0f;
    private static final float MAX_RESOLUTION = 30.0f;
    private static final float MIN_THRESHOLD = 1.0f;
    private static final float MAX_THRESHOLD = 20.0f;

    private static boolean fallbackWarned = false;

    private int[] channelOptions;
    private int channels = Constants.SEARCH_CHANNELS_DEFAULT;
    private int library = 0;

    @Override
    protected void attachBaseContext(Context newBase) {
        String lang = PrefHelper.getLocale(newBase);
        super.attachBaseContext(LocaleContextWrapper.wrap(newBase, lang));
    }

    @SuppressLint("RtlHardcoded")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences sp = PrefHelper.getASSharedPreferences(this);
        setContentView(R.layout.activity_atom_spectra_find_isotope);
        ((EditText) findViewById(R.id.editResolution)).setText(String.format(Locale.getDefault(), "%.1f", sp.getFloat(Constants.SEARCH.PREF_RESOLUTION, Constants.RESOLUTION_DEFAULT)));
        ((EditText) findViewById(R.id.editTolerance)).setText(String.format(Locale.getDefault(), "%.2f", sp.getFloat(Constants.SEARCH.PREF_TOLERANCE, Constants.TOLERANCE_DEFAULT)));
        ((EditText) findViewById(R.id.editThreshold)).setText(String.format(Locale.getDefault(), "%.1f", sp.getFloat(Constants.SEARCH.PREF_THRESHOLD, Constants.THRESHOLD_DEFAULT)));
        ((Button) findViewById(R.id.showIsotopes)).setText(AtomSpectraIsotopes.showFoundIsotopes ? getString(R.string.show_no_isotopes) : getString(R.string.show_isotopes));
        library = sp.getInt(Constants.SEARCH.PREF_LIBRARY, 0);

        Spinner spinner = findViewById(R.id.selectIsotopeList);
        spinner.setOnItemSelectedListener(this);
        spinner.setSelection(library);

        spinner = findViewById(R.id.selectChannelsList);
        channelOptions = getChannelOptions(SpectrumData.instance.getChannelCount());
        String[] channelLabels = new String[channelOptions.length];
        int selectedChannels = 0;
        int savedChannels = sp.getInt(Constants.SEARCH.PREF_CHANNELS, Constants.SEARCH_CHANNELS_DEFAULT);
        for (int i = 0; i < channelOptions.length; i++) {
            channelLabels[i] = String.format(Locale.getDefault(), "%d", channelOptions[i]);
            if (StrictMath.abs(channelOptions[i] - savedChannels) < StrictMath.abs(channelOptions[selectedChannels] - savedChannels))
                selectedChannels = i;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, channelLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(this);
        spinner.setSelection(selectedChannels);
        channels = channelOptions[selectedChannels];

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(mDataUpdateReceiver, intentFilter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mDataUpdateReceiver, intentFilter);
        }
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

    public static int[] getChannelOptions(int channelCount) {
        int count = 0;
        for (int n = channelCount; n >= Constants.SEARCH_MIN_CHANNELS; n >>= 1)
            count++;
        if (count == 0)
            return new int[]{channelCount};
        int[] options = new int[count];
        for (int i = 0, n = channelCount; i < count; i++, n >>= 1)
            options[count - 1 - i] = n;
        return options;
    }

    // half-window (in compressed channels) for the peak of the given resolution at the reference energy
    private static int calcWindow(Context context, Calibration calibration, double resolution, int numScale) {
        double fwhm = REFERENCE_ENERGY * resolution / 100.0;
        int lastChannel = SpectrumData.instance.getLastCalibrationChannel();
        double low = calibration.toChannelDouble(REFERENCE_ENERGY - fwhm / 2.0, lastChannel);
        double high = calibration.toChannelDouble(REFERENCE_ENERGY + fwhm / 2.0, lastChannel);
        if (low < 0 || high >= SpectrumData.instance.getChannelCount() || high <= low) {
            //called every second, warn once until the calibration becomes usable again
            if (!fallbackWarned) {
                fallbackWarned = true;
                ToastHelper.showWarningAndLog(context, LogTag.FIND_ISOTOPE_ACT, R.string.find_warning_window);
            }
            return Constants.MinMax(SpectrumData.instance.getChannelCount() / numScale / FALLBACK_WINDOW_DIVIDER, MIN_WINDOW, MAX_WINDOW);
        }
        fallbackWarned = false;
        return Constants.MinMax((int) Math.rint((high - low) / numScale / 2.0), MIN_WINDOW, MAX_WINDOW);
    }

    public static void updateFoundIsotopes(Context context) {
        SharedPreferences sp = PrefHelper.getASSharedPreferences(context);

        float resolution = Constants.MinMax(sp.getFloat(Constants.SEARCH.PREF_RESOLUTION, Constants.RESOLUTION_DEFAULT), MIN_RESOLUTION, MAX_RESOLUTION);
        float threshold = Constants.MinMax(sp.getFloat(Constants.SEARCH.PREF_THRESHOLD, Constants.THRESHOLD_DEFAULT), MIN_THRESHOLD, MAX_THRESHOLD);

        float tolerance = sp.getFloat(Constants.SEARCH.PREF_TOLERANCE, Constants.TOLERANCE_DEFAULT);
        tolerance = (float) Math.rint(Constants.MinMax(tolerance * 100, 1, 5000)) / 100.0f;
        tolerance /= 100.0;

        int library = sp.getInt(Constants.SEARCH.PREF_LIBRARY, 0);

        int channelCount = SpectrumData.instance.getChannelCount();
        int channels = Constants.MinMax(sp.getInt(Constants.SEARCH.PREF_CHANNELS, Constants.SEARCH_CHANNELS_DEFAULT), StrictMath.min(Constants.SEARCH_MIN_CHANNELS, channelCount), channelCount);
        int num_scale = StrictMath.max(1, channelCount / channels);
        int num_lines = channelCount / num_scale;
        long[] chan_raw = new long[num_lines];
        double[] variance = new double[num_lines];

        int window = calcWindow(context, SpectrumData.instance.foreground.getSpectrumCalibration(), resolution, num_scale);

        if (UIViewState.instance.backgroundSubtract) {
            double backgroundScale = SpectrumData.instance.foreground.getSpectrumTime() / SpectrumData.instance.background.getSpectrumTime();
            long[] data = SpectrumData.instance.foreground.getSpectrumCalibration().toChannel(SpectrumData.instance.background.getDataArray(), SpectrumData.instance.background.getSpectrumCalibration(), SpectrumData.instance.getLastCalibrationChannel());
            for (int i = 0; i < num_lines; i++) {
                for (int j = i * num_scale; j < (i + 1) * num_scale; j++) {
                    chan_raw[i] += StrictMath.max(0.0, SpectrumData.instance.foreground.getDataArray()[j] - data[j] * backgroundScale);
                    variance[i] += SpectrumData.instance.foreground.getDataArray()[j] + data[j] * backgroundScale * backgroundScale;
                }
            }
        } else {
            for (int i = 0; i < num_lines; i++) {
                for (int j = i * num_scale; j < (i + 1) * num_scale; j++) {
                    chan_raw[i] += SpectrumData.instance.foreground.getDataArray()[j];
                    variance[i] += SpectrumData.instance.foreground.getDataArray()[j];
                }
            }
        }
        //counts are Poisson distributed, empty channels still carry uncertainty
        for (int i = 0; i < num_lines; i++)
            variance[i] = StrictMath.max(variance[i], 1.0);
        //search for lines
        double[] coeffs = new double[2 * window + 1];
        double[] coeffsSquare = new double[2 * window + 1];
        int shift_window, old_window = 0;
        int channel_0 = StrictMath.max(1, SpectrumData.instance.foreground.getSpectrumCalibration().toChannel(REFERENCE_ENERGY) / num_scale);
        double[] peak_array = new double[num_lines];
        for (int i = 0; i < num_lines; i++) {
            shift_window = StrictMath.max((int) ((0.3 + 0.7 * StrictMath.sqrt(i / (double) channel_0)) * window), 4);
            shift_window = StrictMath.min(shift_window, MAX_WINDOW);
            if (old_window != shift_window) {
                old_window = shift_window;
                coeffs = calcSavitzkyGolayWeight(1, POLI_ORDER, shift_window);
                coeffsSquare = new double[coeffs.length];
                for (int j = 0; j < coeffs.length; j++)
                    coeffsSquare[j] = coeffs[j] * coeffs[j];
            }
            if (i < shift_window)
                continue;
            if (i >= (num_lines - shift_window))
                break;
            double slope = 0.0;
            double slopeVariance = 0.0;
            for (int j = i - shift_window; j <= i + shift_window; j++) {
                slope += chan_raw[j] * coeffs[j - (i - shift_window)];
                slopeVariance += variance[j] * coeffsSquare[j - (i - shift_window)];
            }
            //slope in units of its standard deviation
            peak_array[i] = slope / StrictMath.sqrt(slopeVariance);
        }
        double prev_Peak = 0.0;
        double cur_Peak;
        double max_Peak = 0.0;
        double peak_energy;
        long max_Channel = 0;
        AtomSpectraIsotopes.foundList.clear();
        for (int i = 1; i < num_lines; i++) {
            cur_Peak = peak_array[i];
            if (max_Peak < cur_Peak) {
                max_Peak = cur_Peak;
            }
            if (max_Channel < chan_raw[i]) {
                max_Channel = chan_raw[i];
            }
            if (prev_Peak > 0.0 && cur_Peak < 0.0 && max_Channel > 3 && max_Peak > threshold) {
                if (chan_raw[i] > chan_raw[i - 1]) {
                    peak_energy = SpectrumData.instance.foreground.getSpectrumCalibration().toEnergy(i * num_scale);
                } else {
                    peak_energy = SpectrumData.instance.foreground.getSpectrumCalibration().toEnergy((i - 1) * num_scale);
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
        SharedPreferences sp = PrefHelper.getASSharedPreferences(this);
        float resolution = sp.getFloat(Constants.SEARCH.PREF_RESOLUTION, Constants.RESOLUTION_DEFAULT);
        try {
            Number val = NumberFormat.getNumberInstance().parse(((EditText) findViewById(R.id.editResolution)).getText().toString());
            if (val != null)
                resolution = val.floatValue();
        } catch (Exception e) {
            ToastHelper.showErrorAndLog(this, LogTag.FIND_ISOTOPE_ACT, getString(R.string.find_error_resolution));
            return;
        }
        resolution = (float) Math.rint(Constants.MinMax(resolution, MIN_RESOLUTION, MAX_RESOLUTION) * 10) / 10.0f;
        ((EditText) findViewById(R.id.editResolution)).setText(String.format(Locale.getDefault(), "%.1f", resolution));

        float threshold = sp.getFloat(Constants.SEARCH.PREF_THRESHOLD, Constants.THRESHOLD_DEFAULT);
        try {
            Number val = NumberFormat.getNumberInstance().parse(((EditText) findViewById(R.id.editThreshold)).getText().toString());
            if (val != null)
                threshold = val.floatValue();
        } catch (Exception e) {
            ToastHelper.showErrorAndLog(this, LogTag.FIND_ISOTOPE_ACT, getString(R.string.find_error_threshold));
            return;
        }
        threshold = (float) Math.rint(Constants.MinMax(threshold, MIN_THRESHOLD, MAX_THRESHOLD) * 10) / 10.0f;
        ((EditText) findViewById(R.id.editThreshold)).setText(String.format(Locale.getDefault(), "%.1f", threshold));

        float tolerance = sp.getFloat(Constants.SEARCH.PREF_TOLERANCE, Constants.TOLERANCE_DEFAULT);
        try {
            Number val = NumberFormat.getNumberInstance().parse(((EditText) findViewById(R.id.editTolerance)).getText().toString());
            if (val != null)
                tolerance = val.floatValue();
        } catch (Exception e) {
            ToastHelper.showErrorAndLog(this, LogTag.FIND_ISOTOPE_ACT, getString(R.string.find_error_tolerance));
            return;
        }
        tolerance = (float) Math.rint(Constants.MinMax(tolerance * 100, 1, 5000)) / 100.0f;
        ((EditText) findViewById(R.id.editTolerance)).setText(String.format(Locale.getDefault(), "%.2f", tolerance));

        SharedPreferences.Editor spEditor = sp.edit();
        spEditor.putInt(Constants.SEARCH.PREF_CHANNELS, channels);
        spEditor.putInt(Constants.SEARCH.PREF_LIBRARY, library);
        spEditor.putFloat(Constants.SEARCH.PREF_RESOLUTION, resolution);
        spEditor.putFloat(Constants.SEARCH.PREF_THRESHOLD, threshold);
        spEditor.putFloat(Constants.SEARCH.PREF_TOLERANCE, tolerance);
        spEditor.commit();
        ((Button) findViewById(R.id.showIsotopes)).setText(getString(R.string.show_isotopes));
        AtomSpectraIsotopes.showFoundIsotopes = false;

        updateFoundIsotopes(this);
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
            for (Isotope isotope : AtomSpectraIsotopes.foundList) {
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
        if (parent.getId() == R.id.selectChannelsList) {
            channels = channelOptions[position];
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        //Nothing to do
    }
}
