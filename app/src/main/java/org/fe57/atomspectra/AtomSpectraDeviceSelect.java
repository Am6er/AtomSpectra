package org.fe57.atomspectra;

import android.app.ActionBar;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.bluetooth.BluetoothAdapter;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets the user pick the device to record from, or work offline.
 */
public class AtomSpectraDeviceSelect extends ComponentActivity {
    public static final String EXTRA_START_RECORDING_AFTER = "start_recording_after";

    private static final Object OFFLINE_TAG = new Object();

    private DeviceScanner scanner;
    private AtomSpectraService service = null;
    private boolean serviceBound = false;
    private boolean receiverRegistered = false;
    private boolean startRecordingAfter = false;
    private boolean connecting = false;
    private int selectedType = SpectrumSource.TYPE_NONE;
    private String selectedIdentity;
    private String selectedName;
    private DeviceScanner.BluetoothState bluetoothState = DeviceScanner.BluetoothState.UNSUPPORTED;
    private OnBackPressedCallback backCallback;
    private List<DeviceDescriptor> devices = new ArrayList<>();
    private AppPermissions permissions;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private long statusStartedAt;
    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            updateStatusText();
            statusHandler.postDelayed(this, 1000);
        }
    };
    private final ActivityResultLauncher<Intent> bluetoothEnable = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (scanner != null) scanner.restartBluetoothScan();
            });

    @Override
    protected void attachBaseContext(Context newBase) {
        String lang = PrefHelper.getLocale(newBase);
        super.attachBaseContext(LocaleContextWrapper.wrap(newBase, lang));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_atom_spectra_device_select);
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setDisplayShowHomeEnabled(true);
            bar.setDisplayHomeAsUpEnabled(true);
        }
        CheckBox rememberChoice = findViewById(R.id.rememberChoice);
        rememberChoice.setChecked(PrefHelper.shouldRememberDeviceChoice(this));
        rememberChoice.setOnCheckedChangeListener((button, checked) -> {
            PrefHelper.setRememberDeviceChoice(this, checked);
            if (scanner != null) scanner.refresh();
        });
        startRecordingAfter = getIntent().getBooleanExtra(EXTRA_START_RECORDING_AFTER, false);
        scanner = new DeviceScanner(this);
        permissions = new AppPermissions(this, null);
        if (savedInstanceState != null) {
            selectedType = savedInstanceState.getInt("selectedType", SpectrumSource.TYPE_NONE);
            selectedIdentity = savedInstanceState.getString("selectedIdentity");
            selectedName = savedInstanceState.getString("selectedName");
            connecting = savedInstanceState.getBoolean("connecting");
            statusStartedAt = savedInstanceState.getLong("statusStartedAt", 0);
        }
        showConnecting(connecting);
        findViewById(R.id.bluetoothAction).setOnClickListener(view -> onBluetoothAction());
        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);
        updateExitGate();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            if (backCallback == null || !backCallback.isEnabled()) {
                finish();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_START_RECORDING_AFTER, false)) {
            startRecordingAfter = true;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        restoreSelection();
        // the service is started by the main activity; binding without BIND_AUTO_CREATE never starts a new one
        serviceBound = bindService(new Intent(this, AtomSpectraService.class), serviceConnection, 0);

        IntentFilter filter = new IntentFilter(Constants.ACTION.ACTION_DEVICE_SELECTED);
        filter.addAction(Constants.ACTION.ACTION_DEVICE_SELECTION_REQUIRED);
        filter.addAction(Constants.ACTION.ACTION_DEVICE_STATE_CHANGED);
        ContextCompat.registerReceiver(this, resultReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;

        scanner.start(new DeviceScanner.Listener() {
            @Override
            public void onDevicesChanged(List<DeviceDescriptor> list) {
                devices = list;
                renderDevices();
            }

            @Override
            public void onBluetoothStateChanged(DeviceScanner.BluetoothState state) {
                updateBluetoothState(state);
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("selectedType", selectedType);
        state.putString("selectedIdentity", selectedIdentity);
        state.putString("selectedName", selectedName);
        state.putBoolean("connecting", connecting);
        state.putLong("statusStartedAt", statusStartedAt);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopStatusTicker();
        scanner.stop();
        if (receiverRegistered) {
            unregisterReceiver(resultReceiver);
            receiverRegistered = false;
        }
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
            service = null;
        }
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((AtomSpectraService.LocalBinder) binder).getService();
            restoreSelection();
            renderDevices();
            updateExitGate();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Constants.ACTION.ACTION_DEVICE_STATE_CHANGED.equals(intent.getAction())) {
                restoreSelection();
                renderDevices();
                updateExitGate();
                return;
            }
            connecting = false;
            restoreSelection();
            updateExitGate();
            if (Constants.ACTION.ACTION_DEVICE_SELECTED.equals(intent.getAction())) {
                // a spectrum that needs the user's decision before recording is settled by the record button, not here
                if (startRecordingAfter && service != null && !AtomSpectraService.isRecording()
                        && AtomSpectraService.isDeviceConnected() && !AtomSpectraService.isConnectDecisionPending()
                        && service.startDecision() == AtomSpectraService.START_FREE) {
                    sendBroadcast(new Intent(Constants.ACTION.ACTION_START_RECORDING).setPackage(Constants.PACKAGE_NAME));
                }
                finish();
            } else {
                String error = intent.getStringExtra(Constants.ACTION_PARAMETERS.SELECTION_ERROR_TEXT);
                if (error != null) {
                    Toast.makeText(AtomSpectraDeviceSelect.this, error, Toast.LENGTH_LONG).show();
                }
                renderDevices();
            }
        }
    };

    // nothing is chosen, or a chosen device is still connecting: the screen cannot be left
    private void updateExitGate() {
        boolean gate = connecting || AtomSpectraService.sessionState == AtomSpectraService.DeviceSessionState.UNSELECTED
                || AtomSpectraService.isSelectionPending();
        if (backCallback != null) {
            backCallback.setEnabled(gate);
        }
    }

    private void showConnecting(boolean show) {
        boolean suspended = AtomSpectraService.isRecordingSuspended;
        boolean visible = show || suspended;
        TextView status = findViewById(R.id.statusText);
        status.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            stopStatusTicker();
            statusStartedAt = 0;
            return;
        }
        AtomSpectraService.DeviceState deviceState = AtomSpectraService.deviceState();
        boolean timed = !(suspended && deviceState == AtomSpectraService.DeviceState.ERROR);
        if (timed) {
            if (statusStartedAt == 0) statusStartedAt = SystemClock.elapsedRealtime();
            updateStatusText();
            statusHandler.removeCallbacks(statusTicker);
            statusHandler.postDelayed(statusTicker, 1000);
        } else {
            stopStatusTicker();
            status.setText(R.string.device_error_notification);
        }
    }

    private void updateStatusText() {
        TextView status = findViewById(R.id.statusText);
        if (status.getVisibility() != View.VISIBLE) return;
        boolean suspended = AtomSpectraService.isRecordingSuspended;
        AtomSpectraService.DeviceState deviceState = AtomSpectraService.deviceState();
        if (suspended && deviceState == AtomSpectraService.DeviceState.ERROR) {
            status.setText(R.string.device_error_notification);
            return;
        }
        int seconds = statusStartedAt == 0 ? 0
                : (int) ((SystemClock.elapsedRealtime() - statusStartedAt) / 1000);
        boolean waiting = suspended && deviceState != AtomSpectraService.DeviceState.BUSY;
        status.setText(getString(waiting ? R.string.device_select_waiting_timed
                : R.string.device_select_connecting_timed, seconds));
    }

    private void stopStatusTicker() {
        statusHandler.removeCallbacks(statusTicker);
    }

    private void restoreSelection() {
        AtomSpectraService.LockedDevice selected = AtomSpectraService.selectedDevice();
        selectedType = selected == null ? SpectrumSource.TYPE_NONE : selected.type;
        selectedIdentity = selected == null ? null : selected.identity;
        selectedName = selected == null ? null : selected.name;
        connecting = AtomSpectraService.isSelectionPending();
        showConnecting(connecting);
    }

    private void renderDevices() {
        LinearLayout list = findViewById(R.id.deviceList);
        List<DeviceDescriptor> visibleDevices = new ArrayList<>(devices);
        if (selectedIdentity != null) {
            boolean found = false;
            for (DeviceDescriptor device : visibleDevices) {
                if (device.matches(selectedType, selectedIdentity)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                boolean permissionGranted = selectedType == SpectrumSource.TYPE_AUDIO
                        ? AppPermissions.isMicGranted(this)
                        : selectedType != SpectrumSource.TYPE_BLUZ || AppPermissions.isBluetoothGranted(this);
                visibleDevices.add(new DeviceDescriptor(selectedType, selectedIdentity, selectedName,
                        permissionGranted, null, false));
            }
        }
        list.removeAllViews();
        if (visibleDevices.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.device_select_none);
            empty.setTextColor(0xFF808080);
            empty.setTextSize(16);
            empty.setPadding(dp(16), dp(12), dp(16), dp(12));
            empty.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            list.addView(empty);
        } else {
            for (DeviceDescriptor device : visibleDevices) {
                View row = createRow();
                updateRow(row, device);
                list.addView(row);
            }
        }
        list.addView(createOfflineRow());
    }

    private View createRow() {
        return LayoutInflater.from(this).inflate(R.layout.item_device_select_row, null);
    }

    private View createOfflineRow() {
        View row = createRow();
        boolean selected = AtomSpectraService.sessionState == AtomSpectraService.DeviceSessionState.OFFLINE;
        ImageView icon = row.findViewById(R.id.deviceIcon);
        TextView title = row.findViewById(R.id.deviceTitle);
        TextView subtitle = row.findViewById(R.id.deviceSubtitle);
        ImageView check = row.findViewById(R.id.deviceCheck);
        icon.setImageResource(R.drawable.input_none);
        title.setText(R.string.device_select_offline);
        title.setTextColor(0xFFFFFFFF);
        subtitle.setVisibility(View.GONE);
        check.setVisibility(selected ? View.VISIBLE : View.GONE);
        row.setTag(OFFLINE_TAG);
        row.setBackgroundColor(selected ? 0x70505050 : 0x00000000);
        row.setContentDescription(selected
                ? getString(R.string.device_select_selected_description, getString(R.string.device_select_offline))
                : getString(R.string.device_select_offline));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(view -> {
            if (service == null) return;
            service.selectOffline();
            finish();
        });
        return row;
    }

    private void updateRow(View row, DeviceDescriptor device) {
        boolean selected = device.matches(selectedType, selectedIdentity);
        AtomSpectraService.LockedDevice locked = AtomSpectraService.selectedDevice();
        boolean available = device.available || (locked != null && device.matches(locked.type, locked.identity)
                && AtomSpectraService.isDeviceConnected());
        String titleText = rowTitle(device);
        StringBuilder subtitle = new StringBuilder();
        if (!available) subtitle.append(getString(R.string.device_select_unavailable));
        if (!device.permissionGranted) {
            if (subtitle.length() > 0) subtitle.append('\n');
            subtitle.append(getString(R.string.device_select_permission_needed));
        }

        ImageView icon = row.findViewById(R.id.deviceIcon);
        TextView title = row.findViewById(R.id.deviceTitle);
        TextView subtitleView = row.findViewById(R.id.deviceSubtitle);
        ImageView check = row.findViewById(R.id.deviceCheck);
        icon.setImageResource(typeIcon(device.type));
        title.setText(titleText);
        title.setTextColor(available && device.permissionGranted ? 0xFFFFFFFF : 0xFFB0B0B0);
        if (subtitle.length() > 0) {
            subtitleView.setText(subtitle);
            subtitleView.setVisibility(View.VISIBLE);
        } else {
            subtitleView.setVisibility(View.GONE);
        }
        check.setVisibility(selected ? View.VISIBLE : View.GONE);
        row.setTag(device);
        row.setBackgroundColor(selected ? 0x70505050 : 0x00000000);
        String description = subtitle.length() > 0 ? titleText + "\n" + subtitle : titleText;
        row.setContentDescription(selected
                ? getString(R.string.device_select_selected_description, description) : description);
        row.setEnabled(available || selected || (device.type == SpectrumSource.TYPE_BLUZ
                && bluetoothState != DeviceScanner.BluetoothState.UNSUPPORTED)
                || (device.type == SpectrumSource.TYPE_AUDIO && !device.permissionGranted));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(view -> onDeviceTapped(device));
    }

    private String rowTitle(DeviceDescriptor device) {
        if (device.type == SpectrumSource.TYPE_AUDIO) {
            int colon = device.displayName.indexOf(": ");
            if (colon >= 0) return device.displayName.substring(colon + 2);
        }
        return device.displayName;
    }

    private int typeIcon(int type) {
        switch (type) {
            case SpectrumSource.TYPE_AUDIO:
                return R.drawable.input_mic;
            case SpectrumSource.TYPE_SPECTRA_PRO:
                return R.drawable.input_usb;
            case SpectrumSource.TYPE_BLUZ:
                return R.drawable.input_bt;
            default:
                return R.drawable.input_none;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void onDeviceTapped(DeviceDescriptor device) {
        if (service == null) return;

        if (!device.permissionGranted) {
            requestPermission(device);
            return;
        }

        if (device.type == SpectrumSource.TYPE_BLUZ
                && bluetoothState != DeviceScanner.BluetoothState.READY
                && bluetoothState != DeviceScanner.BluetoothState.SCAN_FAILED) {
            if (bluetoothState != DeviceScanner.BluetoothState.UNSUPPORTED) onBluetoothAction();
            return;
        }
        AtomSpectraService.LockedDevice locked = AtomSpectraService.selectedDevice();
        boolean sameDevice = locked != null && device.matches(locked.type, locked.identity);
        if (!device.available && device.type != SpectrumSource.TYPE_BLUZ && !sameDevice) return;

        // picking again while a device is connecting replaces the pending choice; unsaved data on the screen is never discarded by a switch
        connect(device);
    }

    private void requestPermission(DeviceDescriptor device) {
        if (device.type == SpectrumSource.TYPE_AUDIO) {
            permissions.ensure(AppPermissions.Capability.MIC, () -> scanner.refresh(), () -> scanner.refresh());
            return;
        }
        if (device.type == SpectrumSource.TYPE_BLUZ) {
            permissions.ensure(AppPermissions.Capability.BLUETOOTH,
                    () -> scanner.restartBluetoothScan(), () -> scanner.refresh());
            return;
        }
        if (device.token instanceof UsbDevice) {
            scanner.requestUsbPermission((UsbDevice) device.token);
        }
    }

    private void updateBluetoothState(DeviceScanner.BluetoothState state) {
        bluetoothState = state;
        TextView warning = findViewById(R.id.bluetoothWarning);
        android.widget.Button action = findViewById(R.id.bluetoothAction);
        warning.setVisibility(state == DeviceScanner.BluetoothState.OFF
                || state == DeviceScanner.BluetoothState.LOCATION_DISABLED
                || state == DeviceScanner.BluetoothState.SCAN_FAILED ? View.VISIBLE : View.GONE);
        if (state == DeviceScanner.BluetoothState.OFF) warning.setText(R.string.bluetooth_off);
        else if (state == DeviceScanner.BluetoothState.LOCATION_DISABLED)
            warning.setText(R.string.bluetooth_location_off);
        else if (state == DeviceScanner.BluetoothState.SCAN_FAILED)
            warning.setText(R.string.bluetooth_scan_failed);
        action.setVisibility(state == DeviceScanner.BluetoothState.UNSUPPORTED ? View.GONE : View.VISIBLE);
        action.setText(state == DeviceScanner.BluetoothState.PERMISSION_REQUIRED ? R.string.bluetooth_allow
                : state == DeviceScanner.BluetoothState.OFF ? R.string.bluetooth_enable
                  : state == DeviceScanner.BluetoothState.LOCATION_DISABLED ? R.string.bluetooth_location_enable
                    : R.string.bluetooth_scan);
    }

    private void onBluetoothAction() {
        permissions.ensure(AppPermissions.Capability.BLUETOOTH, () -> {
            DeviceScanner.BluetoothState state = scanner.bluetoothState();
            if (state == DeviceScanner.BluetoothState.OFF) {
                bluetoothEnable.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } else if (state == DeviceScanner.BluetoothState.LOCATION_DISABLED) {
                bluetoothEnable.launch(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } else {
                scanner.restartBluetoothScan();
            }
        }, () -> scanner.refresh());
    }

    private void connect(DeviceDescriptor device) {
        if (service == null) return;
        AtomSpectraService.LockedDevice locked = AtomSpectraService.selectedDevice();
        boolean sameDevice = locked != null && device.matches(locked.type, locked.identity);
        if (sameDevice && AtomSpectraService.isRecording()) startRecordingAfter = false;
        selectedType = device.type;
        selectedIdentity = device.identity;
        selectedName = device.displayName;
        connecting = !sameDevice || AtomSpectraService.isSelectionPending();
        if (!sameDevice) statusStartedAt = 0;
        showConnecting(connecting);
        renderDevices();
        service.selectDevice(device);
        updateExitGate();
    }
}
