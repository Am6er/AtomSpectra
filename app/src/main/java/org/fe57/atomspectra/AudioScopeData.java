package org.fe57.atomspectra;

/**
 * Raw audio diagnostics: the latest captured samples and the averaged reference pulse. Written
 * only by the audio source (one audio source runs at a time), read by Settings on
 * {@code ACTION_RAW_AUDIO_SNAPSHOT}. Arrays are handed over, not copied: nobody modifies them
 * after publishing.
 */
public final class AudioScopeData {
    public static final AudioScopeData instance = new AudioScopeData();

    private static final double[] EMPTY = new double[0];

    /** Set by a viewer while it is showing the snapshots; the audio source skips the work otherwise. */
    public volatile boolean wanted = false;

    private volatile double[] samples = EMPTY;
    private volatile double[] referencePulse = EMPTY;

    private AudioScopeData() {
    }

    public double[] getSamples() {
        return samples;
    }

    public double[] getReferencePulse() {
        return referencePulse;
    }

    public void set(double[] samples, double[] referencePulse) {
        this.samples = samples;
        this.referencePulse = referencePulse;
    }

    public void reset() {
        set(EMPTY, EMPTY);
    }
}
