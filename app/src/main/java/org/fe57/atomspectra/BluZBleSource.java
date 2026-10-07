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
    private static final long HANDSHAKE_MS = 20000;
    private static final long COMMAND_MS = 15000;
    private static final long ASSEMBLY_MS = 10000;
    private static final boolean DEBUG_LOG = false; // set to true only briefly for debugging - it adds timing messages to the log on every reconnect
    private static final String LOG_TAG = "BluZ";
    // a lost link is retried silently, the service hears about it only if the whole window passes without a valid frame
    private static final long[] FAST_RETRY_DELAYS_MS = {1000, 3000, 5000};
    private static final long FAST_ATTEMPT_MS = 2000; // time to establish the physical connection in one fast attempt
    private static final long FAST_WINDOW_MS = 15000;
    private static final long SLOW_RETRY_MS = 30000;
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
    private boolean physicalConnection;
    private boolean subscribed;
    private boolean writing;
    private long retryDelay = 1000;
    private boolean silentRetry;
    private int silentAttempt;
    private String silentReason;
    private boolean silentCollecting;
    private BluZFrameDecoder.Frame latest;
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
    public void requestConnect() {
        dispatch(() -> {
            if (closed) return;
            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                failHandshake(REASON_ERROR, "Invalid BluZ Bluetooth address");
                return;
            }
            if (ready && lastError != null) connectionLostNow("Retrying BluZ connection");
            else if (ready || physicalConnection || silentRetry) return;
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
                                else if (silentRetry) return;
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
            waitForDevice();
        });
    }

    private final Runnable retry = this::waitForDevice;

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
                        stopScan();
                        connect(result.getDevice());
                    });
                }

                @Override
                public void onScanFailed(int errorCode) {
                    dispatch(() -> {
                        if (scanCallback != this) return;
                        stopScan();
                        scheduleRetry();
                    });
                }
            };
            scanCallback = callback;
            debug("Scan started");
            adapter.getBluetoothLeScanner().startScan(
                    Collections.singletonList(new ScanFilter.Builder().setDeviceAddress(address).build()),
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), callback);
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
        handler.postDelayed(retry, retryDelay);
        retryDelay = Math.min(30000, retryDelay * 2);
    }

    private void stopScan() {
        ScanCallback callback = scanCallback;
        scanCallback = null;
        if (callback == null || adapter == null) return;
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
            gatt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE)
                    : device.connectGatt(context, false, callbacks);
            if (gatt == null) {
                if (silentRetry) silentAttemptFailed();
                else scheduleRetry();
            } else handler.postDelayed(connectionDeadline, silentRetry ? FAST_ATTEMPT_MS : HANDSHAKE_MS);
        } catch (SecurityException error) {
            permissionLost();
        }
    }

    private final Runnable connectionDeadline = () -> {
        if (silentRetry) silentAttemptFailed();
        else if (!physicalConnection) {
            releaseGatt();
            scheduleRetry();
        } else failHandshake(REASON_TIMEOUT, "BluZ handshake timed out");
    };

    private boolean current(BluetoothGatt candidate) {
        return !closed && !terminal && candidate == gatt;
    }

    private final BluetoothGattCallback callbacks = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt candidate, int result, int newState) {
            dispatch(() -> {
                if (!current(candidate)) return;
                if (newState == BluetoothProfile.STATE_DISCONNECTED || result != BluetoothGatt.GATT_SUCCESS) {
                    if (silentRetry) silentAttemptFailed();
                    else if (physicalConnection && !ready)
                        failHandshake(REASON_ERROR, "BluZ disconnected during handshake");
                    else connectionLost("BluZ connection lost (" + result + ")");
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    physicalConnection = true;
                    debug("Connected");
                    if (silentRetry) handler.removeCallbacks(connectionDeadline);
                    else setStatus(STATUS_CONNECTING);
                    try {
                        candidate.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                        if (!candidate.discoverServices())
                            failHandshake(REASON_ERROR, "Cannot discover BluZ services");
                    } catch (SecurityException error) {
                        permissionLost();
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt candidate, int result) {
            dispatch(() -> {
                if (!current(candidate)) return;
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
                    writeFailed(completed.op, "BluZ GATT write failed (" + result + ")");
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
                retryDelay = 1000;
                handler.removeCallbacks(connectionDeadline);
                coefficients = frame.calibration();
                lastError = null;
                status = frame.isCollecting() ? STATUS_CONNECTED_COLLECTING : STATUS_CONNECTED_IDLE;
                debug("First frame");
                if (silentRetry) {
                    cancelSilentRetry();
                    // the device restarted while we were away: let the service take its normal resume path
                    if (silentCollecting && !frame.isCollecting())
                        reply(new Intent(ACTION_SOURCE_DISCONNECTED).putExtra(EXTRA_SOURCE_DISCONNECT_REASON, silentReason));
                }
                reply(new Intent(ACTION_SOURCE_READY)
                        .putExtra(EXTRA_SOURCE_STATUS, status)
                        .putExtra(EXTRA_SOURCE_DEVICE_ID, deviceId())
                        .putExtra(EXTRA_SOURCE_CHANNEL_COUNT, channelCount())
                        .putExtra(EXTRA_SOURCE_CALIBRATION_COEFFS, coefficients.clone()));
            }
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
        dispatch(() -> requestCollecting(true));
    }

    @Override
    public void requestStop() {
        dispatch(() -> requestCollecting(false));
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
        error(op, REASON_ERROR, "BluZ is not ready");
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
            connectionLost("BluZ transport write timed out");
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
        if (!ready) {
            connectionLostNow(reason);
            return;
        }
        silentRetry = true;
        silentAttempt = 0;
        silentReason = reason;
        silentCollecting = status == STATUS_CONNECTED_COLLECTING;
        AtomSpectraLog.addMessage(context, reason + ", retrying");
        stopScan();
        releaseGatt();
        handler.postDelayed(silentWindowEnd, FAST_WINDOW_MS);
        scheduleSilentAttempt();
    }

    private void connectionLostNow(String reason) {
        boolean wasActive = ready || silentRetry;
        cancelSilentRetry();
        stopScan();
        releaseGatt();
        setStatus(STATUS_DISCONNECTED);
        if (wasActive)
            reply(new Intent(ACTION_SOURCE_DISCONNECTED).putExtra(EXTRA_SOURCE_DISCONNECT_REASON, reason));
        scheduleRetry();
    }

    private final Runnable silentAttemptStart = () -> {
        if (closed || terminal || !silentRetry || gatt != null) return;
        silentAttempt++;
        debug("Fast retry attempt " + silentAttempt);
        connect(adapter.getRemoteDevice(address));
    };

    private final Runnable silentWindowEnd = this::endSilentRetry;

    private void scheduleSilentAttempt() {
        if (silentAttempt >= FAST_RETRY_DELAYS_MS.length) endSilentRetry();
        else handler.postDelayed(silentAttemptStart, FAST_RETRY_DELAYS_MS[silentAttempt]);
    }

    private void silentAttemptFailed() {
        if (!silentRetry) return;
        debug("Fast retry attempt " + silentAttempt + " failed");
        releaseGatt();
        scheduleSilentAttempt();
    }

    private void cancelSilentRetry() {
        silentRetry = false;
        handler.removeCallbacks(silentAttemptStart);
        handler.removeCallbacks(silentWindowEnd);
    }

    // fast retries did not help: report the loss and wait for the device in the background
    private void endSilentRetry() {
        if (!silentRetry) return;
        String reason = silentReason;
        cancelSilentRetry();
        releaseGatt();
        retryDelay = SLOW_RETRY_MS;
        setStatus(STATUS_DISCONNECTED);
        reply(new Intent(ACTION_SOURCE_DISCONNECTED).putExtra(EXTRA_SOURCE_DISCONNECT_REASON, reason));
        waitForDevice();
    }

    private void debug(String message) {
        if (DEBUG_LOG) AtomSpectraLog.addMessage(context, LOG_TAG, message);
    }

    private void permissionLost() {
        failHandshake(REASON_PERMISSION, "Bluetooth permission was lost");
    }

    private void failHandshake(int reason, String text) {
        terminal = true;
        cancelSilentRetry();
        releaseGatt();
        stopScan();
        handler.removeCallbacks(retry);
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
            } catch (SecurityException ignored) {
            }
            try {
                previous.close();
            } catch (SecurityException ignored) {
            }
        }
    }

    @Override
    public void close() {
        dispatch(() -> {
            if (closed) return;
            closed = true;
            cancelSilentRetry();
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