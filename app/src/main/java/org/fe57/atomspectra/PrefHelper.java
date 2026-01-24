package org.fe57.atomspectra;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

public class PrefHelper {
    public static String configCalibrationChannel(int i) {
        return Constants.CONFIG.CONF_CAL_CHANNEL + i + ":";
    }

    public static String configCalibrationEnergy(int i) {
        return Constants.CONFIG.CONF_CAL_ENERGY + i + ":";
    }

    public static String configCalibrationCoefficient(int i) {
        return Constants.CONFIG.CONF_CAL_POLI_COEFFICIENT + i + ":";
    }

    public static String configSensTableValue(int i) {
        return Constants.CONFIG.CONF_SENS_TABLE_VALUE + i + ":";
    }

    public static String configSensTableEnergy(int i) {
        return Constants.CONFIG.CONF_SENS_TABLE_ENERGY + i + ":";
    }

    public static String getLocale(Context context) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, Context.MODE_PRIVATE);
        int r = sharedPreferences.getInt(Constants.CONFIG.CONF_LOCALE_ID, 0);
        r = r < Constants.LOCALES_ID.length ? r : (Constants.LOCALES_ID.length - 1);
        String lang = Locale.getDefault().getLanguage();
        if (r > 0) {
            lang = Constants.LOCALES_ID[r];
        }
        return lang;
    }

    public static TreeMap<Float, Double> getDefaultSensitivityTable() {
        TreeMap<Float, Double> sensitivityTable = new TreeMap<>();
        for (int i = 0; i < AtomSpectraService.EnergyBinsDefault.length; i++) {
            float energy = AtomSpectraService.EnergyBinsDefault[i];
            double sens = AtomSpectraService.EnergySensitivityDefault[i];

            if (energy != 0 && sens != 0) {
                sensitivityTable.put(energy, sens);
            }
        }

        return sensitivityTable;
    }

    public static TreeMap<Float, Double> getSensitivityTableOrDefault(Context context) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, Context.MODE_PRIVATE);
        int binsCount = sharedPreferences.getInt(Constants.CONFIG.CONF_SENS_TABLE_SIZE, 0);
        if (binsCount > 0) {
            TreeMap<Float, Double> sensitivityTable = new TreeMap<>();
            for (int i = 0; i < binsCount; i++) {
                float energy = sharedPreferences.getFloat(PrefHelper.configSensTableEnergy(i), 0.0f);
                double sens = Double.longBitsToDouble(sharedPreferences.getLong(PrefHelper.configSensTableValue(i), 0));

                sensitivityTable.put(energy, sens);
            }

            return sensitivityTable;
        } else {
            return getDefaultSensitivityTable();
        }
    }

    public static void setSensitivityTable(Context context, TreeMap<Float, Double> sensitivityTable) {
        SharedPreferences.Editor editor = context.getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, Context.MODE_PRIVATE).edit();
        List<Float> sortedEnergyList = new ArrayList<>(sensitivityTable.keySet());
        editor.putInt(Constants.CONFIG.CONF_SENS_TABLE_SIZE, sensitivityTable.size());
        for (int i = 0; i < sortedEnergyList.size(); i++) {
            float energy = sortedEnergyList.get(i);
            double sens = sensitivityTable.get(energy);
            editor.putFloat(PrefHelper.configSensTableEnergy(i), energy);
            editor.putLong(PrefHelper.configSensTableValue(i), Double.doubleToRawLongBits(sens));
        }

        editor.commit();
    }
}
