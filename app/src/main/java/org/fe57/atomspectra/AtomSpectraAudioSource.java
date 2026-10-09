package org.fe57.atomspectra;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

public class AtomSpectraAudioSource implements SpectrumSource {
    private static final int ADC_EFF_BITS = 13;
    static final int HIST_POINTS = 8192;
    static final int MIN_FRONT_POINTS_DEFAULT = 4;
    static final int MAX_FRONT_POINTS_DEFAULT = 12;
    static final int NOISE_DISCRIMINATOR_DEFAULT = 64;
    static final int NOISE_DISCRIMINATOR_MAX = HIST_POINTS;
    static final boolean INVERSE_DEFAULT = false;
    static final boolean PILE_UP_DEFAULT = false;
    static final int AUDIO_SOURCE_SETTING_RAW = 2;
    static final int AUDIO_SOURCE_SETTING_VOICE = 1;
    static final int AUDIO_SOURCE_SETTING_DEFAULT = AUDIO_SOURCE_SETTING_RAW;
    static final double OSCILLOSCOPE_SAMPLE_CENTER = HIST_POINTS / 2.0;
    private static final int REFERENCE_PULSE_POINTS = 256;
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int AUDIO_SOURCE_VOICE = MediaRecorder.AudioSource.VOICE_RECOGNITION;
    private static final int AUDIO_SOURCE_RAW = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
            ? MediaRecorder.AudioSource.UNPROCESSED
            : MediaRecorder.AudioSource.VOICE_RECOGNITION;

    private static final int AUDIO_ZERO_DATA_MAX_COUNT = 5;

    private static final int REPORT_PERIOD_MS = 100;
    // raw audio snapshots are independent of the report interval: fast enough for a scope view
    private static final int SCOPE_SNAPSHOT_PERIOD_MS = 200;
    private static final long DEVICE_RECOVERY_WINDOW_MS = 5000;
    private static final long CAPTURE_START_WINDOW_MS = 5000;

    private static final String LOG_TAG = "Audio";

    private static void log(Context ctx, String message) {
        AtomSpectraLog.add(ctx, AtomSpectraLog.Type.EVENT, AtomSpectraLog.Severity.WARNING, LOG_TAG, message);
    }

    private static String audioSourceName(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.DEFAULT:
                return "DEFAULT";
            case MediaRecorder.AudioSource.MIC:
                return "MIC";
            case MediaRecorder.AudioSource.CAMCORDER:
                return "CAMCORDER";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION:
                return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION:
                return "VOICE_COMMUNICATION";
            case MediaRecorder.AudioSource.UNPROCESSED:
                return "UNPROCESSED";
            default:
                return "unknown";
        }
    }

    private volatile Context context;
    private final int instanceId = SourceInstanceId.next();
    // replaced with a fresh token when the same device returns after re-enumeration
    private volatile AudioDeviceInfo device;
    private final String identity;
    private volatile int status = SpectrumSource.STATUS_DISCONNECTED;
    private volatile SourceError lastError = null;
    private volatile String deviceId = "default";
    private AudioDeviceCallback deviceLossCallback = null;
    private final HandlerThread sourceThread;
    private final Handler sourceHandler;
    private final Runnable recoveryTimeout = this::onDeviceRecoveryTimeout;
    private final Runnable recoveryCaptureRetry = this::retryRecoveryCapture;
    private final Runnable captureStartTimeout = this::onCaptureStartTimeout;
    private final Runnable reportRunnable = this::reportTask;

    // pulse-detection tuning, read in requestConnect() and on every application preferences change
    private volatile int frontCountsMin = MIN_FRONT_POINTS_DEFAULT;
    private volatile int frontCountsMax = MAX_FRONT_POINTS_DEFAULT;
    private volatile int histogramMinChannel = NOISE_DISCRIMINATOR_DEFAULT;
    private volatile boolean inversion = INVERSE_DEFAULT;
    private volatile boolean pileup = PILE_UP_DEFAULT;
    private volatile int reportIntervalMs = 1000 / Constants.UPDATE_DOSE_DEFAULT;

    private int audioSourceMode = AUDIO_SOURCE_VOICE;

    private boolean capturing = false;
    private boolean captureConfirmed = false;
    private boolean recoveryCapture = false;
    private long recoveryCaptureDeadline;
    private volatile long captureGeneration;
    private HandlerThread captureThread;
    private AudioRecord audioRecord = null;
    private int bufferSize = 0;
    private byte[] audioBytes = null;
    private int[] audioData = null;
    private int audioBytesRead = 0;
    private int audioZeroDataCount = 0;

    private final long[] histogram;
    private long totalSamplesProcessed = 0;
    private final int[] cp1sArray = new int[1000 / REPORT_PERIOD_MS];
    private int cp1sPos = 0;
    private int countsThisReportPeriod = 0;
    // sum of the 256 samples around the peak of every pulse counted into the histogram (128 before, 127 after); only the shape matters, so it is never normalised
    // grows while collecting; cleared by requestReset() and when a setting that changes which pulses are accepted or how they look changes
    private final long[] referencePulse = new long[REFERENCE_PULSE_POINTS];
    private double[] lastCapturedSamples = new double[0];

    private long captureTaskIntervalMs = 0;
    private long captureElapsedMs = 0;
    private long captureOldElapsedMs = 0;
    private int elapsedSinceReportMs = 0;
    private int elapsedSinceScopeSnapshotMs = 0;

    public AtomSpectraAudioSource(Context context, AudioDeviceInfo device) {
        this(context, device, DeviceIdentity.audio(device));
    }

    /**
     * Waits for this identity if the token is not on the system yet.
     */
    public AtomSpectraAudioSource(Context context, String identity) {
        this(context, findAudioDevice(context, identity), identity);
    }

    private AtomSpectraAudioSource(Context context, AudioDeviceInfo device, String identity) {
        this.context = context;
        this.device = device;
        this.identity = identity != null ? identity : DeviceIdentity.AUDIO_DEFAULT;
        this.histogram = new long[HIST_POINTS];
        this.sourceThread = new HandlerThread("AtomSpectraAudioSource");
        this.sourceThread.start();
        this.sourceHandler = new Handler(this.sourceThread.getLooper());
    }

    private boolean deferToSourceThread(Runnable task) {
        if (this.context == null) return true;
        if (Looper.myLooper() == this.sourceHandler.getLooper()) return false;
        this.sourceHandler.post(() -> {
            if (this.context != null) task.run();
        });
        return true;
    }

    @Override
    public boolean supportsInitialHistogram() {
        return true;
    }

    @Override
    public void setInitialHistogram(long[] initialHistogram, double recordingTimeSec) {
        if (Looper.myLooper() != this.sourceHandler.getLooper()) {
            long[] snapshot = initialHistogram == null ? null : Arrays.copyOf(initialHistogram, initialHistogram.length);
            this.deferToSourceThread(() -> this.setInitialHistogram(snapshot, recordingTimeSec));
            return;
        }
        if (this.rejectIfDisconnected(SpectrumSource.OP_START)) return;
        if (this.capturing) {
            this.emitError(SpectrumSource.OP_START, SpectrumSource.REASON_ERROR, "Initial histogram can't be set while collecting");
            return;
        }

        Arrays.fill(this.histogram, 0);
        if (initialHistogram != null) {
            System.arraycopy(initialHistogram, 0, this.histogram, 0, Math.min(initialHistogram.length, HIST_POINTS));
        }
        this.totalSamplesProcessed = Math.round(Math.max(0, recordingTimeSec) * SAMPLE_RATE);
        Arrays.fill(this.cp1sArray, 0);
        this.cp1sPos = 0;
        this.countsThisReportPeriod = 0;
    }

    @Override
    public int instanceId() {
        return this.instanceId;
    }

    @Override
    public void requestConnect() {
        if (this.deferToSourceThread(this::requestConnect)) return;
        if (this.rejectIfClosed(SpectrumSource.OP_CONNECT)) return;

        if (this.status != SpectrumSource.STATUS_DISCONNECTED) {
            return;
        }

        final Context ctx = this.context;
        if (ctx == null || !AppPermissions.isMicGranted(ctx)) {
            this.emitError(SpectrumSource.OP_CONNECT, SpectrumSource.REASON_PERMISSION, "Microphone permission not granted");
            return;
        }

        if (this.device == null && !DeviceIdentity.AUDIO_DEFAULT.equals(this.identity)) {
            this.device = findAudioDevice(ctx, this.identity);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !DeviceIdentity.AUDIO_DEFAULT.equals(this.identity)) {
            this.registerDeviceLossCallback(ctx);
        }

        if (this.device == null && !DeviceIdentity.AUDIO_DEFAULT.equals(this.identity)) {
            return;
        }

        this.completeConnect(ctx);
    }

    private void completeConnect(Context ctx) {
        this.loadAppPreferences(ctx);

        this.deviceId = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && this.device != null)
                ? this.device.getProductName().toString()
                : "default";

        this.setAndEmitStatus(SpectrumSource.STATUS_CONNECTED_IDLE);
        this.emitReady();
    }

    @TargetApi(Build.VERSION_CODES.M)
    private static AudioDeviceInfo findAudioDevice(Context ctx, String identity) {
        if (ctx == null || identity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            return null;
        AudioManager manager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return null;
        AudioDeviceInfo[] devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        if (devices == null) return null;
        for (AudioDeviceInfo device : devices) {
            if (device.isSource() && identity.equals(DeviceIdentity.audio(device))) return device;
        }
        return null;
    }

    private void loadAppPreferences(Context ctx) {
        SharedPreferences sp = PrefHelper.getASSharedPreferences(ctx);
        this.frontCountsMin = sp.getInt(Constants.CONFIG.CONF_MIN_POINTS, MIN_FRONT_POINTS_DEFAULT);
        this.frontCountsMax = sp.getInt(Constants.CONFIG.CONF_MAX_POINTS, MAX_FRONT_POINTS_DEFAULT);
        this.histogramMinChannel = sp.getInt(Constants.CONFIG.CONF_NOISE, NOISE_DISCRIMINATOR_DEFAULT);
        this.inversion = sp.getBoolean(Constants.CONFIG.CONF_INVERSION, INVERSE_DEFAULT);
        this.pileup = sp.getBoolean(Constants.CONFIG.CONF_PILE_UP, PILE_UP_DEFAULT);
        this.reportIntervalMs = 1000 / sp.getInt(Constants.CONFIG.CONF_DOSE_UPDATE, Constants.UPDATE_DOSE_DEFAULT);
    }

    // raw (unprocessed) capture is used only when requested in preferences and supported by the device
    private int resolveAudioSourceMode(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return AUDIO_SOURCE_VOICE;

        SharedPreferences sp = PrefHelper.getASSharedPreferences(ctx);
        if (sp.getInt(Constants.CONFIG.CONF_AUDIO_SOURCE, AUDIO_SOURCE_SETTING_DEFAULT) != AUDIO_SOURCE_SETTING_RAW) {
            return AUDIO_SOURCE_VOICE;
        }

        AudioManager manager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (manager != null && manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) != null) {
            return AUDIO_SOURCE_RAW;
        }
        log(ctx, "Raw (unprocessed) audio source requested but not supported by this device; using voice-recognition source");
        return AUDIO_SOURCE_VOICE;
    }

    @Override
    public void onAppPreferencesChanged() {
        if (this.deferToSourceThread(this::onAppPreferencesChanged)) return;
        final Context ctx = this.context;
        if (ctx == null || this.status == SpectrumSource.STATUS_DISCONNECTED || this.status == SpectrumSource.STATUS_CLOSED)
            return;

        final int oldFrontMin = this.frontCountsMin;
        final int oldFrontMax = this.frontCountsMax;
        final int oldNoise = this.histogramMinChannel;
        final boolean oldInversion = this.inversion;
        final boolean oldPileup = this.pileup;
        this.loadAppPreferences(ctx);

        // the pulse sums only pulses accepted under the current settings, so pulses accepted under the previous ones must go
        boolean pulseSelectionChanged = oldFrontMin != this.frontCountsMin || oldFrontMax != this.frontCountsMax
                || oldNoise != this.histogramMinChannel || oldInversion != this.inversion || oldPileup != this.pileup;

        int newMode = this.resolveAudioSourceMode(ctx);
        if (pulseSelectionChanged || newMode != this.audioSourceMode) {
            Arrays.fill(this.referencePulse, 0);
        }
        if (newMode != this.audioSourceMode && this.capturing) {
            this.startCapture(this.recoveryCapture);
        }
        this.audioSourceMode = newMode;
    }

    // watches the AudioManager device list for the specific selected device disappearing; armed for the whole
    // connected lifetime (idle and collecting alike), not just while requestStart()/requestStop() bracket capture
    @TargetApi(Build.VERSION_CODES.M)
    private void registerDeviceLossCallback(Context ctx) {
        if (this.deviceLossCallback != null) return;
        AudioManager manager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return;

        this.deviceLossCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                final AudioDeviceInfo current = AtomSpectraAudioSource.this.device;
                for (AudioDeviceInfo removed : removedDevices) {
                    if (removed.isSource() && current != null && removed.getId() == current.getId()) {
                        AtomSpectraAudioSource.this.onSelectedDeviceLost();
                        return;
                    }
                }
            }

            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                for (AudioDeviceInfo added : addedDevices) {
                    if (added.isSource() && identity.equals(DeviceIdentity.audio(added))) {
                        AtomSpectraAudioSource.this.onSelectedDeviceReturned(added);
                        return;
                    }
                }
            }
        };
        manager.registerAudioDeviceCallback(this.deviceLossCallback, this.sourceHandler);
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void unregisterDeviceLossCallback() {
        if (this.deviceLossCallback == null) return;

        final Context ctx = this.context;
        if (ctx != null) {
            AudioManager manager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (manager != null) {
                manager.unregisterAudioDeviceCallback(this.deviceLossCallback);
            }
        }
        this.deviceLossCallback = null;
    }

    private void onSelectedDeviceLost() {
        if (this.status == SpectrumSource.STATUS_DISCONNECTED || this.status == SpectrumSource.STATUS_CLOSED)
            return;
        if (this.status == SpectrumSource.STATUS_RECOVERING && !this.capturing && !this.recoveryCapture)
            return;

        boolean wasCollecting = this.status == SpectrumSource.STATUS_CONNECTED_COLLECTING || this.recoveryCapture;
        this.stopCapture();
        if (!wasCollecting) {
            this.status = SpectrumSource.STATUS_DISCONNECTED;
            this.emitDisconnected("Audio device removed");
            return;
        }
        log(this.context, "Audio device removed during capture, waiting up to "
                + DEVICE_RECOVERY_WINDOW_MS / 1000 + " s for it to return");
        this.setAndEmitStatus(SpectrumSource.STATUS_RECOVERING);
        this.sourceHandler.removeCallbacks(this.recoveryTimeout);
        this.sourceHandler.postDelayed(this.recoveryTimeout, DEVICE_RECOVERY_WINDOW_MS);
    }

    private void onSelectedDeviceReturned(AudioDeviceInfo returned) {
        boolean recovering = this.status == SpectrumSource.STATUS_RECOVERING;
        if (this.capturing || (!recovering && this.status != SpectrumSource.STATUS_DISCONNECTED))
            return;

        final Context ctx = this.context;
        if (ctx == null) return;
        if (!AppPermissions.isMicGranted(ctx)) {
            if (recovering) {
                this.status = SpectrumSource.STATUS_DISCONNECTED;
                this.emitDisconnected("Audio recovery permission denied");
            }
            this.emitError(SpectrumSource.OP_CONNECT, SpectrumSource.REASON_PERMISSION, "Microphone permission was lost");
            return;
        }

        this.sourceHandler.removeCallbacks(this.recoveryTimeout);
        this.device = returned;
        if (recovering) {
            this.loadAppPreferences(ctx);
            this.deviceId = returned.getProductName().toString();
            this.recoveryCaptureDeadline = SystemClock.elapsedRealtime() + CAPTURE_START_WINDOW_MS;
            this.startCapture(true);
        } else {
            this.completeConnect(ctx);
        }
    }

    private void onDeviceRecoveryTimeout() {
        if (this.status != SpectrumSource.STATUS_RECOVERING || this.capturing) return;

        this.status = SpectrumSource.STATUS_DISCONNECTED;
        this.emitDisconnected("Audio recovery timed out after " + DEVICE_RECOVERY_WINDOW_MS / 1000
                + " s: the device did not return");
    }

    @Override
    public void requestShowData() {
        if (this.deferToSourceThread(this::requestShowData)) return;
        if (this.rejectIfDisconnected(SpectrumSource.OP_SHOW)) return;
        this.broadcastData();
    }

    @Override
    public void requestStart() {
        if (this.deferToSourceThread(this::requestStart)) return;
        if (this.rejectIfDisconnected(SpectrumSource.OP_START) || this.capturing) return;
        this.startCapture(false);
    }

    private void startCapture(boolean recovering) {
        this.stopCapture();
        final Context ctx = this.context;
        if (ctx == null) return;
        this.recoveryCapture = recovering;
        this.capturing = true;
        this.captureConfirmed = false;
        this.setAndEmitStatus(recovering ? SpectrumSource.STATUS_RECOVERING
                : SpectrumSource.STATUS_CONNECTED_EXECUTING_COMMAND);
        try {
            this.audioSourceMode = this.resolveAudioSourceMode(ctx);
            int minimumBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (minimumBufferSize <= 0)
                throw new IllegalStateException("Audio buffer configuration is unsupported");
            this.bufferSize = minimumBufferSize * 2;
            this.audioData = new int[this.bufferSize / 2];
            this.audioZeroDataCount = 0;
            this.captureTaskIntervalMs = Math.max(1, 1000L * this.bufferSize / 2 / SAMPLE_RATE);
            this.captureElapsedMs = 0;
            this.captureOldElapsedMs = 0;
            this.elapsedSinceReportMs = 0;
            this.elapsedSinceScopeSnapshotMs = 0;
            this.audioRecord = new AudioRecord(this.audioSourceMode, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, this.bufferSize);
            if (this.audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Audio recorder could not initialize");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !DeviceIdentity.AUDIO_DEFAULT.equals(this.identity)) {
                if (this.device == null || !this.audioRecord.setPreferredDevice(this.device)) {
                    throw new IllegalStateException("Selected audio input could not be preferred");
                }
            }
            this.audioRecord.startRecording();
            if (this.audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Audio recording did not start");
            }
            AtomSpectraLog.detail(ctx, LOG_TAG, "Audio capture configured: sampleRate=" + SAMPLE_RATE
                    + ", bufferSize=" + this.bufferSize + ", source=" + this.audioSourceMode
                    + " (" + audioSourceName(this.audioSourceMode) + ")");
            final long generation = this.captureGeneration;
            final AudioRecord record = this.audioRecord;
            final byte[] readBuffer = new byte[this.bufferSize];
            final long intervalMs = this.captureTaskIntervalMs;
            this.captureThread = new HandlerThread("AtomSpectra-Audio-Capture");
            this.captureThread.start();
            final Handler handler = new Handler(this.captureThread.getLooper());
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (generation != AtomSpectraAudioSource.this.captureGeneration) return;
                    final long readStarted = SystemClock.elapsedRealtime();
                    try {
                        int bytesRead = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                                ? record.read(readBuffer, 0, readBuffer.length, AudioRecord.READ_NON_BLOCKING)
                                : record.read(readBuffer, 0, readBuffer.length);
                        byte[] samples = bytesRead > 0 ? Arrays.copyOf(readBuffer, bytesRead) : new byte[0];
                        AtomSpectraAudioSource.this.sourceHandler.post(() -> {
                            if (generation != AtomSpectraAudioSource.this.captureGeneration) return;
                            try {
                                AtomSpectraAudioSource.this.onAudioRead(generation, record, samples, bytesRead);
                            } catch (RuntimeException error) {
                                AtomSpectraAudioSource.this.failCapture("Audio processing failed: " + error);
                            }
                            if (generation == AtomSpectraAudioSource.this.captureGeneration) {
                                long delay = Math.max(0, intervalMs - (SystemClock.elapsedRealtime() - readStarted));
                                handler.postDelayed(this, delay);
                            }
                        });
                    } catch (RuntimeException error) {
                        AtomSpectraAudioSource.this.sourceHandler.post(() -> {
                            if (generation == AtomSpectraAudioSource.this.captureGeneration) {
                                AtomSpectraAudioSource.this.failCapture("Audio read failed: " + error,
                                        error instanceof SecurityException ? SpectrumSource.REASON_PERMISSION : SpectrumSource.REASON_ERROR);
                            }
                        });
                        return;
                    }
                }
            });
            long remaining = recovering ? Math.max(0, this.recoveryCaptureDeadline - SystemClock.elapsedRealtime())
                    : CAPTURE_START_WINDOW_MS;
            this.sourceHandler.postDelayed(this.captureStartTimeout, remaining);
        } catch (RuntimeException error) {
            this.failCapture("Audio capture could not start: " + error,
                    error instanceof SecurityException ? SpectrumSource.REASON_PERMISSION : SpectrumSource.REASON_ERROR);
        }
    }

    private void onCaptureStartTimeout() {
        if ((this.capturing || this.recoveryCapture) && !this.captureConfirmed) {
            this.failCapture("No samples from the selected audio input before the capture deadline", SpectrumSource.REASON_TIMEOUT);
        }
    }

    private void failCapture(String reason) {
        this.failCapture(reason, SpectrumSource.REASON_ERROR);
    }

    private void failCapture(String reason, int errorReason) {
        boolean recovering = this.recoveryCapture;
        this.stopCapture();
        if (recovering) {
            long remaining = this.recoveryCaptureDeadline - SystemClock.elapsedRealtime();
            if (errorReason != SpectrumSource.REASON_PERMISSION && remaining > 0) {
                this.recoveryCapture = true;
                this.setAndEmitStatus(SpectrumSource.STATUS_RECOVERING);
                this.sourceHandler.postDelayed(this.captureStartTimeout, remaining);
                this.sourceHandler.postDelayed(this.recoveryCaptureRetry, Math.min(250, remaining));
                return;
            }
            this.status = SpectrumSource.STATUS_DISCONNECTED;
            this.emitDisconnected(reason);
            if (errorReason == SpectrumSource.REASON_PERMISSION) {
                this.emitError(SpectrumSource.OP_CONNECT, errorReason, reason);
            }
        } else {
            this.setAndEmitStatus(SpectrumSource.STATUS_CONNECTED_COMMAND_FAILED);
            this.emitError(errorReason == SpectrumSource.REASON_PERMISSION ? SpectrumSource.OP_CONNECT : SpectrumSource.OP_START,
                    errorReason, reason);
        }
    }

    private void retryRecoveryCapture() {
        if (this.context == null || !this.recoveryCapture || this.capturing
                || this.status != SpectrumSource.STATUS_RECOVERING) return;
        if (!AppPermissions.isMicGranted(this.context)) {
            this.failCapture("Microphone permission was lost", SpectrumSource.REASON_PERMISSION);
            return;
        }
        this.startCapture(true);
    }

    private void onAudioRead(long generation, AudioRecord record, byte[] samples, int bytesRead) {
        if (this.context == null || !this.capturing || generation != this.captureGeneration) return;
        if (bytesRead < 0) {
            this.failCapture("Audio read returned error " + bytesRead);
            return;
        }
        if (bytesRead == 0) {
            if (++this.audioZeroDataCount >= AUDIO_ZERO_DATA_MAX_COUNT && this.captureConfirmed) {
                this.failCapture("Audio input stopped delivering samples");
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !DeviceIdentity.AUDIO_DEFAULT.equals(this.identity)) {
            AudioDeviceInfo routed = record.getRoutedDevice();
            if (routed == null || this.device == null || routed.getId() != this.device.getId()) {
                if (this.captureConfirmed)
                    this.failCapture("Audio input no longer routes to the selected device");
                return;
            }
        }
        this.audioZeroDataCount = 0;
        if (!this.captureConfirmed) {
            this.captureConfirmed = true;
            this.recoveryCapture = false;
            this.sourceHandler.removeCallbacks(this.captureStartTimeout);
            this.setAndEmitStatus(SpectrumSource.STATUS_CONNECTED_COLLECTING);
            AtomSpectraLog.detail(this.context, LOG_TAG, "Audio capture started, device=" + this.deviceId);
            this.sourceHandler.postDelayed(this.reportRunnable, REPORT_PERIOD_MS);
        }
        this.audioBytes = samples;
        this.audioBytesRead = samples.length;
        this.captureAudioChunk();
    }

    @Override
    public void requestStop() {
        if (this.deferToSourceThread(this::requestStop)) return;
        if (this.context == null || this.status == SpectrumSource.STATUS_CLOSED) return;
        this.sourceHandler.removeCallbacks(this.recoveryTimeout);
        this.stopCapture();
        if (this.status == SpectrumSource.STATUS_RECOVERING || this.status == SpectrumSource.STATUS_DISCONNECTED) {
            this.status = SpectrumSource.STATUS_DISCONNECTED;
            this.emitDisconnected("Audio recovery cancelled by stop");
            AudioDeviceInfo available = findAudioDevice(this.context, this.identity);
            if (available != null && AppPermissions.isMicGranted(this.context)) {
                this.device = available;
                this.completeConnect(this.context);
            }
        } else {
            this.setAndEmitStatus(SpectrumSource.STATUS_CONNECTED_IDLE);
        }
    }

    @Override
    public void requestReset() {
        if (this.deferToSourceThread(this::requestReset)) return;
        if (this.rejectIfDisconnected(SpectrumSource.OP_RESET)) return;
        Arrays.fill(this.histogram, 0);
        this.totalSamplesProcessed = 0;
        Arrays.fill(this.cp1sArray, 0);
        this.cp1sPos = 0;
        this.countsThisReportPeriod = 0;
        Arrays.fill(this.referencePulse, 0);
        AudioScopeData.instance.reset();
    }

    @Override
    public void requestSaveCalibration(double[] coeffs) {
        if (Looper.myLooper() != this.sourceHandler.getLooper()) {
            double[] snapshot = Arrays.copyOf(coeffs, coeffs.length);
            this.deferToSourceThread(() -> this.requestSaveCalibration(snapshot));
            return;
        }
        if (this.rejectIfDisconnected(SpectrumSource.OP_CALIBRATION_SAVE)) return;
        final Context ctx = this.context;
        if (ctx == null) return;
        PrefHelper.setDeviceCalibration(ctx, this.deviceId, coeffs);
        this.broadcastReply(new Intent(SpectrumSource.ACTION_SOURCE_CALIBRATION_SAVED));
    }

    @Override
    public void close() {
        if (this.context == null) return;
        if (Looper.myLooper() != this.sourceHandler.getLooper()) {
            CountDownLatch closed = new CountDownLatch(1);
            if (!this.sourceHandler.post(() -> {
                try {
                    this.close();
                } finally {
                    closed.countDown();
                }
            })) return;
            boolean interrupted = false;
            while (true) {
                try {
                    closed.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return;
        }
        this.sourceHandler.removeCallbacks(this.recoveryTimeout);
        this.stopCapture();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.unregisterDeviceLossCallback();
        }
        AudioScopeData.instance.reset();
        this.setAndEmitStatus(SpectrumSource.STATUS_CLOSED);
        this.context = null;
        this.sourceThread.quitSafely();
    }

    @Override
    public int inputType() {
        return SpectrumSource.TYPE_AUDIO;
    }

    @Override
    public int status() {
        return this.status;
    }

    @Override
    public SourceError lastError() {
        return this.lastError;
    }

    @Override
    public int channelCount() {
        return HIST_POINTS;
    }

    @Override
    public String deviceId() {
        return this.deviceId;
    }

    @Override
    public double[] calibration() {
        final Context ctx = this.context;
        if (ctx == null) return null;
        double[] coeffs = PrefHelper.getDeviceCalibration(ctx, this.deviceId);
        if (coeffs != null) return coeffs;
        // until the user stores one for this device, the calibration shared by all devices in older versions applies
        Calibration legacy = PrefHelper.getLegacyCalibration(ctx, HIST_POINTS);
        return legacy == null ? null : legacy.getCoeffArray(legacy.getFactor() + 1);
    }

    /**
     * Publish the latest captured samples and the averaged reference pulse for the raw audio views.
     */
    private void publishScopeSnapshot() {
        if (!AudioScopeData.instance.wanted) return;

        final Context ctx = this.context;
        if (ctx == null) return;

        double[] samples;
        double[] pulse = new double[REFERENCE_PULSE_POINTS];
        samples = Arrays.copyOf(this.lastCapturedSamples, this.lastCapturedSamples.length);
        for (int i = 0; i < pulse.length; i++) pulse[i] = this.referencePulse[i];
        AudioScopeData.instance.set(samples, pulse);
        ctx.sendBroadcast(new Intent(Constants.ACTION.ACTION_RAW_AUDIO_SNAPSHOT).setPackage(Constants.PACKAGE_NAME));
    }

    private void stopCapture() {
        this.captureGeneration++;
        this.capturing = false;
        this.captureConfirmed = false;
        this.recoveryCapture = false;
        this.sourceHandler.removeCallbacks(this.reportRunnable);
        this.sourceHandler.removeCallbacks(this.captureStartTimeout);
        this.sourceHandler.removeCallbacks(this.recoveryCaptureRetry);
        if (this.audioRecord != null) {
            try {
                if (this.audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    this.audioRecord.stop();
                }
            } catch (RuntimeException error) {
                if (this.context != null) AtomSpectraLog.error(this.context, "Audio stop failed", error);
            }
        }
        if (this.captureThread != null) {
            this.captureThread.quitSafely();
            this.captureThread.interrupt();
            boolean interrupted = false;
            while (true) {
                try {
                    this.captureThread.join();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            this.captureThread = null;
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (this.audioRecord != null) {
            try {
                this.audioRecord.release();
            } catch (RuntimeException error) {
                if (this.context != null) AtomSpectraLog.error(this.context, "Audio release failed", error);
            } finally {
                this.audioRecord = null;
            }
        }
    }

    private void captureAudioChunk() {
        // First pass the 2 bytes into one sample - an extra loop, but avoids repeating the same sum many times during the filter below
        int highBitsShift = Math.max(0, 15 - ADC_EFF_BITS);
        for (int i = 0, r = 0; i < this.audioBytesRead - 2; i += 2, r++) {
            if (this.audioBytes[i] < 0)
                this.audioData[r] = this.audioBytes[i] + 256;
            else
                this.audioData[r] = this.audioBytes[i];
            this.audioData[r] = this.audioData[r] + 256 * this.audioBytes[i + 1];

            if (this.inversion)
                this.audioData[r] = -this.audioData[r];
            this.audioData[r] = (this.audioData[r] >> highBitsShift);
        }

        int sampleCount = this.audioBytesRead / 2;
        if (this.lastCapturedSamples.length != sampleCount) {
            this.lastCapturedSamples = new double[sampleCount];
        }
        for (int i = 0; i < sampleCount; i++) {
            this.lastCapturedSamples[i] = this.audioData[i];
        }
        this.totalSamplesProcessed += sampleCount;

        //-----------------DPP started--------------------------------------------------
        int initialAmp = 0, initialTime = 0;
        float corrector;
        for (int i = 1; i < this.audioBytesRead / 2 - 2; i++) {
            if (((this.audioData[i] - this.audioData[i - 1]) <= 0) && ((this.audioData[i + 1] - this.audioData[i]) > 0)) {
                initialAmp = this.audioData[i];
                initialTime = i;
            }
            if (((this.audioData[i] - this.audioData[i - 1]) >= 0) && ((this.audioData[i + 1] - this.audioData[i]) < 0)) {
                if (((i - initialTime) >= this.frontCountsMin) && ((i - initialTime) <= this.frontCountsMax)) {
                    if (i > this.frontCountsMax * 2)
                        corrector = this.audioData[initialTime - (i - initialTime)] - this.audioData[initialTime];
                    else corrector = 0;
                    if (!this.pileup) corrector = 0;
                    int channel = (this.audioData[i] - initialAmp + (int) corrector);
                    if ((channel >= this.histogramMinChannel) && (channel < HIST_POINTS)) {
                        //-----------------DPP finished-------------------------------------------------
                        this.histogram[channel]++;
                        this.countsThisReportPeriod++;

                        if ((i > 128) && (i < (1024 - 128)) && (i < ((this.audioBytesRead - 128) / 2)))
                            for (int j = -128; j < 127; j++)
                                this.referencePulse[j + 128] += this.audioData[i + j];
                    }
                }
            }
        }

        this.captureElapsedMs += this.captureTaskIntervalMs;
        // expected to be called 10 times per second
        if ((this.captureOldElapsedMs + REPORT_PERIOD_MS) < this.captureElapsedMs) {
            this.captureOldElapsedMs += REPORT_PERIOD_MS;
            this.cp1sPos = this.cp1sPos < (this.cp1sArray.length - 1) ? (this.cp1sPos + 1) : 0;
            this.cp1sArray[this.cp1sPos] = this.countsThisReportPeriod;
            this.countsThisReportPeriod = 0;
        }
    }

    // called every REPORT_PERIOD_MS (100ms); broadcasts the cumulative snapshot every reportIntervalMs
    private void reportTask() {
        if (this.context == null || !this.capturing || !this.captureConfirmed) return;

        this.elapsedSinceScopeSnapshotMs += REPORT_PERIOD_MS;
        if (this.elapsedSinceScopeSnapshotMs >= SCOPE_SNAPSHOT_PERIOD_MS) {
            this.elapsedSinceScopeSnapshotMs = 0;
            this.publishScopeSnapshot();
        }

        this.elapsedSinceReportMs += REPORT_PERIOD_MS;
        if (this.elapsedSinceReportMs >= this.reportIntervalMs) {
            this.elapsedSinceReportMs = 0;
            this.broadcastData();
        }
        this.sourceHandler.postDelayed(this.reportRunnable, REPORT_PERIOD_MS);
    }

    private void broadcastData() {
        final Context ctx = this.context;
        if (ctx == null) return;

        long[] histogramCopy;
        int cp1s;
        double recordingTime;
        histogramCopy = Arrays.copyOf(this.histogram, this.histogram.length);
        cp1s = 0;
        for (int value : this.cp1sArray) cp1s += value;
        recordingTime = (double) this.totalSamplesProcessed / SAMPLE_RATE;

        Intent intent = new Intent(SpectrumSource.ACTION_SOURCE_DATA).setPackage(Constants.PACKAGE_NAME);
        intent.putExtra(SpectrumSource.EXTRA_SOURCE_DATA_HISTOGRAM, histogramCopy);
        intent.putExtra(SpectrumSource.EXTRA_SOURCE_DATA_CP1S, cp1s);
        intent.putExtra(SpectrumSource.EXTRA_SOURCE_DATA_RECORDING_TIME, recordingTime);
        intent.putExtra(SpectrumSource.EXTRA_SOURCE_INPUT_TYPE, this.inputType());
        intent.putExtra(SpectrumSource.EXTRA_SOURCE_INSTANCE_ID, this.instanceId);
        ctx.sendBroadcast(intent);
    }

    private boolean rejectIfClosed(int op) {
        if (this.status == SpectrumSource.STATUS_CLOSED) {
            this.emitError(op, SpectrumSource.REASON_ERROR, "Request to an already closed source");
            return true;
        }
        return false;
    }

    private boolean rejectIfDisconnected(int op) {
        if (this.rejectIfClosed(op)) return true;
        if (this.status == SpectrumSource.STATUS_DISCONNECTED || this.status == SpectrumSource.STATUS_CONNECTING
                || this.status == SpectrumSource.STATUS_RECOVERING) {
            this.emitError(op, SpectrumSource.REASON_ERROR, "Request to a disconnected source");
            return true;
        }
        return false;
    }

    private void broadcastReply(@NonNull Intent intent) {
        final Context ctx = this.context;
        if (ctx == null) {
            return;
        }

        ctx.sendBroadcast(intent
                .setPackage(Constants.PACKAGE_NAME)
                .putExtra(EXTRA_SOURCE_INPUT_TYPE, this.inputType())
                .putExtra(EXTRA_SOURCE_INSTANCE_ID, this.instanceId));
    }

    private void emitDisconnected(String reason) {
        this.broadcastReply(new Intent(SpectrumSource.ACTION_SOURCE_DISCONNECTED)
                .putExtra(SpectrumSource.EXTRA_SOURCE_DISCONNECT_REASON, reason));
    }

    private void emitError(int op, int reason, String text) {
        this.lastError = new SourceError(op, reason, text);
        this.broadcastReply(new Intent(SpectrumSource.ACTION_SOURCE_ERROR)
                .putExtra(SpectrumSource.EXTRA_SOURCE_ERROR_OP, op)
                .putExtra(SpectrumSource.EXTRA_SOURCE_ERROR_REASON, reason)
                .putExtra(SpectrumSource.EXTRA_SOURCE_ERROR_TEXT, text));
    }

    private void setAndEmitStatus(int status) {
        if (status == SpectrumSource.STATUS_CONNECTED_IDLE || status == SpectrumSource.STATUS_CONNECTED_COLLECTING) {
            this.lastError = null;
        }
        if (this.status != status) {
            this.status = status;
            this.broadcastReply(new Intent(SpectrumSource.ACTION_SOURCE_STATUS)
                    .putExtra(SpectrumSource.EXTRA_SOURCE_STATUS, this.status));
        }
    }

    private void emitReady() {
        this.lastError = null;
        this.broadcastReply(new Intent(SpectrumSource.ACTION_SOURCE_READY)
                .putExtra(SpectrumSource.EXTRA_SOURCE_CHANNEL_COUNT, this.channelCount())
                .putExtra(SpectrumSource.EXTRA_SOURCE_STATUS, this.status)
                .putExtra(SpectrumSource.EXTRA_SOURCE_DEVICE_ID, this.deviceId));
    }
}
