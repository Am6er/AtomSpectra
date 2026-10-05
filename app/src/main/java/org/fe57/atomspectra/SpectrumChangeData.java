package org.fe57.atomspectra;

/**
 * Shared spectrum-change result computed by the service: the counts gained over a short and a
 * long sliding window. Readers take one immutable {@link Snapshot} per render.
 */
public final class SpectrumChangeData {
    public static final SpectrumChangeData instance = new SpectrumChangeData();

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    private SpectrumChangeData() {
    }

    /**
     * Store the result of a spectrum-change calculation. The arrays are handed over, not copied:
     * the caller must not modify them afterwards.
     */
    public void set(long[] foregroundDelta, long[] backgroundDelta,
                    int foregroundTimeSeconds, int backgroundTimeSeconds) {
        long foregroundCounts = 0;
        long backgroundCounts = 0;
        for (int i = 0; i < foregroundDelta.length; i++) {
            foregroundCounts += foregroundDelta[i];
            backgroundCounts += backgroundDelta[i];
        }
        snapshot = new Snapshot(foregroundDelta, backgroundDelta,
                foregroundCounts, backgroundCounts, foregroundTimeSeconds, backgroundTimeSeconds);
    }

    public Snapshot get() {
        return snapshot;
    }

    public void reset() {
        snapshot = Snapshot.EMPTY;
    }

    /**
     * Immutable result. Foreground is the change over the short window, background over the long
     * one. Time is zero while the window holds no change yet.
     */
    public static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(new long[0], new long[0], 0, 0, 0, 0);

        public final long[] foregroundDelta;
        public final long[] backgroundDelta;
        public final long foregroundTotalCounts;
        public final long backgroundTotalCounts;
        public final int foregroundTimeSeconds;
        public final int backgroundTimeSeconds;

        private Snapshot(long[] foregroundDelta, long[] backgroundDelta,
                         long foregroundTotalCounts, long backgroundTotalCounts,
                         int foregroundTimeSeconds, int backgroundTimeSeconds) {
            this.foregroundDelta = foregroundDelta;
            this.backgroundDelta = backgroundDelta;
            this.foregroundTotalCounts = foregroundTotalCounts;
            this.backgroundTotalCounts = backgroundTotalCounts;
            this.foregroundTimeSeconds = foregroundTimeSeconds;
            this.backgroundTimeSeconds = backgroundTimeSeconds;
        }

        public boolean isEmpty() {
            return foregroundDelta.length == 0 || backgroundTimeSeconds <= 0;
        }
    }
}
