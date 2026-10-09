package org.fe57.atomspectra;

import android.app.PendingIntent;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lists the devices the user can select and keeps the list current while started.
 * Knows nothing about AtomSpectraService; its output is {@link DeviceDescriptor}s.
 */
final class DeviceScanner {
    interface Listener {
        void onDevicesChanged(List<DeviceDescriptor> devices);

        default void onBluetoothStateChanged(BluetoothState state) {
        }
    }

    enum BluetoothState {UNSUPPORTED, PERMISSION_REQUIRED, OFF, LOCATION_DISABLED, READY, SCAN_FAILED}

    private static final String ACTION_USB_PERMISSION_RESULT = "org.fe57.atomspectra.ACTION_SCANNER_USB_PERMISSION";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Listener listener = null;
    private BroadcastReceiver usbReceiver = null;
    private AudioDeviceCallback audioCallback = null;
    private BroadcastReceiver bluetoothReceiver;
    private ScanCallback bluetoothScan;
    private BluetoothAdapter bluetoothAdapter;
    private boolean scanFailed;
    private boolean bluetoothScanPaused;
    private final Map<String, DeviceDescriptor> bluetoothDevices = new LinkedHashMap<>();

    DeviceScanner(Context context) {
        this.context = context;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
    }

    List<DeviceDescriptor> scan() {
        List<DeviceDescriptor> devices = new ArrayList<>();
        devices.addAll(this.scanAudio());
        devices.addAll(this.scanUsb());
        devices.addAll(this.scanBluetooth());
        DeviceChoice choice = PrefHelper.getDeviceChoice(context);
        if (choice.mode == DeviceChoice.MODE_DEVICE) {
            boolean found = false;
            for (DeviceDescriptor device : devices) {
                if (device.matches(choice.type, choice.identity)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                boolean permissionGranted = choice.type == SpectrumSource.TYPE_AUDIO
                        ? AppPermissions.isMicGranted(context)
                        : choice.type != SpectrumSource.TYPE_BLUZ || AppPermissions.isBluetoothGranted(context);
                devices.add(new DeviceDescriptor(choice.type, choice.identity, choice.name,
                        permissionGranted, null, false));
            }
        }
        return devices;
    }

    /**
     * Delivers the current list at once and again on every change, until {@link #stop()}.
     */
    void start(Listener listener) {
        this.stop();
        this.listener = listener;
        this.registerUsbReceiver();
        this.registerBluetoothReceiver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.registerAudioCallback();
        }
        this.refresh();
    }

    void stop() {
        this.listener = null;
        stopBluetoothScan();
        bluetoothDevices.clear();
        scanFailed = false;
        if (bluetoothReceiver != null) {
            context.unregisterReceiver(bluetoothReceiver);
            bluetoothReceiver = null;
        }
        if (this.usbReceiver != null) {
            try {
                this.context.unregisterReceiver(this.usbReceiver);
            } catch (IllegalArgumentException ignore) {
                // already unregistered
            }
            this.usbReceiver = null;
        }
        if (this.audioCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioManager manager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
            if (manager != null) manager.unregisterAudioDeviceCallback(this.audioCallback);
            this.audioCallback = null;
        }
    }

    /**
     * Re-scan and notify the listener, e.g. after a permission was granted.
     */
    void refresh() {
        final Listener current = this.listener;
        if (current != null) {
            current.onDevicesChanged(this.scan());
        }
    }

    /**
     * Asks the system for permission to use the USB device; the list is refreshed when the user answers.
     */
    void requestUsbPermission(UsbDevice device) {
        UsbManager manager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        if (manager == null) return;

        AtomSpectraLog.action(this.context, LogTag.SCANNER, "Requesting USB permission: " + device.getDeviceName());
        final int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(this.context, 0,
                new Intent(ACTION_USB_PERMISSION_RESULT).setPackage(Constants.PACKAGE_NAME), flags);
        manager.requestPermission(device, pi);
    }

    private List<DeviceDescriptor> scanAudio() {
        List<DeviceDescriptor> result = new ArrayList<>();
        // without the microphone permission audio sources are not offered at all
        if (!AppPermissions.isMicGranted(this.context)) return result;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            result.add(new DeviceDescriptor(SpectrumSource.TYPE_AUDIO, DeviceIdentity.AUDIO_DEFAULT,
                    this.context.getString(R.string.device_default_microphone), true, null));
            return result;
        }

        AudioManager manager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return result;
        AudioDeviceInfo[] devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        if (devices == null) return result;

        Map<String, DeviceDescriptor> audioDevices = new LinkedHashMap<>();
        for (AudioDeviceInfo device : devices) {
            if (!isSupportedAudioInput(device)) continue;
            String identity = DeviceIdentity.audio(device);
            if (audioDevices.containsKey(identity)) continue;
            int typeIndex = Constants.MinMax(device.getType(), 0, AtomSpectraService.audioDeviceNames.length - 1);
            String name = AtomSpectraService.audioDeviceNames[typeIndex] + ": " + device.getProductName();
            audioDevices.put(identity, new DeviceDescriptor(SpectrumSource.TYPE_AUDIO, identity, name, true,
                    device));
        }
        result.addAll(audioDevices.values());
        return result;
    }

    private static boolean isSupportedAudioInput(AudioDeviceInfo device) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_AUX_LINE:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return true;
            default:
                return false;
        }
    }

    private List<DeviceDescriptor> scanUsb() {
        List<DeviceDescriptor> result = new ArrayList<>();
        UsbManager manager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        if (manager == null) return result;

        for (UsbDevice device : manager.getDeviceList().values()) {
            if (!AtomSpectraProSource.isSpectraPro(device)) continue;
            result.add(new DeviceDescriptor(SpectrumSource.TYPE_SPECTRA_PRO, DeviceIdentity.usb(device),
                    this.context.getString(R.string.device_spectra_pro), manager.hasPermission(device),
                    device));
        }
        return result;
    }

    @SuppressLint("MissingPermission")
    private List<DeviceDescriptor> scanBluetooth() {
        List<DeviceDescriptor> result = new ArrayList<>();
        BluetoothState state = bluetoothState();
        if (listener != null) listener.onBluetoothStateChanged(state);
        if (state != BluetoothState.READY && state != BluetoothState.SCAN_FAILED) {
            stopBluetoothScan();
            bluetoothDevices.clear();
            return result;
        }
        try {
            for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
                if ("BluZ".equals(device.getName()))
                    addBluetoothDevice(device, device.getName(), false);
            }
            if (listener != null && bluetoothScan == null && !scanFailed && !bluetoothScanPaused)
                startBluetoothScan();
            result.addAll(bluetoothDevices.values());
        } catch (SecurityException error) {
            AtomSpectraLog.warning(context, LogTag.SCANNER, "Bluetooth device list denied: " + error);
            stopBluetoothScan();
            if (listener != null)
                listener.onBluetoothStateChanged(BluetoothState.PERMISSION_REQUIRED);
        }
        return result;
    }

    @SuppressLint("MissingPermission")
    BluetoothState bluetoothState() {
        if (bluetoothAdapter == null || !context.getPackageManager()
                .hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)) {
            return BluetoothState.UNSUPPORTED;
        }
        if (!AppPermissions.isBluetoothGranted(context)) return BluetoothState.PERMISSION_REQUIRED;
        try {
            if (!bluetoothAdapter.isEnabled()) return BluetoothState.OFF;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                if (manager == null || (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        && !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))) {
                    return BluetoothState.LOCATION_DISABLED;
                }
            }
            return scanFailed ? BluetoothState.SCAN_FAILED : BluetoothState.READY;
        } catch (SecurityException error) {
            return BluetoothState.PERMISSION_REQUIRED;
        }
    }

    @SuppressLint("MissingPermission")
    private boolean addBluetoothDevice(BluetoothDevice device, String name, boolean observed) {
        String identity = DeviceIdentity.bluz(device.getAddress());
        String displayName = name + " (" + device.getAddress() + ")";
        DeviceDescriptor previous = bluetoothDevices.get(identity);
        boolean available = observed || (previous != null && previous.available);
        if (previous != null && displayName.equals(previous.displayName)
                && available == previous.available) return false;
        bluetoothDevices.put(identity, new DeviceDescriptor(SpectrumSource.TYPE_BLUZ, identity,
                displayName, true, device, available));
        return true;
    }

    @SuppressLint("MissingPermission")
    private void startBluetoothScan() {
        if (bluetoothAdapter.getBluetoothLeScanner() == null) return;
        ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                mainHandler.post(() -> {
                    if (listener == null || bluetoothScan != this) return;
                    try {
                        String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
                        if (name == null) name = result.getDevice().getName();
                        if (!"BluZ".equals(name)) return;
                        if (addBluetoothDevice(result.getDevice(), name, true)) refresh();
                    } catch (SecurityException error) {
                        refresh();
                    }
                });
            }

            @Override
            public void onScanFailed(int errorCode) {
                mainHandler.post(() -> {
                    if (bluetoothScan != this || listener == null) return;
                    AtomSpectraLog.warning(context, LogTag.SCANNER, "Bluetooth scan failed, error code " + errorCode);
                    stopBluetoothScan();
                    scanFailed = true;
                    refresh();
                });
            }
        };
        bluetoothScan = callback;
        try {
            bluetoothAdapter.getBluetoothLeScanner().startScan(
                    Collections.singletonList(new ScanFilter.Builder().setDeviceName("BluZ").build()),
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
        } catch (IllegalStateException error) {
            AtomSpectraLog.warning(context, LogTag.SCANNER, "Bluetooth scan start failed: " + error);
            stopBluetoothScan();
        }
    }

    @SuppressLint("MissingPermission")
    private void stopBluetoothScan() {
        ScanCallback previous = bluetoothScan;
        bluetoothScan = null;
        if (previous == null || bluetoothAdapter == null) return;
        try {
            if (bluetoothAdapter.getBluetoothLeScanner() != null)
                bluetoothAdapter.getBluetoothLeScanner().stopScan(previous);
        } catch (SecurityException | IllegalStateException error) {
            AtomSpectraLog.warning(context, LogTag.SCANNER, "Bluetooth scan stop failed: " + error);
        }
    }

    // the radio is left to a connection in progress; the list keeps what was found
    void pauseBluetoothScan() {
        if (bluetoothScanPaused) return;
        bluetoothScanPaused = true;
        stopBluetoothScan();
    }

    void resumeBluetoothScan() {
        if (!bluetoothScanPaused) return;
        bluetoothScanPaused = false;
        refresh();
    }

    void restartBluetoothScan() {
        stopBluetoothScan();
        bluetoothDevices.clear();
        scanFailed = false;
        refresh();
    }

    private void registerBluetoothReceiver() {
        bluetoothReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopBluetoothScan();
                bluetoothDevices.clear();
                scanFailed = false;
                refresh();
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
        ContextCompat.registerReceiver(context, bluetoothReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }

    private void registerUsbReceiver() {
        this.usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_USB_PERMISSION_RESULT.equals(intent.getAction())) {
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    if (granted) AtomSpectraLog.event(context, LogTag.SCANNER, "USB permission granted");
                    else AtomSpectraLog.warning(context, LogTag.SCANNER, "USB permission denied");
                }
                DeviceScanner.this.refresh();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION_RESULT);
        ContextCompat.registerReceiver(this.context, this.usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private void registerAudioCallback() {
        AudioManager manager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return;

        this.audioCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                DeviceScanner.this.refresh();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                DeviceScanner.this.refresh();
            }
        };
        manager.registerAudioDeviceCallback(this.audioCallback, this.mainHandler);
    }
}
