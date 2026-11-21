package org.fe57.atomspectra;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * App-side settings of one device, kept in the app preferences under the device id.
 * Not used by any schema yet.
 */
final class SoftwareSettingsStore implements SettingsStore {
    private final SharedPreferences preferences;
    private final String prefix;
    private final SettingsSchema schema;

    SoftwareSettingsStore(@NonNull Context context, @NonNull String deviceId, @NonNull SettingsSchema schema) {
        this.preferences = PrefHelper.getASSharedPreferences(context);
        this.prefix = Constants.CONFIG.CONF_DEVICE_SETTINGS + deviceId + ":";
        this.schema = schema;
    }

    @Nullable
    @Override
    public Map<String, Object> values() {
        Map<String, Object> values = new HashMap<>();
        for (SettingDescriptor descriptor : schema.all()) {
            if (descriptor.scope() != SettingDescriptor.Scope.SOFTWARE) continue;
            Object value = read(descriptor);
            if (value != null) values.put(descriptor.key, value);
        }
        return values;
    }

    @Override
    public boolean write(Map<String, Object> changes) {
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, Object> change : changes.entrySet()) {
            SettingDescriptor descriptor = schema.find(change.getKey());
            if (descriptor == null || descriptor.scope() != SettingDescriptor.Scope.SOFTWARE) continue;
            String name = prefix + descriptor.key;
            Object value = change.getValue();
            switch (descriptor.type) {
                case BOOL:
                    editor.putBoolean(name, (Boolean) value);
                    break;
                case INT:
                case SELECT:
                    editor.putInt(name, (Integer) value);
                    break;
                case DECIMAL:
                    editor.putString(name, Double.toString((Double) value));
                    break;
                default:
                    editor.putString(name, String.valueOf(value));
                    break;
            }
        }
        editor.apply();
        return true;
    }

    @Nullable
    private Object read(SettingDescriptor descriptor) {
        String name = prefix + descriptor.key;
        if (!preferences.contains(name)) return descriptor.defaultValue();
        try {
            switch (descriptor.type) {
                case BOOL:
                    return preferences.getBoolean(name, false);
                case INT:
                case SELECT:
                    return preferences.getInt(name, 0);
                case DECIMAL:
                    return Double.parseDouble(preferences.getString(name, ""));
                default:
                    return preferences.getString(name, "");
            }
        } catch (ClassCastException | NumberFormatException e) {
            return descriptor.defaultValue();
        }
    }
}
