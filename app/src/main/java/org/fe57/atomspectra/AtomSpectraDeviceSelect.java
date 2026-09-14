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

import java.util.ArrayList;
import java.util.List;

/** Lets the user pick the device to record from, or work offline. Changes nothing until a device is picked. */
public class AtomSpectraDeviceSelect extends ComponentActivity {
    public static final String EXTRA_START_RECORDING_AFTER = "start_recording_after";

    private DeviceScanner scanner;
    private AtomSpectraService service = null;
    private boolean serviceBound = false;
    private boolean receiverRegistered = false;
    private boolean startRecordingAfter = false;
    private boolean connecting = false;
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
        findViewById(R.id.cancelButton).setOnClickListener(v -> finish());
        findViewById(R.id.offlineButton).setOnClickListener(v -> {
            if (service != null && !connecting) {
                service.selectOffline();
                finish();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        // the service is started by the main activity; binding without BIND_AUTO_CREATE never starts a new one
        serviceBound = bindService(new Intent(this, AtomSpectraService.class), serviceConnection, 0);

        IntentFilter filter = new IntentFilter(Constants.ACTION.ACTION_DEVICE_SELECTION_RESULT);
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
            if (intent.getBooleanExtra(Constants.ACTION_PARAMETERS.SELECTION_SUCCESS, false)) {
                if (startRecordingAfter) {
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

    private void showConnecting(boolean show) {
        TextView status = findViewById(R.id.statusText);
        status.setText(R.string.device_select_connecting);
        status.setVisibility(show ? View.VISIBLE : View.GONE);
        findViewById(R.id.offlineButton).setEnabled(!show);
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
        if (connecting || service == null) return;

        if (!device.permissionGranted) {
            requestPermission(device);
            return;
        }

        if (SpectrumData.instance.foreground.isChanged()) {
            DialogHelper.showActionConfirmationDialog(this, getString(R.string.device_switch_discard_text), () -> connect(device));
        } else {
            connect(device);
        }
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
    }
}
