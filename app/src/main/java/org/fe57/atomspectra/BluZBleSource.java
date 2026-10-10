package org.fe57.atomspectra;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

@SuppressLint("MissingPermission")
final class BluZBleSource implements SpectrumSource {
    private static final UUID SERVICE = UUID.fromString("0000fe80-cc7a-482a-984a-7f2ed5b3e58f");
    private static final UUID NOTIFY = UUID.fromString("0000fe81-8e22-4541-9d4c-21edae82ed19");
    private static final UUID WRITE = UUID.fromString("0000fe82-8e22-4541-9d4c-21edae82ed19");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final long HANDSHAKE_MS = 30000;
    private static final long COMMAND_MS = 15000;
    private static final long ASSEMBLY_MS = 10000;
    // Silent recovery is abandoned after this long; the retry delay is fixed while it lasts.
    private static final long RECOVERY_MAX_WINDOW_MS = 60000;
    private static final long RECOVERY_RETRY_MS = 1000;
    private static final long MAX_RETRY_MS = 30000;
    private static final long SCAN_RESTART_MS = 120000;
    // A user-initiated connect gives up if the device is not found within this time.
    private static final long USER_SCAN_TIMEOUT_MS = 30000;
    
    private static String gattStatus(int status) {
        String name;
        switch (status) {
            case BluetoothGatt.GATT_SUCCESS:
                name = "SUCCESS";
                break;
            case BluetoothGatt.GATT_READ_NOT_PERMITTED:
                name = "READ_NOT_PERMITTED";
                break;
            case BluetoothGatt.GATT_WRITE_NOT_PERMITTED:
                name = "WRITE_NOT_PERMITTED";
                break;
            case BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION:
                name = "INSUFFICIENT_AUTHENTICATION";
                break;
            case BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION:
                name = "INSUFFICIENT_ENCRYPTION";
                break;
            case BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH:
                name = "INVALID_ATTRIBUTE_LENGTH";
                break;
            case BluetoothGatt.GATT_CONNECTION_CONGESTED:
                name = "CONNECTION_CONGESTED";
                break;
            case BluetoothGatt.GATT_FAILURE:
                name = "FAILURE";
                break;
            case 0x08:
                name = "CONN_TIMEOUT";
                break;
            case 0x13:
                name = "CONN_TERMINATE_PEER_USER";
                break;
            case 0x16:
                name = "CONN_TERMINATE_LOCAL_HOST";
                break;
            case 0x22:
                name = "CONN_LMP_TIMEOUT";
                break;
            case 0x3E:
                name = "CONN_FAIL_ESTABLISH";
                break;
            case 133:
                name = "GATT_ERROR";
                break;
            default:
                return String.valueOf(status);
        }
        return status + " (" + name + ")";
    }

    private static String phyName(int phy) {
        switch (phy) {
            case BluetoothDevice.PHY_LE_1M:
                return "1M";
            case BluetoothDevice.PHY_LE_2M:
                return "2M";
            case BluetoothDevice.PHY_LE_CODED:
                return "Coded";
            default:
                return String.valueOf(phy);
        }
    }

    private final Context context;
    private final Handler handler;
    private final String address;
    private final int instanceId = SourceInstanceId.next();
    private final BluZFrameDecoder decoder = new BluZFrameDecoder();
    private final ArrayDeque<PendingWrite> writes = new ArrayDeque<>();
    private volatile int status = STATUS_DISCONNECTED;
    private volatile SourceError lastError;
    private volatile double[] coefficients = new double[]{0, 3000.0 / 4096};
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private ScanCallback scanCallback;
    private BroadcastReceiver adapterReceiver;
    private boolean closed;
    private boolean terminal;
    private boolean ready;
    private boolean hasBeenReady;
    private boolean physicalConnection;
    private boolean subscribed;
    private boolean writing;
    private long retryDelay = 1000;
    private boolean userInitiated;
    private boolean recoveryActive;
    private String recoveryReason;
    private long recoveryStartedAt;
    private long scanStartedAt;
    private long connectStartedAt;
    private BluZFrameDecoder.Frame latest;
    private boolean stopOnReturn;
    private int toggleOp;
    private boolean desiredCollecting;
    private boolean toggleSent;
    private boolean resetPending;
    private boolean resetSent;
    private double resetTime;
    private long resetPulses;
    private byte[] latestSettings;
    private double[] pendingCalibration;
    private boolean calibrationPending;
    private boolean calibrationSent;
    private boolean resolutionAttempted;
    private boolean resolutionPending;
    private boolean resolutionSent;

    BluZBleSource(Context context, String identity, Handler inputHandler) {
        this.context = context.getApplicationContext();
        handler = inputHandler;
        address = identity != null && identity.startsWith("bluz:") ? identity.substring(5) : "";
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    private void dispatch(Runnable action) {
        if (Looper.myLooper() == handler.getLooper()) action.run();
        else handler.post(action);
    }

    @Override
    public void requestConnect(boolean userInitiated) {
        dispatch(() -> {
            if (closed) return;
            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                failHandshake(REASON_ERROR, "Invalid BluZ Bluetooth address");
                return;
            }
            if (ready && lastError != null) connectionLostNow("Retrying BluZ connection");
            else if (ready || physicalConnection || recoveryActive) return;
            this.userInitiated = userInitiated;
            terminal = false;
            lastError = null;
            if (!AppPermissions.isBluetoothGranted(context)) {
                failHandshake(REASON_PERMISSION, "Bluetooth permission is required");
                return;
            }
            if (adapter == null) {
                failHandshake(REASON_ERROR, "Bluetooth is not supported on this device");
                return;
            }
            if (adapterReceiver == null) {
                adapterReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        dispatch(() -> {
                            if (closed || terminal) return;
                            try {
                                if (!adapter.isEnabled()) connectionLostNow("Bluetooth is off");
                                else if (recoveryActive) return;
                                else waitForDevice();
                            } catch (SecurityException error) {
                                permissionLost();
                            }
                        });
                    }
                };
                ContextCompat.registerReceiver(context, adapterReceiver,
                        new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                        ContextCompat.RECEIVER_EXPORTED);
            }
            handler.removeCallbacks(scanTimeout);
            if (userInitiated && !hasBeenReady) handler.postDelayed(scanTimeout, USER_SCAN_TIMEOUT_MS);
            waitForDevice();
        });
    }

    private final Runnable retry = this::waitForDevice;

    private final Runnable scanTimeout = () -> {
        if (closed || terminal || ready || hasBeenReady || physicalConnection || recoveryActive || gatt != null) return;
        failHandshake(REASON_TIMEOUT, "BluZ not found");
    };

    private void waitForDevice() {
        handler.removeCallbacks(retry);
        if (closed || terminal || gatt != null || scanCallback != null) return;
        if (!AppPermissions.isBluetoothGranted(context)) {
            permissionLost();
            return;
        }
        try {
            if (adapter == null || !adapter.isEnabled()) return;
            if (adapter.getBluetoothLeScanner() == null) {
                scheduleRetry();
                return;
            }
            ScanCallback callback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    dispatch(() -> {
                        if (closed || terminal || scanCallback != this) return;
                        debug("Scan hit");
                        handler.removeCallbacks(scanTimeout);
                        stopScan();
                        connect(result.getDevice());
                    });
                }

                @Override
                public void onScanFailed(int errorCode) {
                    dispatch(() -> {
                        if (scanCallback != this) return;
                        stopScan();
                        if (recoveryActive && errorCode == SCAN_FAILED_FEATURE_UNSUPPORTED)
                            connectionLostNow("BLE scanning is not supported");
                        else scheduleRetry();
                    });
                }
            };
            scanCallback = callback;
            scanStartedAt = SystemClock.elapsedRealtime();
            debug("Scan started");
            adapter.getBluetoothLeScanner().startScan(
                    Collections.singletonList(new ScanFilter.Builder().setDeviceAddress(address).build()),
                    new ScanSettings.Builder().setScanMode(recoveryActive || userInitiated
                            ? ScanSettings.SCAN_MODE_LOW_LATENCY : ScanSettings.SCAN_MODE_LOW_POWER).build(), callback);
            handler.postDelayed(scanRestart, SCAN_RESTART_MS);
        } catch (SecurityException error) {
            permissionLost();
        } catch (IllegalStateException error) {
            stopScan();
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (closed || terminal) return;
        handler.removeCallbacks(retry);
        if (recoveryActive) {
            handler.postDelayed(retry, RECOVERY_RETRY_MS);
            return;
        }
        handler.postDelayed(retry, retryDelay);
        retryDelay = Math.min(MAX_RETRY_MS, retryDelay * 2);
    }

    // A scan that produced no events for SCAN_RESTART_MS is restarted.
    private final Runnable scanRestart = () -> {
        if (scanCallback == null) return;
        debug("Scan restart, no scan events for " + (SystemClock.elapsedRealtime() - scanStartedAt) / 1000 + " s");
        stopScan();
        waitForDevice();
    };

    private void stopScan() {
        handler.removeCallbacks(scanRestart);
        ScanCallback callback = scanCallback;
        scanCallback = null;
        if (callback == null || adapter == null) return;
        debug("Scan stopped after " + (SystemClock.elapsedRealtime() - scanStartedAt) + " ms");
        try {
            if (adapter.getBluetoothLeScanner() != null)
                adapter.getBluetoothLeScanner().stopScan(callback);
        } catch (SecurityException | IllegalStateException ignored) {
        }
    }

    private void connect(BluetoothDevice device) {
        if (closed || terminal || gatt != null) return;
        try {
            debug("Connecting");
            connectStartedAt = SystemClock.elapsedRealtime();
            gatt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE)
                    : device.connectGatt(context, false, callbacks);
            if (gatt == null) {
                if (userInitiated && !hasBeenReady) failHandshake(REASON_ERROR, "BluZ connection could not be started");
                else scheduleRetry();
            } else
                handler.postDelayed(connectionDeadline, HANDSHAKE_MS);
        } catch (SecurityException error) {
            permissionLost();
        }
    }

    private final Runnable connectionDeadline = () -> {
        debug("Connection deadline reached, " + (SystemClock.elapsedRealtime() - connectStartedAt)
                + " ms after connect, physical=" + physicalConnection);
        if (recoveryActive) recoveryAttemptFailed();
        else if (!physicalConnection) {
            if (userInitiated && !hasBeenReady) failHandshake(REASON_TIMEOUT, "BluZ connection timed out");
            else {
                releaseGatt();
                scheduleRetry();
            }
        } else if (hasBeenReady) connectionLostNow("BluZ handshake timed out");
        else failHandshake(REASON_TIMEOUT, "BluZ handshake timed out");
    };

    private boolean current(BluetoothGatt candidate) {
        return !closed && !terminal && candidate == gatt;
    }

    private final BluetoothGattCallback callbacks = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt candidate, int result, int newState) {
            dispatch(() -> {
                String state = "GATT connection state=" + newState + ", status=" + gattStatus(result)
                        + ", " + (SystemClock.elapsedRealtime() - connectStartedAt) + " ms after connect";
                if (!current(candidate)) {
                    debug(state + " (ignored, stale GATT)");
                    return;
                }
                debug(state);
                if (newState == BluetoothProfile.STATE_DISCONNECTED || result != BluetoothGatt.GATT_SUCCESS) {
                    if (recoveryActive) recoveryAttemptFailed();
                    else if (!physicalConnection && userInitiated && !hasBeenReady)
                        failHandshake(REASON_ERROR, "BluZ connection failed, status " + gattStatus(result));
                    else if (physicalConnection && !ready && !hasBeenReady)
                        failHandshake(REASON_ERROR, "BluZ disconnected during handshake");
                    else connectionLost("Connection lost, status " + gattStatus(result));
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    physicalConnection = true;
                    debug("Connected after " + (SystemClock.elapsedRealtime() - connectStartedAt) + " ms");
                    handler.removeCallbacks(connectionDeadline);
                    handler.postDelayed(connectionDeadline, HANDSHAKE_MS);
                    if (!recoveryActive) setStatus(STATUS_CONNECTING);
                    try {
                        candidate.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) candidate.readPhy();
                        if (!candidate.discoverServices())
                            failHandshake(REASON_ERROR, "Cannot discover BluZ services");
                    } catch (SecurityException error) {
                        permissionLost();
                    }
                }
            });
        }

        @Override
        public void onPhyRead(BluetoothGatt candidate, int txPhy, int rxPhy, int result) {
            dispatch(() -> {
                if (current(candidate)) debug("PHY tx=" + phyName(txPhy) + ", rx=" + phyName(rxPhy)
                        + ", status=" + gattStatus(result));
            });
        }

        @Override
        public void onPhyUpdate(BluetoothGatt candidate, int txPhy, int rxPhy, int result) {
            dispatch(() -> {
                if (current(candidate)) debug("PHY updated: tx=" + phyName(txPhy) + ", rx=" + phyName(rxPhy)
                        + ", status=" + gattStatus(result));
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt candidate, int result) {
            dispatch(() -> {
                if (!current(candidate)) return;
                debug("Services discovered, status=" + gattStatus(result));
                if (result != BluetoothGatt.GATT_SUCCESS || candidate.getService(SERVICE) == null) {
                    failHandshake(REASON_ERROR, "BluZ GATT service is missing");
                    return;
                }
                notifyCharacteristic = candidate.getService(SERVICE).getCharacteristic(NOTIFY);
                writeCharacteristic = candidate.getService(SERVICE).getCharacteristic(WRITE);
                if (notifyCharacteristic == null || writeCharacteristic == null
                        || (writeCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
                    failHandshake(REASON_ERROR, "BluZ characteristics are missing or incompatible");
                    return;
                }
                try {
                    if (!candidate.requestMtu(251))
                        failHandshake(REASON_ERROR, "Cannot request BluZ MTU");
                } catch (SecurityException error) {
                    permissionLost();
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt candidate, int mtu, int result) {
            dispatch(() -> {
                if (!current(candidate) || subscribed) return;
                debug("MTU=" + mtu + ", status=" + gattStatus(result));
                if (result != BluetoothGatt.GATT_SUCCESS || mtu < 251) {
                    failHandshake(REASON_ERROR, "BluZ requires an MTU of at least 251");
                    return;
                }
                enableNotifications(candidate);
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt candidate, BluetoothGattDescriptor descriptor, int result) {
            dispatch(() -> {
                if (!current(candidate) || !CCCD.equals(descriptor.getUuid())) return;
                debug("Notification subscription status=" + gattStatus(result));
                if (result != BluetoothGatt.GATT_SUCCESS) {
                    failHandshake(REASON_ERROR, "Cannot subscribe to BluZ frames");
                    return;
                }
                subscribed = true;
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt candidate, BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return;
            byte[] value = characteristic.getValue();
            if (value != null && NOTIFY.equals(characteristic.getUuid())) {
                byte[] copy = value.clone();
                dispatch(() -> {
                    if (current(candidate)) onPacket(copy);
                });
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt candidate, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (!NOTIFY.equals(characteristic.getUuid()) || value == null) return;
            byte[] copy = value.clone();
            dispatch(() -> {
                if (current(candidate)) onPacket(copy);
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt candidate, BluetoothGattCharacteristic characteristic, int result) {
            dispatch(() -> {
                if (!current(candidate) || !writing || !WRITE.equals(characteristic.getUuid()))
                    return;
                handler.removeCallbacks(writeDeadline);
                PendingWrite completed = writes.poll();
                writing = false;
                if (completed == null) return;
                if (result != BluetoothGatt.GATT_SUCCESS) {
                    writeFailed(completed.op, "BluZ GATT write failed, status " + gattStatus(result));
                    return;
                }
                if (completed.finished != null) completed.finished.run();
                writeNext();
            });
        }
    };

    private void enableNotifications(BluetoothGatt candidate) {
        try {
            BluetoothGattDescriptor descriptor = notifyCharacteristic.getDescriptor(CCCD);
            int properties = notifyCharacteristic.getProperties();
            byte[] value;
            if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            } else if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            } else {
                failHandshake(REASON_ERROR, "BluZ has no notification support");
                return;
            }
            if (descriptor == null || !candidate.setCharacteristicNotification(notifyCharacteristic, true)) {
                failHandshake(REASON_ERROR, "Cannot enable BluZ notifications");
                return;
            }
            boolean accepted;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                accepted = candidate.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS;
            } else {
                descriptor.setValue(value);
                accepted = candidate.writeDescriptor(descriptor);
            }
            if (!accepted) failHandshake(REASON_ERROR, "Cannot write BluZ notification descriptor");
        } catch (SecurityException error) {
            permissionLost();
        }
    }

    private final Runnable assemblyDeadline = () -> {
        if (decoder.isAssembling()) {
            decoder.reset();
            skipped("Incomplete BluZ frame timed out");
        }
    };

    private void onPacket(byte[] packet) {
        if (!subscribed) return;
        if (!AppPermissions.isBluetoothGranted(context)) {
            permissionLost();
            return;
        }
        boolean header = packet.length >= 4 && packet[0] == '<' && packet[1] == 'B' && packet[2] == '>';
        if (header) {
            handler.removeCallbacks(assemblyDeadline);
            handler.postDelayed(assemblyDeadline, ASSEMBLY_MS);
        }
        try {
            BluZFrameDecoder.Frame frame = decoder.accept(packet);
            if (decoder.takeDroppedFrames() > 0) skipped("BluZ frame was interrupted");
            if (frame == null) return;
            handler.removeCallbacks(assemblyDeadline);
            latestSettings = frame.settings.clone();
            if (!frame.isNormal()) return;
            latest = frame;
            if (!ready) {
                ready = true;
                hasBeenReady = true;
                userInitiated = false;
                retryDelay = 1000;
                handler.removeCallbacks(connectionDeadline);
                coefficients = frame.calibration();
                lastError = null;
                boolean recovering = recoveryActive;
                debug("First frame");
                if (recovering) {
                    cancelRecovery();
                    if (!frame.isCollecting() || stopOnReturn) {
                        status = STATUS_DISCONNECTED;
                        reply(new Intent(ACTION_SOURCE_DISCONNECTED).putExtra(EXTRA_SOURCE_DISCONNECT_REASON,
                                (stopOnReturn ? "Reconnected, acquisition was stopped during recovery"
                                        : "Reconnected, but the device is not collecting") + "; cause: " + recoveryReason));
                    }
                }
                if (recovering && frame.isCollecting() && !stopOnReturn) {
                    setStatus(STATUS_CONNECTED_COLLECTING);
                } else {
                    status = frame.isCollecting() ? STATUS_CONNECTED_COLLECTING : STATUS_CONNECTED_IDLE;
                    if (stopOnReturn) {
                        if (frame.isCollecting()) requestCollecting(false);
                        else stopOnReturn = false;
                    }
                    reply(new Intent(ACTION_SOURCE_READY)
                            .putExtra(EXTRA_SOURCE_STATUS, status)
                            .putExtra(EXTRA_SOURCE_DEVICE_ID, deviceId())
                            .putExtra(EXTRA_SOURCE_CHANNEL_COUNT, channelCount())
                            .putExtra(EXTRA_SOURCE_CALIBRATION_COEFFS, coefficients.clone()));
                }
            }
            if (stopOnReturn && !frame.isCollecting()) stopOnReturn = false;
            if (toggleOp != 0 && toggleSent && frame.isCollecting() == desiredCollecting) {
                handler.removeCallbacks(toggleDeadline);
                toggleOp = 0;
                toggleSent = false;
                recover();
            }
            if (resetPending && resetSent && ((frame.time == 0 && frame.pulses == 0)
                    || (frame.time < resetTime && frame.pulses <= resetPulses))) {
                resetPending = false;
                resetSent = false;
                handler.removeCallbacks(resetDeadline);
                recover();
            }
            if (resolutionPending && resolutionSent && frame.channels == 4096) {
                resolutionPending = false;
                resolutionSent = false;
                handler.removeCallbacks(resolutionDeadline);
                recover();
            }
            if (calibrationPending && calibrationSent && calibrationMatches(frame.calibration())) {
                handler.removeCallbacks(calibrationDeadline);
                calibrationPending = false;
                calibrationSent = false;
                pendingCalibration = null;
                coefficients = frame.calibration();
                recover();
                reply(new Intent(ACTION_SOURCE_CALIBRATION_SAVED));
            }
            if (frame.channels == 4096 && !resolutionPending
                    && (lastError == null || lastError.op != OP_SETTINGS_SAVE)) {
                resolutionAttempted = false;
            }
            if (lastError == null) updateObservedStatus();
            if (frame.isCollecting()) broadcastData(frame);
            if (frame.isCollecting() && frame.channels != 4096 && !resolutionAttempted
                    && !calibrationPending) {
                resolutionAttempted = true;
                resolutionPending = true;
                enqueue(BluZCommandCodec.resolution4096(latestSettings), OP_SETTINGS_SAVE, () -> {
                    resolutionSent = true;
                    handler.postDelayed(resolutionDeadline, COMMAND_MS);
                });
            }
        } catch (IllegalArgumentException error) {
            handler.removeCallbacks(assemblyDeadline);
            skipped(error.getMessage());
        }
    }

    private void updateObservedStatus() {
        if (latest == null) return;
        setStatus(toggleOp != 0 || resetPending || calibrationPending ? STATUS_CONNECTED_EXECUTING_COMMAND
                : latest.isCollecting() ? STATUS_CONNECTED_COLLECTING : STATUS_CONNECTED_IDLE);
    }

    private void recover() {
        if (lastError == null) return;
        if ((lastError.op == OP_SETTINGS_SAVE && resolutionPending)
                || ((lastError.op == OP_START || lastError.op == OP_STOP) && toggleOp != 0)
                || (lastError.op == OP_RESET && resetPending)
                || (lastError.op == OP_CALIBRATION_SAVE && calibrationPending)) return;
        lastError = null;
    }

    private boolean calibrationMatches(double[] observed) {
        if (pendingCalibration == null || observed.length != pendingCalibration.length)
            return false;
        for (int index = 0; index < observed.length; index++) {
            if (Double.compare(observed[index], pendingCalibration[index]) != 0) return false;
        }
        return true;
    }

    private void broadcastData(BluZFrameDecoder.Frame frame) {
        reply(new Intent(ACTION_SOURCE_DATA)
                .putExtra(EXTRA_SOURCE_DATA_HISTOGRAM, frame.histogram.clone())
                .putExtra(EXTRA_SOURCE_DATA_RECORDING_TIME, frame.time)
                .putExtra(EXTRA_SOURCE_DATA_CP1S, frame.cps));
    }

    @Override
    public void requestShowData() {
        dispatch(() -> {
            if (!usable(OP_SHOW)) return;
            if (latest != null && latest.isCollecting()) broadcastData(latest);
            else reply(new Intent(ACTION_SOURCE_DATA)
                    .putExtra(EXTRA_SOURCE_DATA_HISTOGRAM, new long[channelCount()])
                    .putExtra(EXTRA_SOURCE_DATA_RECORDING_TIME, 0.0)
                    .putExtra(EXTRA_SOURCE_DATA_CP1S, 0));
        });
    }

    @Override
    public void requestStart() {
        dispatch(() -> {
            if (closed) return;
            stopOnReturn = false;
            requestCollecting(true);
        });
    }

    @Override
    public void requestStop() {
        dispatch(() -> {
            if (closed) return;
            if (recoveryActive || (!ready && hasBeenReady)) {
                stopOnReturn = true;
                if (recoveryActive) {
                    cancelRecovery();
                    setStatus(STATUS_DISCONNECTED);
                    reply(new Intent(ACTION_SOURCE_DISCONNECTED)
                            .putExtra(EXTRA_SOURCE_DISCONNECT_REASON, "BluZ recovery cancelled by stop"));
                    if (gatt == null) waitForDevice();
                }
                return;
            }
            requestCollecting(false);
        });
    }

    private void requestCollecting(boolean collecting) {
        int op = collecting ? OP_START : OP_STOP;
        if (!usable(op)) return;
        if (toggleOp != 0) {
            if (desiredCollecting != collecting)
                error(op, REASON_ERROR, "BluZ acquisition command is already pending");
            return;
        }
        if (latest.isCollecting() == collecting) {
            recover();
            updateObservedStatus();
            return;
        }
        desiredCollecting = collecting;
        toggleOp = op;
        toggleSent = false;
        setStatus(STATUS_CONNECTED_EXECUTING_COMMAND);
        enqueue(BluZCommandCodec.command(2), op, () -> {
            toggleSent = true;
            handler.postDelayed(toggleDeadline, COMMAND_MS);
        });
    }

    private final Runnable toggleDeadline = () -> {
        int op = toggleOp;
        toggleOp = 0;
        toggleSent = false;
        if (op != 0) error(op, REASON_TIMEOUT, "BluZ acquisition state was not confirmed");
    };

    @Override
    public void requestReset() {
        dispatch(() -> {
            if (!usable(OP_RESET) || resetPending) return;
            if (latest.time == 0 && latest.pulses == 0) {
                recover();
                updateObservedStatus();
                return;
            }
            resetTime = latest.time;
            resetPulses = latest.pulses;
            resetPending = true;
            resetSent = false;
            setStatus(STATUS_CONNECTED_EXECUTING_COMMAND);
            enqueue(BluZCommandCodec.command(1), OP_RESET, () -> {
                resetSent = true;
                handler.postDelayed(resetDeadline, COMMAND_MS);
            });
        });
    }

    private final Runnable resetDeadline = () -> {
        resetPending = false;
        resetSent = false;
        error(OP_RESET, REASON_TIMEOUT, "BluZ spectrum reset was not confirmed");
    };

    private final Runnable resolutionDeadline = () -> {
        resolutionPending = false;
        resolutionSent = false;
        error(OP_SETTINGS_SAVE, REASON_TIMEOUT, "BluZ resolution change was not confirmed");
    };

    private final Runnable calibrationDeadline = () -> {
        if (!calibrationPending || !calibrationSent) return;
        calibrationPending = false;
        calibrationSent = false;
        pendingCalibration = null;
        error(OP_CALIBRATION_SAVE, REASON_TIMEOUT, "BluZ calibration change was not confirmed");
    };

    private boolean usable(int op) {
        if (closed) return false;
        if (ready && gatt != null && latest != null) return true;
        error(op, REASON_ERROR, recoveryActive ? "BluZ is reconnecting" : "BluZ is not ready");
        return false;
    }

    private void enqueue(byte[] command, int op, Runnable finished) {
        writes.add(new PendingWrite(Arrays.copyOfRange(command, 0, BluZCommandCodec.WRITE_CHUNK_LENGTH), op, null));
        writes.add(new PendingWrite(Arrays.copyOfRange(command, BluZCommandCodec.WRITE_CHUNK_LENGTH, command.length), op, finished));
        writeNext();
    }

    private void writeNext() {
        if (closed || terminal || writing || writes.isEmpty() || gatt == null) return;
        PendingWrite pending = writes.peek();
        if ((pending.op == OP_START || pending.op == OP_STOP) && pending.finished == null
                && latest != null && latest.isCollecting() == desiredCollecting) {
            writes.poll();
            writes.poll();
            toggleOp = 0;
            toggleSent = false;
            recover();
            updateObservedStatus();
            writeNext();
            return;
        }
        try {
            boolean accepted;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                accepted = gatt.writeCharacteristic(writeCharacteristic, pending.bytes,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS;
            } else {
                writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                writeCharacteristic.setValue(pending.bytes);
                accepted = gatt.writeCharacteristic(writeCharacteristic);
            }
            if (!accepted) {
                writeFailed(pending.op, "BluZ rejected a GATT write");
                return;
            }
            writing = true;
            handler.postDelayed(writeDeadline, COMMAND_MS);
        } catch (SecurityException error) {
            permissionLost();
        }
    }

    private final Runnable writeDeadline = () -> {
        PendingWrite pending = writes.peek();
        if (pending != null) {
            writeFailed(pending.op, "BluZ GATT write timed out");
            connectionLostNow("BluZ transport write timed out");
        }
    };

    private void writeFailed(int op, String text) {
        writes.clear();
        writing = false;
        handler.removeCallbacks(writeDeadline);
        handler.removeCallbacks(toggleDeadline);
        handler.removeCallbacks(resetDeadline);
        handler.removeCallbacks(resolutionDeadline);
        handler.removeCallbacks(calibrationDeadline);
        int pendingToggle = toggleOp;
        boolean pendingReset = resetPending;
        boolean pendingResolution = resolutionPending;
        boolean pendingCalibration = calibrationPending;
        toggleOp = 0;
        toggleSent = false;
        resetPending = false;
        resetSent = false;
        resolutionPending = false;
        resolutionSent = false;
        calibrationPending = false;
        calibrationSent = false;
        this.pendingCalibration = null;
        if (pendingToggle != 0 && pendingToggle != op) error(pendingToggle, REASON_ERROR, text);
        if (pendingReset && op != OP_RESET) error(OP_RESET, REASON_ERROR, text);
        if (pendingResolution && op != OP_SETTINGS_SAVE)
            error(OP_SETTINGS_SAVE, REASON_ERROR, text);
        if (pendingCalibration && op != OP_CALIBRATION_SAVE)
            error(OP_CALIBRATION_SAVE, REASON_ERROR, text);
        error(op, REASON_ERROR, text);
    }

    // a working link was lost: retry quietly first, the service is told only if that fails
    private void connectionLost(String reason) {
        // the service must hear about a lost link while a command is pending
        if (!ready) {
            connectionLostNow(reason);
            return;
        }
        if (toggleOp != 0 || resetPending || resolutionPending || calibrationPending) {
            int op = toggleOp != 0 ? toggleOp
                    : resetPending ? OP_RESET
                      : resolutionPending ? OP_SETTINGS_SAVE
                        : OP_CALIBRATION_SAVE;
            writeFailed(op, reason);
            connectionLostNow(reason);
            return;
        }
        if (latest == null || !latest.isCollecting()) {
            connectionLostNow(reason);
            return;
        }
        recoveryActive = true;
        setStatus(STATUS_RECOVERING);
        recoveryReason = reason;
        recoveryStartedAt = SystemClock.elapsedRealtime();
        AtomSpectraLog.warning(context, LogTag.BLUZ, "Recovery: started after " + reason + ", window "
                + RECOVERY_MAX_WINDOW_MS / 1000 + " s");
        stopScan();
        releaseGatt();
        handler.removeCallbacks(recoveryWindowEnd);
        handler.postDelayed(recoveryWindowEnd, RECOVERY_MAX_WINDOW_MS);
        waitForDevice();
    }

    private void connectionLostNow(String reason) {
        boolean wasActive = ready || recoveryActive;
        cancelRecovery();
        stopScan();
        releaseGatt();
        setStatus(STATUS_DISCONNECTED);
        if (wasActive)
            reply(new Intent(ACTION_SOURCE_DISCONNECTED).putExtra(EXTRA_SOURCE_DISCONNECT_REASON, reason));
        scheduleRetry();
    }

    private void recoveryAttemptFailed() {
        if (!recoveryActive) return;
        AtomSpectraLog.warning(context, LogTag.BLUZ, "Recovery: attempt failed after "
                + (SystemClock.elapsedRealtime() - recoveryStartedAt) / 1000 + " s of recovery");
        releaseGatt();
        scheduleRetry();
    }

    private void cancelRecovery() {
        recoveryActive = false;
        handler.removeCallbacks(recoveryWindowEnd);
        recoveryStartedAt = 0;
        stopScan();
    }

    private final Runnable recoveryWindowEnd = this::endRecoveryByTimeout;

    // Recovery expired: report the loss and keep connecting in the background, leaving any GATT in flight alone.
    private void endRecoveryByTimeout() {
        if (!recoveryActive) return;
        String reason = "Recovery timed out after " + (SystemClock.elapsedRealtime() - recoveryStartedAt) / 1000
                + " s; cause: " + recoveryReason;
        cancelRecovery();
        setStatus(STATUS_DISCONNECTED);
        reply(new Intent(ACTION_SOURCE_DISCONNECTED).putExtra(EXTRA_SOURCE_DISCONNECT_REASON, reason));
        if (gatt == null) waitForDevice();
        debug("Recovery ended, still connecting in background");
    }

    private void debug(String message) {
        AtomSpectraLog.detail(context, LogTag.BLUZ, recoveryActive ? "Recovery: " + message : message);
    }

    private void permissionLost() {
        failHandshake(REASON_PERMISSION, "Bluetooth permission was lost");
    }

    private void failHandshake(int reason, String text) {
        if (recoveryActive && reason != REASON_PERMISSION) {
            AtomSpectraLog.warning(context, LogTag.BLUZ, "Recovery: GATT setup failed: " + text);
            recoveryAttemptFailed();
            return;
        }
        boolean recovering = recoveryActive;
        terminal = true;
        userInitiated = false;
        cancelRecovery();
        releaseGatt();
        stopScan();
        handler.removeCallbacks(retry);
        if (recovering) {
            setStatus(STATUS_DISCONNECTED);
            reply(new Intent(ACTION_SOURCE_DISCONNECTED).putExtra(EXTRA_SOURCE_DISCONNECT_REASON, text));
        }
        error(OP_CONNECT, reason, text);
    }

    private void releaseGatt() {
        handler.removeCallbacks(connectionDeadline);
        handler.removeCallbacks(assemblyDeadline);
        handler.removeCallbacks(writeDeadline);
        handler.removeCallbacks(toggleDeadline);
        handler.removeCallbacks(resetDeadline);
        handler.removeCallbacks(resolutionDeadline);
        handler.removeCallbacks(calibrationDeadline);
        BluetoothGatt previous = gatt;
        gatt = null;
        ready = false;
        physicalConnection = false;
        subscribed = false;
        writing = false;
        latest = null;
        latestSettings = null;
        writeCharacteristic = null;
        notifyCharacteristic = null;
        toggleOp = 0;
        resetPending = false;
        resolutionPending = false;
        calibrationPending = false;
        calibrationSent = false;
        pendingCalibration = null;
        resolutionAttempted = false;
        resolutionSent = false;
        writes.clear();
        decoder.reset();
        if (previous != null) {
            try {
                previous.disconnect();
            } catch (SecurityException error) {
                debug("GATT disconnect failed: " + error);
            }
            try {
                previous.close();
            } catch (SecurityException error) {
                debug("GATT close failed: " + error);
            }
        }
    }

    @Override
    public void close() {
        dispatch(() -> {
            if (closed) return;
            closed = true;
            handler.removeCallbacks(scanTimeout);
            cancelRecovery();
            stopScan();
            releaseGatt();
            handler.removeCallbacks(retry);
            if (adapterReceiver != null) {
                context.unregisterReceiver(adapterReceiver);
                adapterReceiver = null;
            }
            status = STATUS_CLOSED;
        });
    }

    private void reply(Intent intent) {
        if (!closed) context.sendBroadcast(intent.setPackage(Constants.PACKAGE_NAME)
                .putExtra(EXTRA_SOURCE_INPUT_TYPE, inputType())
                .putExtra(EXTRA_SOURCE_INSTANCE_ID, instanceId));
    }

    private void setStatus(int next) {
        if (status == next) return;
        status = next;
        reply(new Intent(ACTION_SOURCE_STATUS).putExtra(EXTRA_SOURCE_STATUS, next));
    }

    private void error(int op, int reason, String text) {
        lastError = new SourceError(op, reason, text);
        setStatus(STATUS_CONNECTED_COMMAND_FAILED);
        reply(new Intent(ACTION_SOURCE_ERROR).putExtra(EXTRA_SOURCE_ERROR_OP, op)
                .putExtra(EXTRA_SOURCE_ERROR_REASON, reason).putExtra(EXTRA_SOURCE_ERROR_TEXT, text));
    }

    private void skipped(String text) {
        if (AtomSpectraLog.isDiagnosticsEnabled(context)) debug("Frame skipped: " + text);
        reply(new Intent(ACTION_SOURCE_DATA_SKIPPED).putExtra(EXTRA_SOURCE_DATA_SKIPPED_REASON, text));
    }

    @Override
    public void requestSaveCalibration(double[] coeffs) {
        double[] requested = coeffs == null ? null : coeffs.clone();
        dispatch(() -> {
            if (!usable(OP_CALIBRATION_SAVE)) return;
            if (resolutionPending || calibrationPending) {
                error(OP_CALIBRATION_SAVE, REASON_ERROR, "BluZ settings write is already pending");
                return;
            }
            try {
                byte[] command = BluZCommandCodec.calibration4096(latestSettings, requested);
                pendingCalibration = new double[requested.length];
                for (int index = 0; index < requested.length; index++) {
                    pendingCalibration[index] = (float) requested[index];
                }
                calibrationPending = true;
                calibrationSent = false;
                setStatus(STATUS_CONNECTED_EXECUTING_COMMAND);
                enqueue(command, OP_CALIBRATION_SAVE, () -> {
                    calibrationSent = true;
                    handler.postDelayed(calibrationDeadline, COMMAND_MS);
                });
            } catch (IllegalArgumentException invalid) {
                error(OP_CALIBRATION_SAVE, REASON_ERROR, invalid.getMessage());
            }
        });
    }

    @Override
    public void setInitialHistogram(long[] histogram, double recordingTimeSec) {
        dispatch(() -> {
            if (usable(OP_START))
                error(OP_START, REASON_ERROR, "BluZ cannot accept an initial histogram");
        });
    }

    @Override
    public boolean supportsInitialHistogram() {
        return false;
    }

    @Override
    public int instanceId() {
        return instanceId;
    }

    @Override
    public int inputType() {
        return TYPE_BLUZ;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public SourceError lastError() {
        return lastError;
    }

    @Override
    public int channelCount() {
        return BluZFrameDecoder.CHANNEL_COUNT;
    }

    @Override
    public String deviceId() {
        return "BluZ " + address;
    }

    @Override
    public double[] calibration() {
        return coefficients.clone();
    }

    private static final class PendingWrite {
        final byte[] bytes;
        final int op;
        final Runnable finished;

        PendingWrite(byte[] bytes, int op, Runnable finished) {
            this.bytes = bytes;
            this.op = op;
            this.finished = finished;
        }
    }
}