package org.fe57.atomspectra;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The Spectra Pro device settings: their schema and the snapshot built from what the source has read.
 */
final class SpectraProSettings {
    static final String[] CALIBRATION = {"cal0", "cal1", "cal2", "cal3", "cal4"};
    static final String NOISE = "nos";
    static final int NOISE_MAX = 4095;

    static final SettingsSchema SCHEMA = SettingsSchema.builder()
            .category(R.string.device_settings_calibration, calibration())
            .category(R.string.spectra_pro_settings_device,
                    SettingDescriptor.integer(NOISE, R.string.spectra_pro_setting_noise, 0, NOISE_MAX)
                            .expert().help(R.string.spectra_pro_setting_noise_help))
            .build();

    private SpectraProSettings() {
    }

    private static SettingDescriptor[] calibration() {
        int[] labels = {R.string.device_setting_cal0, R.string.device_setting_cal1, R.string.device_setting_cal2,
                R.string.device_setting_cal3, R.string.device_setting_cal4};
        SettingDescriptor[] descriptors = new SettingDescriptor[CALIBRATION.length];
        for (int index = 0; index < descriptors.length; index++) {
            descriptors[index] = SettingDescriptor.decimal(CALIBRATION[index], labels[index]);
        }
        descriptors[0].help(R.string.device_setting_cal_help);
        return descriptors;
    }

    static Map<String, Object> snapshot(double[] calibration, int noise) {
        Map<String, Object> values = new HashMap<>();
        for (int index = 0; index < CALIBRATION.length; index++) values.put(CALIBRATION[index], calibration[index]);
        values.put(NOISE, noise);
        return Collections.unmodifiableMap(values);
    }

    static int calibrationIndex(String key) {
        for (int index = 0; index < CALIBRATION.length; index++) {
            if (CALIBRATION[index].equals(key)) return index;
        }
        return -1;
    }
}
