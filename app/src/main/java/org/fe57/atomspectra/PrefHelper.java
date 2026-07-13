package org.fe57.atomspectra;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

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

    public static SharedPreferences getASSharedPreferences(@NonNull Context context) {
        return context.getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, Context.MODE_PRIVATE);
    }

    public static String getLocale(@NonNull Context context) {
        SharedPreferences sharedPreferences = getASSharedPreferences(context);
        int r = sharedPreferences.getInt(Constants.CONFIG.CONF_LOCALE_ID, 0);
        r = r < Constants.LOCALES_ID.length ? r : (Constants.LOCALES_ID.length - 1);
        String lang = Locale.getDefault().getLanguage();
        if (r > 0) {
            lang = Constants.LOCALES_ID[r];
        }
        return lang;
    }

    // --- Sensitivity profiles -----------------------------------------------------------------

    /** Active sensitivity profile id, migrating a legacy install to a custom profile on first access. */
    public static String getActiveSensitivityProfileId(@NonNull Context context) {
        SharedPreferences sp = getASSharedPreferences(context);
        maybeMigrateLegacySensitivityPrefs(context);
        return sp.getString(Constants.CONFIG.CONF_SENSITIVITY_PROFILE, SensitivityProfile.ID_DEFAULT);
    }

    /** The active sensitivity profile (a copy - safe to mutate). */
    public static SensitivityProfile getActiveSensitivityProfile(@NonNull Context context) {
        String id = getActiveSensitivityProfileId(context);
        if (SensitivityProfile.ID_CUSTOM.equals(id)) {
            return getCustomSensitivityProfile(context);
        }
        return SensitivityProfile.builtInById(id);
    }

    public static void setActiveSensitivityProfile(@NonNull Context context, String id) {
        getASSharedPreferences(context).edit()
                .putString(Constants.CONFIG.CONF_SENSITIVITY_PROFILE, id)
                .apply();
    }

    /** All custom sensitivity profiles. Single slot for now; list-shaped so it can grow later. */
    public static List<SensitivityProfile> getCustomSensitivityProfiles(@NonNull Context context) {
        List<SensitivityProfile> profiles = new ArrayList<>();
        profiles.add(getCustomSensitivityProfile(context));
        return profiles;
    }

    /** The (single) custom sensitivity profile assembled from preferences. */
    public static SensitivityProfile getCustomSensitivityProfile(@NonNull Context context) {
        SharedPreferences sp = getASSharedPreferences(context);
        SensitivityProfile profile = new SensitivityProfile();
        profile.name = sp.getString(Constants.CONFIG.CONF_CUSTOM_PROFILE_NAME, "Custom");
        profile.readOnly = false;
        profile.setCompCurveFromMap(readSensitivityCurve(sp));
        profile.nonCompPsvPerCount = Double.longBitsToDouble(
                sp.getLong(Constants.CONFIG.CONF_NONCOMP_PSV, Double.doubleToRawLongBits(1.0)));
        profile.compFast = sp.getInt(Constants.CONFIG.CONF_SEARCH_COMP_FAST, Constants.SEARCH_FAST_DEFAULT);
        profile.compMedium = sp.getInt(Constants.CONFIG.CONF_SEARCH_COMP_MEDIUM, Constants.SEARCH_MEDIUM_DEFAULT);
        profile.compSlow = sp.getInt(Constants.CONFIG.CONF_SEARCH_COMP_SLOW, Constants.SEARCH_SLOW_DEFAULT);
        profile.nonCompFast = sp.getInt(Constants.CONFIG.CONF_SEARCH_NONCOMP_FAST, Constants.SEARCH_FAST_DEFAULT);
        profile.nonCompMedium = sp.getInt(Constants.CONFIG.CONF_SEARCH_NONCOMP_MEDIUM, Constants.SEARCH_MEDIUM_DEFAULT);
        profile.nonCompSlow = sp.getInt(Constants.CONFIG.CONF_SEARCH_NONCOMP_SLOW, Constants.SEARCH_SLOW_DEFAULT);
        return profile;
    }

    /** Persist the custom sensitivity profile (does not change the active profile). */
    public static void setCustomSensitivityProfile(@NonNull Context context, @NonNull SensitivityProfile profile) {
        SharedPreferences.Editor editor = getASSharedPreferences(context).edit();
        editor.putString(Constants.CONFIG.CONF_CUSTOM_PROFILE_NAME, profile.name);
        editor.putLong(Constants.CONFIG.CONF_NONCOMP_PSV, Double.doubleToRawLongBits(profile.nonCompPsvPerCount));
        editor.putInt(Constants.CONFIG.CONF_SEARCH_COMP_FAST, profile.compFast);
        editor.putInt(Constants.CONFIG.CONF_SEARCH_COMP_MEDIUM, profile.compMedium);
        editor.putInt(Constants.CONFIG.CONF_SEARCH_COMP_SLOW, profile.compSlow);
        editor.putInt(Constants.CONFIG.CONF_SEARCH_NONCOMP_FAST, profile.nonCompFast);
        editor.putInt(Constants.CONFIG.CONF_SEARCH_NONCOMP_MEDIUM, profile.nonCompMedium);
        editor.putInt(Constants.CONFIG.CONF_SEARCH_NONCOMP_SLOW, profile.nonCompSlow);
        writeSensitivityCurve(editor, profile.compCurveAsMap());
        editor.apply();
    }

    /** Clone any profile into the custom slot and make it active. */
    public static void cloneToCustomSensitivityProfile(@NonNull Context context, @NonNull SensitivityProfile source, String newName) {
        setCustomSensitivityProfile(context, source.editableCopy(newName));
        setActiveSensitivityProfile(context, SensitivityProfile.ID_CUSTOM);
    }

    private static TreeMap<Float, Double> readSensitivityCurve(@NonNull SharedPreferences sp) {
        TreeMap<Float, Double> curve = new TreeMap<>();
        int binsCount = sp.getInt(Constants.CONFIG.CONF_SENS_TABLE_SIZE, 0);
        for (int i = 0; i < binsCount; i++) {
            float energy = sp.getFloat(configSensTableEnergy(i), 0.0f);
            double psv = Double.longBitsToDouble(sp.getLong(configSensTableValue(i), 0));
            curve.put(energy, psv);
        }
        if (curve.isEmpty()) {
            curve.put(3000f, 1.0); // safety fallback: single placeholder band
        }
        return curve;
    }

    private static void writeSensitivityCurve(@NonNull SharedPreferences.Editor editor, @NonNull TreeMap<Float, Double> curve) {
        List<Float> edges = new ArrayList<>(curve.keySet());
        editor.putInt(Constants.CONFIG.CONF_SENS_TABLE_SIZE, edges.size());
        for (int i = 0; i < edges.size(); i++) {
            float energy = edges.get(i);
            editor.putFloat(configSensTableEnergy(i), energy);
            editor.putLong(configSensTableValue(i), Double.doubleToRawLongBits(curve.get(energy)));
        }
    }

    /**
     * One-time conversion of a pre-profile install into the custom sensitivity profile. Starts from the
     * nano-8 built-in and overrides only the values actually present in the old preferences:
     * comp pSv/count = rel / (SensGCompensated * PSV_PER_COUNT_TO_USV_H);
     * nonComp pSv/count = 1 / (SensG * PSV_PER_COUNT_TO_USV_H); old f/m/s copied into both triples.
     */
    private static void maybeMigrateLegacySensitivityPrefs(@NonNull Context context) {
        // legacy pref keys, kept only here for the one-time migration
        final String OLD_SENSG = "sensg";
        final String OLD_SENSG_COMPENSATED = "sensg_compensated";
        final String OLD_BACKGROUND = "backgcnt";
        final String OLD_SEARCH_FAST = "search_fast";
        final String OLD_SEARCH_MEDIUM = "search_medium";
        final String OLD_SEARCH_SLOW = "search_slow";

        SharedPreferences sp = getASSharedPreferences(context);
        if (sp.contains(Constants.CONFIG.CONF_SENSITIVITY_PROFILE)) {
            return; // already on the profile scheme
        }
        boolean hasLegacy = sp.contains(OLD_SENSG) || sp.contains(OLD_SENSG_COMPENSATED)
                || sp.contains(OLD_BACKGROUND) || sp.contains(OLD_SEARCH_FAST)
                || sp.contains(OLD_SEARCH_MEDIUM) || sp.contains(OLD_SEARCH_SLOW)
                || sp.getInt(Constants.CONFIG.CONF_SENS_TABLE_SIZE, 0) > 0;
        if (!hasLegacy) {
            return; // fresh install: defaults to the built-in profile
        }

        SensitivityProfile custom = SensitivityProfile.NANO8.editableCopy("Custom");

        if (sp.contains(OLD_SENSG)) {
            int sensG = readLegacyInt(sp, OLD_SENSG);
            custom.nonCompPsvPerCount = sensG > 0 ? 1.0 / (sensG * SensitivityProfile.PSV_PER_COUNT_TO_USV_H) : 0.0;
        }
        if (sp.contains(OLD_SEARCH_FAST)) {
            int v = readLegacyInt(sp, OLD_SEARCH_FAST);
            custom.compFast = v;
            custom.nonCompFast = v;
        }
        if (sp.contains(OLD_SEARCH_MEDIUM)) {
            int v = readLegacyInt(sp, OLD_SEARCH_MEDIUM);
            custom.compMedium = v;
            custom.nonCompMedium = v;
        }
        if (sp.contains(OLD_SEARCH_SLOW)) {
            int v = readLegacyInt(sp, OLD_SEARCH_SLOW);
            custom.compSlow = v;
            custom.nonCompSlow = v;
        }
        // convert the compensated curve only when both the stored table and its coefficient exist
        if (sp.getInt(Constants.CONFIG.CONF_SENS_TABLE_SIZE, 0) > 0 && sp.contains(OLD_SENSG_COMPENSATED)) {
            int sensGComp = readLegacyInt(sp, OLD_SENSG_COMPENSATED);
            if (sensGComp > 0) {
                double pSvPerCount = (1.0 / SensitivityProfile.PSV_PER_COUNT_TO_USV_H) / sensGComp;
                TreeMap<Float, Double> absoluteCurve = new TreeMap<>();
                int binsCount = sp.getInt(Constants.CONFIG.CONF_SENS_TABLE_SIZE, 0);
                for (int i = 0; i < binsCount; i++) {
                    float energy = sp.getFloat(configSensTableEnergy(i), 0.0f);
                    if (energy > 0) {
                        double rel = Double.longBitsToDouble(sp.getLong(configSensTableValue(i), 0));
                        absoluteCurve.put(energy, rel * pSvPerCount);
                    }
                }

                custom.setCompCurveFromMap(absoluteCurve);
            }
        }

        setCustomSensitivityProfile(context, custom); // overwrites CONF_SENS_TABLE_* with absolute values

        SharedPreferences.Editor editor = sp.edit();
        editor.remove(OLD_SENSG);
        editor.remove(OLD_SENSG_COMPENSATED);
        editor.remove(OLD_BACKGROUND);
        editor.remove(OLD_SEARCH_FAST);
        editor.remove(OLD_SEARCH_MEDIUM);
        editor.remove(OLD_SEARCH_SLOW);
        editor.putString(Constants.CONFIG.CONF_SENSITIVITY_PROFILE, SensitivityProfile.ID_CUSTOM);
        editor.apply();
    }

    private static int readLegacyInt(@NonNull SharedPreferences sp, String key) {
        try {
            return sp.getInt(key, 0);
        } catch (ClassCastException e) {
            return (int) sp.getFloat(key, 0);
        }
    }

    public static String getWorkingDir(@NonNull Context context, boolean notifyUserIfNotSet) {
        SharedPreferences sharedPreferences = getASSharedPreferences(context);
        if (sharedPreferences == null) {
            ToastHelper.showToast(context, "ERROR: Unable to get working dir, sharedPreferences instance is null.");
            return null;
        }

        String workingDir = sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null);
        if (workingDir == null && notifyUserIfNotSet) {
            ToastHelper.showToast(context, context.getString(R.string.error_working_dir_not_set));
        }

        return workingDir;
    }
}
