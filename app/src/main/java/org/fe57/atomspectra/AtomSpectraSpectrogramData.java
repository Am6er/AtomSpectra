package org.fe57.atomspectra;

import android.net.Uri;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class AtomSpectraSpectrogramData {
    public static final AtomSpectraSpectrogramData instance = new AtomSpectraSpectrogramData();
    public static final int CHANNEL_COUNT = 1024; // must be 2^n and less then 8192
    public static final int MAX_ROWS = 25000;

    // A segment groups rows recorded under a single base spectrum (calibration + device
    // info) and backing file. addDelta() always appends to the latest segment; a new
    // segment is only ever created explicitly via addSegment(), which appends rather than
    // replacing existing data.
    private static class Segment {
        private final Spectrum baseSpectrum;
        private final Uri spectrogramFileName;
        private final ArrayList<float[]> spectrogram = new ArrayList<>();
        private final ArrayList<Long> timestamps = new ArrayList<>();
        private final ArrayList<Float> durations = new ArrayList<>();
        // Count of rows evicted from the front of this segment's in-memory arrays via
        // MAX_ROWS ring-buffer truncation. In-memory row 0 corresponds to file-local delta
        // index evictedRowCount, not 0, once eviction has happened - callers translating an
        // in-memory row position back into a file-local delta index (e.g. export) must add
        // this offset.
        private int evictedRowCount = 0;

        private Segment(Spectrum baseSpectrum, Uri spectrogramFileName) {
            this.baseSpectrum = baseSpectrum;
            this.spectrogramFileName = spectrogramFileName;
        }

        private int rowCount() {
            return this.spectrogram.size();
        }

        // Start/end of this segment's time range, used to chronologically order segments
        // loaded from multiple files and to detect overlaps between them. The base
        // spectrum's own date is the segment's start (recorded before any delta); the last
        // row's timestamp is its end. Falls back to the base spectrum's date if there are
        // no rows yet.
        private long startTime() {
            return baseSpectrum != null ? baseSpectrum.getSpectrumDate() : (timestamps.isEmpty() ? 0 : timestamps.get(0));
        }

        private long endTime() {
            return timestamps.isEmpty() ? startTime() : timestamps.get(timestamps.size() - 1);
        }
    }

    private final Object spectrogramSync = new Object();
    private final ArrayList<Segment> segments = new ArrayList<>();

    private String recordingId = java.util.UUID.randomUUID().toString();

    public String getRecordingId() {
        return recordingId;
    }

    public Uri getSpectrogramFileName() {
        synchronized (spectrogramSync) {
            Segment last = lastSegment();
            return last == null ? null : last.spectrogramFileName;
        }
    }

    /**
     * Starts a new segment with its own base spectrum (calibration + device info) and
     * backing file, and makes it the target for subsequent addDelta() calls. Appends to
     * the segment list; never clears or replaces previously recorded segments. Callers
     * that want a fresh view (e.g. loading a file to replace the current view) must call
     * clear() first.
     */
    public void addSegment(Spectrum baseSpectrum, Uri spectrogramFileName) {
        synchronized (spectrogramSync) {
            segments.add(new Segment(baseSpectrum, spectrogramFileName));
        }
    }

    /**
     * Sorts segments chronologically by start time. Intended for multi-file loads, where
     * addSegment() is called once per file in pick order (not necessarily time order).
     * Rejects (throws, leaving the segment list untouched) if any two segments' time
     * ranges overlap, rather than silently interleaving or reordering them.
     */
    public void sortSegmentsRejectOverlap() {
        synchronized (spectrogramSync) {
            List<Segment> sorted = new ArrayList<>(segments);
            Collections.sort(sorted, (s1, s2) -> Long.compare(s1.startTime(), s2.startTime()));

            for (int i = 1; i < sorted.size(); i++) {
                Segment previous = sorted.get(i - 1);
                Segment current = sorted.get(i);
                if (current.startTime() <= previous.endTime()) {
                    throw new InvalidParameterException(String.format(
                            "Overlapping spectrogram segments: %s and %s",
                            previous.spectrogramFileName, current.spectrogramFileName));
                }
            }

            segments.clear();
            segments.addAll(sorted);
        }
    }

    private Segment lastSegment() {
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }

    public void addDelta(Spectrum delta) {
        long[] channels = delta.getDataArray();
        double duration = delta.getRealSpectrumTime();
        long timestamp = delta.getSpectrumDate();
        
        this.addDelta(channels, duration, timestamp);
    }

    public void addDelta(long[] channels, double duration, long timestamp) {
        if (duration <= 0) {
            duration = 1;
        }

        int channelBinning = channels.length / CHANNEL_COUNT;
        if (channelBinning < 1) {
            throw new IllegalArgumentException("Unsupported channels array length: " + channels.length);
        }

        float[] binnedCpsData = new float[CHANNEL_COUNT];
        for (int i = 0; i < channels.length; i += channelBinning) {
            long summ = 0;
            for (int j = 0; j < channelBinning && (i + j) < channels.length; j++) {
                summ += channels[i + j];
            }

            binnedCpsData[i / channelBinning] = (float) (summ / duration);
        }

        synchronized (spectrogramSync) {
            Segment current = lastSegment();
            if (current == null) {
                throw new IllegalStateException("addDelta() called before addSegment()");
            }

            current.spectrogram.add(binnedCpsData);
            current.durations.add((float) duration);
            current.timestamps.add(timestamp);

            if (this.rowCountLocked() > MAX_ROWS) {
                removeOldestRowLocked();
            }
        }
    }

    // Removes the single globally-oldest row (always row 0 of the first segment, since
    // segments are appended in time order). Drops the first segment entirely once it runs
    // out of rows, unless it is the only (i.e. current) segment.
    private void removeOldestRowLocked() {
        Segment oldest = segments.get(0);
        oldest.spectrogram.remove(0);
        oldest.durations.remove(0);
        oldest.timestamps.remove(0);
        oldest.evictedRowCount++;

        if (oldest.rowCount() == 0 && segments.size() > 1) {
            segments.remove(0);
        }
    }

    public int rowCount() {
        synchronized (spectrogramSync) {
            return rowCountLocked();
        }
    }

    private int rowCountLocked() {
        int total = 0;
        for (Segment segment : segments) {
            total += segment.rowCount();
        }
        return total;
    }

    public void clear() {
        synchronized (spectrogramSync) {
            segments.clear();
            this.recordingId = java.util.UUID.randomUUID().toString();
        }
    }

    // read-only snapshot of one segment's data
    public static final class SegmentData {
        private final Segment segment;

        private SegmentData(Segment segment) {
            this.segment = segment;
        }

        public ArrayList<float[]> getSpectrogram() {
            return new ArrayList<>(segment.spectrogram);
        }

        public ArrayList<Long> getTimestamps() {
            return new ArrayList<>(segment.timestamps);
        }

        public ArrayList<Float> getDurations() {
            return new ArrayList<>(segment.durations);
        }

        public Spectrum getBaseSpectrum() {
            return this.segment.baseSpectrum;
        }

        public int getRowCount() {
            return segment.spectrogram.size();
        }

        public long getStartTime() {
            return this.segment.baseSpectrum.getSpectrumDate();
        }

        public long getEndTime() {
            if (this.segment.timestamps.isEmpty()) {
                return this.getStartTime();
            } else {
                return this.segment.timestamps.get(this.segment.timestamps.size() - 1);
            }
        }
    }

    public SegmentData baseSegment() {
        return segments.isEmpty() ? null : new SegmentData(segments.get(0));
    }

    public List<SegmentData> getSegments() {
        synchronized (spectrogramSync) {
            List<SegmentData> result = new ArrayList<>();
            for (Segment segment : segments) {
                result.add(new SegmentData(segment));
            }

            return result;
        }
    }

    public double[] averageSpectrum(int bound1Segment, int bound1Row, int bound2Segment, int bound2Row) {
        synchronized (spectrogramSync) {
            int rowCount = rowCountLocked();
            if (rowCount == 0) {
                return null;
            }

            int fromSegment = Math.max(0, Math.min(bound1Segment, bound2Segment));
            int toSegment = Math.min(segments.size() - 1, Math.max(bound1Segment, bound2Segment));
            if (fromSegment > toSegment) {
                // TODO: throw?
                return null;
            }

            int fromRow;
            int toRow;
            if (fromSegment == toSegment) {
                Segment segment = segments.get(fromSegment);
                fromRow = Math.max(0, Math.min(bound1Row, bound2Row));
                toRow = Math.min(segment.rowCount() - 1, Math.max(bound1Segment, bound2Segment));
                if (fromRow > toRow) {
                    // TODO: throw?
                    return null;
                }
            } else {
                Segment from = segments.get(fromSegment);
                fromRow = Math.max(0, fromSegment == bound1Segment ? bound1Row : bound2Row);
                fromRow = Math.min(from.rowCount() - 1, fromRow);

                Segment to = segments.get(toSegment);
                toRow = Math.max(0, toSegment == bound1Segment ? bound1Row : bound2Row);
                toRow = Math.min(to.rowCount() - 1, toRow);
            }

            double[] result = new double[CHANNEL_COUNT];
            double totalDuration = 0;
            for (int s = fromSegment; s <= toSegment; s++) {
                Segment segment = segments.get(s);
                int fromSegmentRow = s == fromSegment
                        ? fromRow
                        : 0;
                int toSegmentRow = s == toSegment
                        ? toRow
                        : segment.rowCount() - 1;
                for (int i = fromSegmentRow; i < toSegmentRow; i++) {
                    double duration = segment.durations.get(i);
                    float[] row = segment.spectrogram.get(i);
                    totalDuration += duration;
                    for (int k = 0; k < CHANNEL_COUNT; k++) {
                        result[k] += row[k] * duration;
                    }
                }
            }

            if (totalDuration <= 0) {
                return null;
            }

            for (int k = 0; k < CHANNEL_COUNT; k++) {
                result[k] /= totalDuration;
            }

            return result;
        }
    }

    public double channelToEnergy(int channel) {
        synchronized (spectrogramSync) {
            Segment last = lastSegment();
            if (last == null || last.baseSpectrum == null) {
                return 0;
            }
            int channelBinning = last.baseSpectrum.getDataArray().length / CHANNEL_COUNT;
            int originalChannel = channel * channelBinning + (channelBinning - 1);

            return last.baseSpectrum.getSpectrumCalibration().toEnergy(originalChannel);
        }
    }

    // slice of a single segment's own backing file, expressed in file indices
    public static final class SegmentExportRange {
        public final Uri spectrogramFileName;
        public final int fileFromDelta;
        public final int fileToDelta;

        private SegmentExportRange(Uri spectrogramFileName, int fileFromDelta, int fileToDelta) {
            this.spectrogramFileName = spectrogramFileName;
            this.fileFromDelta = fileFromDelta;
            this.fileToDelta = fileToDelta;
        }
    }

    /**
     * Resolves a global (flat, cross-segment) row-selection range into an ordered list of
     * per-segment file ranges, one entry per segment overlapping [globalFrom, globalTo].
     * A selection spanning a gap therefore yields multiple entries, each pointing at a
     * different segment's own backing file with that segment's local delta indices.
     * Segments are walked in list (chronological) order, so the returned list is
     * chronologically ordered. Returns an empty list if there is no data or an
     * empty/invalid range.
     */
    public List<SegmentExportRange> resolveExportRanges(int bound1Segment, int bound1Row, int bound2Segment, int bound2Row) {
        synchronized (spectrogramSync) {
            List<SegmentExportRange> ranges = new ArrayList<>();

            int rowCount = rowCountLocked();
            if (rowCount == 0) {
                return ranges;
            }

            int fromSegment = Math.max(0, Math.min(bound1Segment, bound2Segment));
            int toSegment = Math.min(segments.size() - 1, Math.max(bound1Segment, bound2Segment));
            if (fromSegment > toSegment) {
                // TODO: throw?
                return ranges;
            }

            int fromRow;
            int toRow;
            if (fromSegment == toSegment) {
                Segment segment = segments.get(fromSegment);
                fromRow = Math.max(0, Math.min(bound1Row, bound2Row));
                toRow = Math.min(segment.rowCount() - 1, Math.max(bound1Segment, bound2Segment));
                if (fromRow > toRow) {
                    // TODO: throw?
                    return ranges;
                }
            } else {
                Segment from = segments.get(fromSegment);
                fromRow = Math.max(0, fromSegment == bound1Segment ? bound1Row : bound2Row);
                fromRow = Math.min(from.rowCount() - 1, fromRow);

                Segment to = segments.get(toSegment);
                toRow = Math.max(0, toSegment == bound1Segment ? bound1Row : bound2Row);
                toRow = Math.min(to.rowCount() - 1, toRow);
            }

            for (int s = fromSegment; s <= toSegment; s++) {
                Segment segment = segments.get(s);
                int fromSegmentRow = s == fromSegment
                        ? fromRow
                        : 0;
                int toSegmentRow = s == toSegment
                        ? toRow
                        : segment.rowCount() - 1;
                if (segment.rowCount() > 0) {
                    // In-memory row 0 of first segment may no longer be file-local delta
                    // 0 if earlier rows were evicted (MAX_ROWS truncation)
                    ranges.add(new SegmentExportRange(segment.spectrogramFileName,
                            fromSegmentRow + s == fromSegment ? segment.evictedRowCount : 0,
                            toSegmentRow + s == fromSegment ? segment.evictedRowCount : 0));
                }
            }

            return ranges;
        }
    }
}

