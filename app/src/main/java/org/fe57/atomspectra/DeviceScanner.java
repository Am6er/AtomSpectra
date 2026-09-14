package org.fe57.atomspectra;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists the devices the user can select and keeps the list current while started.
 * Knows nothing about AtomSpectraService; its output is {@link DeviceDescriptor}s.
 */
final class DeviceScanner {
    interface Listener {
        void onDevicesChanged(List<DeviceDescriptor> devices);
    }

    private static final String ACTION_USB_PERMISSION_RESULT = "org.fe57.atomspectra.ACTION_SCANNER_USB_PERMISSION";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Listener listener = null;
    private BroadcastReceiver usbReceiver = null;
    private AudioDeviceCallback audioCallback = null;

    DeviceScanner(Context context) {
        this.context = context;
    }

    List<DeviceDescriptor> scan() {
        List<DeviceDescriptor> devices = new ArrayList<>();
        devices.addAll(this.scanAudio());
        devices.addAll(this.scanUsb());
        devices.addAll(this.scanBluetooth());
        return devices;
    }

    /** Delivers the current list at once and again on every change, until {@link #stop()}. */
    void start(Listener listener) {
        this.stop();
        this.listener = listener;
        this.registerUsbReceiver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.registerAudioCallback();
        }
        this.refresh();
    }

    void stop() {
        this.listener = null;
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

    /** Re-scan and notify the listener, e.g. after a permission was granted. */
    void refresh() {
        final Listener current = this.listener;
        if (current != null) {
            current.onDevicesChanged(this.scan());
        }
    }

    /** Asks the system for permission to use the USB device; the list is refreshed when the user answers. */
    void requestUsbPermission(UsbDevice device) {
        UsbManager manager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        if (manager == null) return;

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
                    this.context.getString(R.string.device_default_microphone), true,
                    true, AtomSpectraAudioSource.HIST_POINTS, null));
            return result;
        }

        AudioManager manager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return result;
        AudioDeviceInfo[] devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        if (devices == null) return result;

        for (AudioDeviceInfo device : devices) {
            if (!isSupportedAudioInput(device)) continue;
            int typeIndex = Constants.MinMax(device.getType(), 0, AtomSpectraService.audioDeviceNames.length - 1);
            String name = AtomSpectraService.audioDeviceNames[typeIndex] + ": " + device.getProductName();
            result.add(new DeviceDescriptor(SpectrumSource.TYPE_AUDIO, DeviceIdentity.audio(device), name, true,
                    true, AtomSpectraAudioSource.HIST_POINTS, device));
        }
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
                    false, AtomSpectraProSource.HIST_POINTS, device));
        }
        return result;
    }

    // seam for a future wireless producer
    private List<DeviceDescriptor> scanBluetooth() {
        return new ArrayList<>();
    }

    private void registerUsbReceiver() {
        this.usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                DeviceScanner.this.refresh();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION_RESULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.context.registerReceiver(this.usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.context.registerReceiver(this.usbReceiver, filter, 0);
        } else {
            this.context.registerReceiver(this.usbReceiver, filter);
        }
    }

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
