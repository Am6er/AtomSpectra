package org.fe57.atomspectra;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import java.util.ArrayList;
import java.util.List;

/** Lets the user pick the device to record from, or work offline. */
public class AtomSpectraDeviceSelect extends ComponentActivity {
    public static final String EXTRA_START_RECORDING_AFTER = "start_recording_after";

    private DeviceScanner scanner;
    private AtomSpectraService service = null;
    private boolean serviceBound = false;
    private boolean receiverRegistered = false;
    private boolean startRecordingAfter = false;
    private boolean connecting = false;
    private OnBackPressedCallback backCallback;
    private List<DeviceDescriptor> devices = new ArrayList<>();

    @Override
    protected void attachBaseContext(Context newBase) {
        String lang = PrefHelper.getLocale(newBase);
        super.attachBaseContext(LocaleContextWrapper.wrap(newBase, lang));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_atom_spectra_device_select);
        startRecordingAfter = getIntent().getBooleanExtra(EXTRA_START_RECORDING_AFTER, false);
        scanner = new DeviceScanner(this);
        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);
        findViewById(R.id.cancelButton).setOnClickListener(v -> finish());
        findViewById(R.id.offlineButton).setOnClickListener(v -> {
            if (service != null) {
                service.selectOffline();
                finish();
            }
        });
        updateExitGate();
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
        // the service is started by the main activity; binding without BIND_AUTO_CREATE never starts a new one
        serviceBound = bindService(new Intent(this, AtomSpectraService.class), serviceConnection, 0);

        IntentFilter filter = new IntentFilter(Constants.ACTION.ACTION_DEVICE_SELECTED);
        filter.addAction(Constants.ACTION.ACTION_DEVICE_SELECTION_REQUIRED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(resultReceiver, filter, 0);
        } else {
            registerReceiver(resultReceiver, filter);
        }
        receiverRegistered = true;

        scanner.start(list -> {
            devices = list;
            renderDevices();
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
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
            connecting = false;
            showConnecting(false);
            updateExitGate();
            if (Constants.ACTION.ACTION_DEVICE_SELECTED.equals(intent.getAction())) {
                // a spectrum that needs the user's decision before recording is settled by the record button, not here
                if (startRecordingAfter && service != null && !AtomSpectraService.isConnectDecisionPending()
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
        boolean gate = AtomSpectraService.sessionState == AtomSpectraService.DeviceSessionState.UNSELECTED
                || AtomSpectraService.isSelectionPending();
        findViewById(R.id.cancelButton).setVisibility(gate ? View.GONE : View.VISIBLE);
        if (backCallback != null) {
            backCallback.setEnabled(gate);
        }
    }

    private void showConnecting(boolean show) {
        TextView status = findViewById(R.id.statusText);
        status.setText(R.string.device_select_connecting);
        status.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void renderDevices() {
        LinearLayout list = findViewById(R.id.deviceList);
        list.removeAllViews();

        if (devices.isEmpty()) {
            list.addView(createRow(getString(R.string.device_select_none), 0xFF808080, null));
            return;
        }
        for (DeviceDescriptor device : devices) {
            String text = device.displayName;
            if (!device.permissionGranted) {
                text += "\n" + getString(R.string.device_select_permission_needed);
            }
            list.addView(createRow(text, device.permissionGranted ? 0xFFFFFFFF : 0xFFB0B0B0, device));
        }
    }

    private TextView createRow(String text, int color, DeviceDescriptor device) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextSize(20);
        row.setTextColor(color);
        row.setPadding(16, 24, 16, 24);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (device != null) {
            row.setClickable(true);
            row.setOnClickListener(v -> onDeviceTapped(device));
        }
        return row;
    }

    private void onDeviceTapped(DeviceDescriptor device) {
        if (service == null) return;

        if (!device.permissionGranted) {
            requestPermission(device);
            return;
        }

        // picking again while a device is connecting replaces the pending choice; unsaved data on the screen is never discarded by a switch
        connect(device);
    }

    private void requestPermission(DeviceDescriptor device) {
        if (device.token instanceof UsbDevice) {
            scanner.requestUsbPermission((UsbDevice) device.token);
        }
    }

    private void connect(DeviceDescriptor device) {
        if (service == null) return;
        connecting = true;
        showConnecting(true);
        service.selectDevice(device);
        updateExitGate();
        // the service reports the pending selection on the input thread shortly after; hide Cancel immediately
        findViewById(R.id.cancelButton).setVisibility(View.GONE);
        backCallback.setEnabled(true);
    }
}
