package org.fe57.atomspectra;

import android.app.ActionBar;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Edits the settings of the connected device, hardware and software ones together, with a single Save.
 * The values are read when the screen opens and again after a confirmed save.
 * <p>
 * TODO: move the schema-driven rows (SettingsAdapter, SettingHolder) out of this device-bound screen so the app preference screens can reuse them
 */
public class AtomSpectraDeviceSettings extends Activity {
    private static final int ROW_HEADER = 0;
    private static final int ROW_SETTING = 1;

    private static final class Row {
        final int titleRes;
        final SettingDescriptor descriptor;

        Row(int titleRes, SettingDescriptor descriptor) {
            this.titleRes = titleRes;
            this.descriptor = descriptor;
        }
    }

    private AtomSpectraService service;
    private boolean serviceBound = false;
    private boolean receiverRegistered = false;

    private SettingsSchema schema;
    private String deviceId;
    private final Map<SettingDescriptor.Scope, SettingsStore> stores = new EnumMap<>(SettingDescriptor.Scope.class);
    private final List<Row> rows = new ArrayList<>();
    // what the device and the app hold, what the user has now, and the part of it sent by the last Save
    private Map<String, Object> loaded = new HashMap<>();
    private final Map<String, Object> current = new HashMap<>();
    private final Map<String, Object> submitted = new HashMap<>();
    private final Map<String, String> rawText = new HashMap<>();
    private final Set<String> invalid = new HashSet<>();

    private SettingsAdapter adapter;
    private Button saveButton;

    @Override
    protected void attachBaseContext(Context newBase) {
        String lang = PrefHelper.getLocale(newBase);
        super.attachBaseContext(LocaleContextWrapper.wrap(newBase, lang));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_atom_spectra_device_settings);
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setDisplayShowHomeEnabled(true);
            bar.setDisplayHomeAsUpEnabled(true);
        }

        adapter = new SettingsAdapter();
        RecyclerView list = findViewById(R.id.settingsList);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        DividerItemDecoration divider = new DividerItemDecoration(this, DividerItemDecoration.VERTICAL);
        divider.setDrawable(ContextCompat.getDrawable(this, R.drawable.divider));
        list.addItemDecoration(divider);

        saveButton = findViewById(R.id.saveSettings);
        saveButton.setOnClickListener(view -> save(null));

        IntentFilter filter = new IntentFilter(Constants.ACTION.ACTION_DEVICE_SETTINGS_SAVED);
        filter.addAction(Constants.ACTION.ACTION_DEVICE_STATE_CHANGED);
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;

        // the service is started by the main activity; binding without BIND_AUTO_CREATE never starts a new one
        serviceBound = bindService(new Intent(this, AtomSpectraService.class), serviceConnection, 0);
        if (!serviceBound) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (receiverRegistered) {
            unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    @Override
    public void onBackPressed() {
        requestClose();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            requestClose();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((AtomSpectraService.LocalBinder) binder).getService();
            if (!service.hasDeviceSettings()) {
                finish();
                return;
            }
            load();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Constants.ACTION.ACTION_DEVICE_SETTINGS_SAVED.equals(intent.getAction())) {
                onSaved();
            } else if (isDeviceGone()) {
                finish();
            }
        }
    };

    // the device is disconnected or another one took its place
    private boolean isDeviceGone() {
        if (!AtomSpectraService.isDeviceConnected()) return true;
        return service != null && deviceId != null && !deviceId.equals(service.deviceSettingsDeviceId());
    }

    private void load() {
        schema = service.deviceSettingsSchema();
        deviceId = service.deviceSettingsDeviceId();
        stores.clear();
        stores.put(SettingDescriptor.Scope.HARDWARE, new HardwareSettingsStore(service));
        if (schema.hasScope(SettingDescriptor.Scope.SOFTWARE) && deviceId != null) {
            stores.put(SettingDescriptor.Scope.SOFTWARE, new SoftwareSettingsStore(this, deviceId, schema));
        }
        rows.clear();
        for (SettingsSchema.Category category : schema.categories) {
            rows.add(new Row(category.titleRes, null));
            for (SettingDescriptor descriptor : category.settings) rows.add(new Row(0, descriptor));
        }
        loaded = readValues();
        current.clear();
        current.putAll(loaded);
        rawText.clear();
        invalid.clear();
        for (SettingDescriptor descriptor : schema.all()) {
            rawText.put(descriptor.key, descriptor.format(loaded.get(descriptor.key)));
        }
        adapter.notifyDataSetChanged();
        updateSaveButton();
    }

    private Map<String, Object> readValues() {
        Map<String, Object> values = new HashMap<>();
        for (SettingsStore store : stores.values()) {
            Map<String, Object> part = store.values();
            if (part != null) values.putAll(part);
        }
        return values;
    }

    private Map<String, Object> changes() {
        Map<String, Object> changes = new HashMap<>();
        if (schema == null) return changes;
        for (SettingDescriptor descriptor : schema.all()) {
            Object value = current.get(descriptor.key);
            if (!Objects.equals(value, loaded.get(descriptor.key))) changes.put(descriptor.key, value);
        }
        return changes;
    }

    private void updateSaveButton() {
        saveButton.setEnabled(invalid.isEmpty() && !changes().isEmpty());
    }

    /**
     * Writes the changes, asking first when one of them wipes data on the device.
     *
     * @param afterSubmit runs once the write has been handed to the stores, null for none
     */
    private void save(Runnable afterSubmit) {
        final Map<String, Object> changes = changes();
        if (changes.isEmpty() || !invalid.isEmpty()) return;

        StringBuilder destructive = new StringBuilder();
        for (SettingDescriptor descriptor : schema.all()) {
            if (descriptor.isDestructive() && changes.containsKey(descriptor.key)) {
                if (destructive.length() > 0) destructive.append('\n');
                destructive.append(getString(descriptor.labelRes));
            }
        }
        if (destructive.length() == 0) {
            submit(changes, afterSubmit);
            return;
        }
        DialogHelper.showActionConfirmationDialog(this,
                getString(R.string.device_settings_destructive_title),
                getString(R.string.device_settings_destructive_text, destructive),
                getString(R.string.device_settings_save),
                getString(R.string.dialog_cancel_button),
                () -> submit(changes, afterSubmit));
    }

    private void submit(Map<String, Object> changes, Runnable afterSubmit) {
        AtomSpectraLog.action(this, LogTag.DEVICE_SETTINGS_ACT, "Save settings: " + changes);
        submitted.clear();
        submitted.putAll(changes);
        boolean pending = false;
        for (Map.Entry<SettingDescriptor.Scope, SettingsStore> entry : stores.entrySet()) {
            Map<String, Object> part = new HashMap<>();
            for (Map.Entry<String, Object> change : changes.entrySet()) {
                SettingDescriptor descriptor = schema.find(change.getKey());
                if (descriptor != null && descriptor.scope() == entry.getKey()) part.put(change.getKey(), change.getValue());
            }
            if (!part.isEmpty() && !entry.getValue().write(part)) pending = true;
        }
        if (!pending) onSaved();
        if (afterSubmit != null) afterSubmit.run();
    }

    // The saved values are now what the stores hold. Keys edited again since the save keep the user's edit.
    private void onSaved() {
        if (schema == null) return;
        Map<String, Object> fresh = readValues();
        for (String key : submitted.keySet()) {
            SettingDescriptor descriptor = schema.find(key);
            if (descriptor == null || !Objects.equals(current.get(key), submitted.get(key))) continue;
            current.put(key, fresh.get(key));
            rawText.put(key, descriptor.format(fresh.get(key)));
            invalid.remove(key);
        }
        submitted.clear();
        loaded = fresh;
        adapter.notifyDataSetChanged();
        updateSaveButton();
    }

    private void requestClose() {
        if (changes().isEmpty()) {
            finish();
            return;
        }
        List<DialogHelper.StackedAction> actions = new ArrayList<>();
        if (invalid.isEmpty()) {
            actions.add(new DialogHelper.StackedAction(getString(R.string.device_settings_save), () -> save(this::finish)));
        }
        actions.add(new DialogHelper.StackedAction(getString(R.string.device_settings_dont_save), this::finish));
        DialogHelper.showStackedActions(this,
                getString(R.string.device_settings_unsaved_title),
                getString(R.string.device_settings_unsaved_text),
                true,
                actions.toArray(new DialogHelper.StackedAction[0]));
    }

    private String errorText(SettingDescriptor descriptor) {
        if (descriptor.min() == -Double.MAX_VALUE && descriptor.max() == Double.MAX_VALUE) {
            return getString(R.string.device_settings_number);
        }
        return getString(R.string.device_settings_range, bound(descriptor, descriptor.min()), bound(descriptor, descriptor.max()));
    }

    private String bound(SettingDescriptor descriptor, double value) {
        if (value == -Double.MAX_VALUE) return "-∞";
        if (value == Double.MAX_VALUE) return "∞";
        return descriptor.type == SettingDescriptor.Type.INT ? String.valueOf((long) value) : descriptor.format(value);
    }

    private final class SettingsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return rows.get(position).descriptor == null ? ROW_HEADER : ROW_SETTING;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == ROW_HEADER) {
                return new RecyclerView.ViewHolder(inflater.inflate(R.layout.item_device_setting_header, parent, false)) {
                };
            }
            return new SettingHolder(inflater.inflate(R.layout.item_device_setting, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            if (row.descriptor == null) {
                ((TextView) holder.itemView).setText(row.titleRes);
            } else {
                ((SettingHolder) holder).bind(row.descriptor);
            }
        }
    }

    private final class SettingHolder extends RecyclerView.ViewHolder {
        private final TextView label;
        private final TextView expert;
        private final TextView help;
        private final TextView unit;
        private final CheckBox check;
        private final View valueRow;
        private final EditText edit;
        private final Spinner select;
        private TextWatcher watcher;

        SettingHolder(View view) {
            super(view);
            label = view.findViewById(R.id.settingLabel);
            expert = view.findViewById(R.id.settingExpert);
            help = view.findViewById(R.id.settingHelp);
            unit = view.findViewById(R.id.settingUnit);
            check = view.findViewById(R.id.settingBool);
            valueRow = view.findViewById(R.id.settingValueRow);
            edit = view.findViewById(R.id.settingEdit);
            select = view.findViewById(R.id.settingSelect);
        }

        void bind(SettingDescriptor descriptor) {
            final String key = descriptor.key;
            label.setText(descriptor.labelRes);
            expert.setVisibility(descriptor.level() == SettingDescriptor.Level.EXPERT ? View.VISIBLE : View.GONE);
            help.setVisibility(descriptor.helpRes() == 0 ? View.GONE : View.VISIBLE);
            if (descriptor.helpRes() != 0) help.setText(descriptor.helpRes());

            if (watcher != null) edit.removeTextChangedListener(watcher);
            watcher = null;
            check.setOnCheckedChangeListener(null);
            select.setOnItemSelectedListener(null);

            check.setVisibility(descriptor.type == SettingDescriptor.Type.BOOL ? View.VISIBLE : View.GONE);
            valueRow.setVisibility(descriptor.type == SettingDescriptor.Type.INT
                    || descriptor.type == SettingDescriptor.Type.DECIMAL ? View.VISIBLE : View.GONE);
            select.setVisibility(descriptor.type == SettingDescriptor.Type.SELECT ? View.VISIBLE : View.GONE);

            switch (descriptor.type) {
                case BOOL:
                    check.setChecked(Boolean.TRUE.equals(current.get(key)));
                    check.setOnCheckedChangeListener((button, checked) -> {
                        current.put(key, checked);
                        updateSaveButton();
                    });
                    break;
                case INT:
                case DECIMAL:
                    bindNumber(descriptor);
                    break;
                case SELECT:
                    bindSelect(descriptor);
                    break;
                default:
                    break;
            }
        }

        private void bindNumber(SettingDescriptor descriptor) {
            final String key = descriptor.key;
            int inputType = InputType.TYPE_CLASS_NUMBER;
            if (descriptor.min() < 0) inputType |= InputType.TYPE_NUMBER_FLAG_SIGNED;
            if (descriptor.type == SettingDescriptor.Type.DECIMAL) inputType |= InputType.TYPE_NUMBER_FLAG_DECIMAL;
            edit.setInputType(inputType);
            edit.setText(rawText.get(key));
            edit.setError(invalid.contains(key) ? errorText(descriptor) : null);
            unit.setVisibility(descriptor.unitRes() == 0 ? View.GONE : View.VISIBLE);
            if (descriptor.unitRes() != 0) unit.setText(descriptor.unitRes());
            watcher = new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable text) {
                    String typed = text.toString();
                    rawText.put(key, typed);
                    Object value = descriptor.parse(typed);
                    if (value == null) {
                        invalid.add(key);
                        edit.setError(errorText(descriptor));
                    } else {
                        invalid.remove(key);
                        current.put(key, value);
                        edit.setError(null);
                    }
                    updateSaveButton();
                }
            };
            edit.addTextChangedListener(watcher);
        }

        private void bindSelect(SettingDescriptor descriptor) {
            final String key = descriptor.key;
            int[] optionRes = descriptor.optionRes();
            List<String> options = new ArrayList<>();
            for (int res : optionRes) options.add(getString(res));
            select.setAdapter(new ArrayAdapter<String>(itemView.getContext(), android.R.layout.simple_spinner_item, options) {
                @NonNull
                @Override
                public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                    TextView view = (TextView) super.getView(position, convertView, parent);
                    view.setTextColor(0xFFFFFFFF);
                    return view;
                }
            });
            Object value = current.get(key);
            select.setSelection(value instanceof Integer ? (Integer) value : 0, false);
            select.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (Objects.equals(current.get(key), position)) return;
                    current.put(key, position);
                    updateSaveButton();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }
    }
}
