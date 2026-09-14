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

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

// TODO: work on thread safety of this class
public class AtomSpectraAudioSource implements SpectrumSource {
    private static final int AUDIO_HIST_POINTS = 8192;
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

    private static final String LOG_TAG = "Audio";

    private static void log(Context ctx, String message) {
        AtomSpectraLog.addMessage(ctx, LOG_TAG, message);
    }

    private volatile Context context;
    private final AudioDeviceInfo device;
    private volatile int status = SpectrumSource.STATUS_DISCONNECTED;
    private volatile String deviceId = "default";
    // non-null only for a non-default device; watches for it disappearing while connected, idle or collecting
    private AudioDeviceCallback deviceLossCallback = null;

    // pulse-detection tuning, read once in requestConnect()
    private int frontCountsMin = Constants.MIN_FRONT_POINTS_DEFAULT;
    private int frontCountsMax = Constants.MAX_FRONT_POINTS_DEFAULT;
    private int histogramMinChannel = Constants.NOISE_DISCRIMINATOR_DEFAULT;
    private boolean inversion = Constants.INVERSE_DEFAULT;
    private boolean pileup = Constants.PILE_UP_DEFAULT;
    private int reportIntervalMs = 1000 / Constants.UPDATE_DOSE_DEFAULT;

    // resolved once in requestStart() from CONF_AUDIO_SOURCE
    private int audioSourceMode = AUDIO_SOURCE_VOICE;

    // capture state, written on the capture-timer thread, created lazily on the first capture tick
    private final Object captureLock = new Object();
    private AudioRecord audioRecord = null;
    private int bufferSize = 0;
    private byte[] audioBytes = null;
    private int[] audioData = null;
    private int audioBytesRead = 0;
    private int audioZeroDataCount = 0;

    // cumulative report state; written on the capture-timer thread, read on the report-timer thread and by the pull methods
    private final Object dataLock = new Object();
    private final long[] histogram;
    private long totalSamplesProcessed = 0;
    private final int[] cp1sArray = new int[1000 / REPORT_PERIOD_MS];
    private int cpsPos = 0;
    private int countsThisPeriod = 0;
    private final long[] referencePulse = new long[REFERENCE_PULSE_POINTS];
    private double[] lastCapturedSamples = new double[0];

    private Timer captureTimer = null;
    private Timer reportTimer = null;
    private long captureTaskIntervalMs = 0;
    private long captureElapsedMs = 0;
    private long captureOldElapsedMs = 0;
    private int elapsedSinceReportMs = 0;

    public AtomSpectraAudioSource(Context context, AudioDeviceInfo device, long[] initialHistogram, double initialRecordingTimeSec) {
        this.context = context;
        this.device = device;
        this.histogram = new long[AUDIO_HIST_POINTS];
        if (initialHistogram != null) {
            System.arraycopy(initialHistogram, 0, this.histogram, 0, Math.min(initialHistogram.length, AUDIO_HIST_POINTS));
        }
        this.totalSamplesProcessed = Math.round(Math.max(0, initialRecordingTimeSec) * SAMPLE_RATE);
    }

    @Override
    public void requestConnect() {
        if (this.rejectIfClosed(SpectrumSource.OP_CONNECT)) return;

        if (this.status != SpectrumSource.STATUS_DISCONNECTED) {
            this.emitError(SpectrumSource.OP_CONNECT, SpectrumSource.REASON_ERROR, "Already connected");
            return;
        }

        final Context ctx = this.context;
        if (ctx == null || !AppPermissions.isMicGranted(ctx)) {
            this.emitError(SpectrumSource.OP_CONNECT, SpectrumSource.REASON_ERROR, "Microphone permission not granted");
            return;
        }

        SharedPreferences sp = PrefHelper.getASSharedPreferences(ctx);
        this.frontCountsMin = sp.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT);
        this.frontCountsMax = sp.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT);
        this.histogramMinChannel = sp.getInt(Constants.CONFIG.CONF_NOISE, Constants.NOISE_DISCRIMINATOR_DEFAULT);
        this.inversion = sp.getBoolean(Constants.CONFIG.CONF_INVERSION, Constants.INVERSE_DEFAULT);
        this.pileup = sp.getBoolean(Constants.CONFIG.CONF_PILE_UP, Constants.PILE_UP_DEFAULT);
        this.reportIntervalMs = 1000 / sp.getInt(Constants.CONFIG.CONF_DOSE_UPDATE, Constants.UPDATE_DOSE_DEFAULT);

        this.deviceId = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && this.device != null)
                ? this.device.getProductName().toString()
                : "default";

        if (this.device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.registerDeviceLossCallback(ctx);
        }

        this.setAndEmitStatus(SpectrumSource.STATUS_CONNECTED_IDLE);
        this.emitReady();
    }

    // watches the AudioManager device list for the specific selected device disappearing; armed for the whole
    // connected lifetime (idle and collecting alike), not just while requestStart()/requestStop() bracket capture
    @TargetApi(Build.VERSION_CODES.M)
    private void registerDeviceLossCallback(Context ctx) {
        AudioManager manager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return;

        final int lostDeviceId = this.device.getId();
        this.deviceLossCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                for (AudioDeviceInfo removed : removedDevices) {
                    if (removed.isSource() && removed.getId() == lostDeviceId) {
                        AtomSpectraAudioSource.this.onSelectedDeviceLost();
                        return;
                    }
                }
            }
        };
        manager.registerAudioDeviceCallback(this.deviceLossCallback, null);
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
        if (this.status == SpectrumSource.STATUS_DISCONNECTED || this.status == SpectrumSource.STATUS_CLOSED) return;

        this.stopTimersAndCapture();
        this.status = SpectrumSource.STATUS_DISCONNECTED;
        this.emitDisconnected("Selected audio device disconnected: " + this.deviceId);
    }

    @Override
    public void requestShowData() {
        if (this.rejectIfDisconnected(SpectrumSource.OP_SHOW)) return;
        this.broadcastData();
    }

    @Override
    public void requestStart() {
        if (this.rejectIfDisconnected(SpectrumSource.OP_START)) return;

        this.stopTimersAndCapture(); // resetting just in case, mirrors old startCapturingAudioSource() behavior

        final Context ctx = this.context;
        if (ctx == null) return;

        SharedPreferences sp = PrefHelper.getASSharedPreferences(ctx);
        this.audioSourceMode = AUDIO_SOURCE_VOICE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            boolean useRawAudio = sp.getInt(Constants.CONFIG.CONF_AUDIO_SOURCE, Constants.AUDIO_SOURCE_DEFAULT) == Constants.AUDIO_SOURCE_RAW;
            if (useRawAudio) {
                AudioManager manager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                if (manager != null && manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) != null) {
                    this.audioSourceMode = AUDIO_SOURCE_RAW;
                } else {
                    log(ctx, "Raw (unprocessed) audio source requested but not supported by this device; using voice-recognition source");
                }
            }
        }

        this.bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 2;
        this.audioBytes = new byte[this.bufferSize];
        this.audioData = new int[this.bufferSize / 2];
        this.captureTaskIntervalMs = 1000L * this.bufferSize / 2 / SAMPLE_RATE;
        this.captureElapsedMs = 0;
        this.captureOldElapsedMs = 0;
        this.elapsedSinceReportMs = 0;

        this.reportTimer = new Timer();
        this.reportTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                AtomSpectraAudioSource.this.reportTask();
            }
        }, REPORT_PERIOD_MS, REPORT_PERIOD_MS);

        this.captureTimer = new Timer();
        this.captureTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                AtomSpectraAudioSource.this.captureAudioTask();
            }
        }, 0, this.captureTaskIntervalMs);

        this.setAndEmitStatus(SpectrumSource.STATUS_CONNECTED_COLLECTING);
    }

    @Override
    public void requestStop() {
        if (this.rejectIfDisconnected(SpectrumSource.OP_STOP)) return;
        this.stopTimersAndCapture();
        this.setAndEmitStatus(SpectrumSource.STATUS_CONNECTED_IDLE);
    }

    @Override
    public void requestReset() {
        if (this.rejectIfDisconnected(SpectrumSource.OP_RESET)) return;
        synchronized (this.dataLock) {
            Arrays.fill(this.histogram, 0);
            this.totalSamplesProcessed = 0;
            Arrays.fill(this.cp1sArray, 0);
            this.cpsPos = 0;
            this.countsThisPeriod = 0;
            Arrays.fill(this.referencePulse, 0);
        }
    }

    @Override
    public void requestSaveCalibration(double[] coeffs) {
        if (this.rejectIfDisconnected(SpectrumSource.OP_CALIBRATION_SAVE)) return;
        this.broadcastReply(new Intent(SpectrumSource.ACTION_SOURCE_CALIBRATION_SAVED));
    }

    @Override
    public void close() {
        this.stopTimersAndCapture();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.unregisterDeviceLossCallback();
        }
        this.setAndEmitStatus(SpectrumSource.STATUS_CLOSED);
        this.context = null;
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
    public int channelCount() {
        return AUDIO_HIST_POINTS;
    }

    @Override
    public String deviceId() {
        return this.deviceId;
    }

    @Override
    public double[] calibration() {
        return null;
    }

    /** Latest captured PCM samples, for a live scope view. Zero-filled past what was actually captured. */
    public void getScopeSnapshot(double[] out) {
        synchronized (this.dataLock) {
            int n = Math.min(out.length, this.lastCapturedSamples.length);
            System.arraycopy(this.lastCapturedSamples, 0, out, 0, n);
            Arrays.fill(out, n, out.length, 0);
        }
    }

    /** Cumulative averaged pulse shape, for a reference-pulse view. Zero-filled past what was actually captured. */
    public void getReferencePulse(double[] out) {
        synchronized (this.dataLock) {
            int n = Math.min(out.length, this.referencePulse.length);
            for (int i = 0; i < n; i++) out[i] = this.referencePulse[i];
            Arrays.fill(out, n, out.length, 0);
        }
    }

    private void stopTimersAndCapture() {
        synchronized (this.captureLock) {
            if (this.audioRecord != null) {
                this.audioRecord.stop();
                this.audioRecord.release();
                this.audioRecord = null;
            }
        }
        if (this.reportTimer != null) {
            this.reportTimer.cancel();
            this.reportTimer.purge();
            this.reportTimer = null;
        }
        if (this.captureTimer != null) {
            this.captureTimer.cancel();
            this.captureTimer.purge();
            this.captureTimer = null;
        }
    }

    // this task is used to read from the microphone and update the private histogram/reference pulse; expected to run every ~46ms
    private void captureAudioTask() {
        if (this.context == null) return;

        synchronized (this.captureLock) {
            if (this.audioRecord == null) {
                this.audioRecord = new AudioRecord(this.audioSourceMode, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, this.bufferSize);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    this.audioRecord.setPreferredDevice(this.device);
                }
                if (this.audioRecord.getState() == AudioRecord.STATE_UNINITIALIZED) {
                    this.audioRecord = null;
                } else {
                    try {
                        this.audioRecord.startRecording();
                        log(this.context, "Recording started, device=" + this.deviceId);
                    } catch (IllegalStateException e) {
                        this.audioRecord.release();
                        this.audioRecord = null;
                    }
                }
            }

            if (this.audioRecord != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    this.audioBytesRead = this.audioRecord.read(this.audioBytes, 0, this.bufferSize, AudioRecord.READ_NON_BLOCKING);
                } else {
                    this.audioBytesRead = this.audioRecord.read(this.audioBytes, 0, this.bufferSize);
                }
                if (this.audioBytesRead < 0) {
                    switch (this.audioBytesRead) {
                        case AudioRecord.ERROR_INVALID_OPERATION: // object is not initialized
                        case AudioRecord.ERROR_DEAD_OBJECT:       // object is not accessible now, try to reopen
                        case AudioRecord.ERROR:                   // other errors found
                            this.audioRecord.stop();
                            this.audioRecord.release();
                            this.audioRecord = null;
                            break;
                        case AudioRecord.ERROR_BAD_VALUE:         // error in input parameters, must not happen
                            break;
                    }
                    this.audioBytesRead = 0;
                }
            } else {
                this.audioBytesRead = 0;
            }
        }

        if (this.audioBytesRead == 0) {
            this.audioZeroDataCount++;
            if (this.audioZeroDataCount >= AUDIO_ZERO_DATA_MAX_COUNT) {
                log(this.context, "No audio data received for " + this.audioZeroDataCount + " buffers");
                this.audioZeroDataCount = 0;
                synchronized (this.captureLock) {
                    if (this.audioRecord != null) {
                        this.audioRecord.stop();
                        this.audioRecord.release();
                        this.audioRecord = null;
                    }
                }
            }
            return;
        } else {
            this.audioZeroDataCount = 0;
        }

        // First pass the 2 bytes into one sample - an extra loop, but avoids repeating the same sum many times during the filter below
        int highBitsShift = Math.max(0, 15 - Constants.ADC_EFF_BITS);
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

        synchronized (this.dataLock) {
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
                        if ((channel >= this.histogramMinChannel) && (channel < AUDIO_HIST_POINTS)) {
                            //-----------------DPP finished-------------------------------------------------
                            this.histogram[channel]++;
                            this.countsThisPeriod++;

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
                this.cpsPos = this.cpsPos < (this.cp1sArray.length - 1) ? (this.cpsPos + 1) : 0;
                this.cp1sArray[this.cpsPos] = this.countsThisPeriod;
                this.countsThisPeriod = 0;
            }
        }
    }

    // called every REPORT_PERIOD_MS (100ms); broadcasts the cumulative snapshot every reportIntervalMs
    private void reportTask() {
        if (this.context == null) return;

        this.elapsedSinceReportMs += REPORT_PERIOD_MS;
        if (this.elapsedSinceReportMs >= this.reportIntervalMs) {
            this.elapsedSinceReportMs = 0;
            this.broadcastData();
        }
    }

    private void broadcastData() {
        final Context ctx = this.context;
        if (ctx == null) return;

        long[] histogramCopy;
        int cps;
        double recordingTime;
        synchronized (this.dataLock) {
            histogramCopy = Arrays.copyOf(this.histogram, this.histogram.length);
            cps = 0;
            for (int value : this.cp1sArray) cps += value;
            recordingTime = (double) this.totalSamplesProcessed / SAMPLE_RATE;
        }

        Intent intent = new Intent(SpectrumSource.ACTION_SOURCE_DATA).setPackage(Constants.PACKAGE_NAME);
        intent.putExtra(SpectrumSource.EXTRA_SOURCE_DATA_HISTOGRAM, histogramCopy);
        intent.putExtra(SpectrumSource.EXTRA_SOURCE_DATA_CP1S, cps);
        intent.putExtra(SpectrumSource.EXTRA_SOURCE_DATA_RECORDING_TIME, recordingTime);
        intent.putExtra(SpectrumSource.EXTRA_SOURCE_INPUT_TYPE, this.inputType());
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
        if (this.status == SpectrumSource.STATUS_DISCONNECTED || this.status == SpectrumSource.STATUS_CONNECTING) {
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
                .putExtra(EXTRA_SOURCE_INPUT_TYPE, this.inputType()));
    }

    private void emitDisconnected(String reason) {
        this.broadcastReply(new Intent(SpectrumSource.ACTION_SOURCE_DISCONNECTED)
                .putExtra(SpectrumSource.EXTRA_SOURCE_DISCONNECT_REASON, reason));
    }

    private void emitError(int op, int reason, String text) {
        this.broadcastReply(new Intent(SpectrumSource.ACTION_SOURCE_ERROR)
                .putExtra(SpectrumSource.EXTRA_SOURCE_ERROR_OP, op)
                .putExtra(SpectrumSource.EXTRA_SOURCE_ERROR_REASON, reason)
                .putExtra(SpectrumSource.EXTRA_SOURCE_ERROR_TEXT, text));
    }

    private void setAndEmitStatus(int status) {
        if (this.status != status) {
            this.status = status;
            this.broadcastReply(new Intent(SpectrumSource.ACTION_SOURCE_STATUS)
                    .putExtra(SpectrumSource.EXTRA_SOURCE_STATUS, this.status));
        }
    }

    private void emitReady() {
        this.broadcastReply(new Intent(SpectrumSource.ACTION_SOURCE_READY)
                .putExtra(SpectrumSource.EXTRA_SOURCE_STATUS, this.status)
                .putExtra(SpectrumSource.EXTRA_SOURCE_DEVICE_ID, this.deviceId));
    }
}
