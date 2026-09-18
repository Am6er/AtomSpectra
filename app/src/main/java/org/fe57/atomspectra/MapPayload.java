package org.fe57.atomspectra;

import android.util.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds the compact JSON payload served at {@code /map-data/track.json}.
 * Point tuples are {@code [longitude, latitude, cps, timestamp]}.
 */
final class MapPayload {
    private MapPayload() {
    }

    static byte[] build(int mapMode) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        JsonWriter writer = new JsonWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.beginObject();
        if (mapMode == AtomSpectraMap.MODE_SPECTROGRAM) {
            writeSpectrogram(writer);
        } else {
            writeSpectrum(writer);
        }
        writer.endObject();
        writer.close();
        return out.toByteArray();
    }

    private static void writeSpectrum(JsonWriter writer) throws IOException {
        Spectrum spectrum = AtomSpectraService.ForegroundSpectrum;
        writer.name("mode").value("spectrum");
        writer.name("recordingId").value("");
        writer.name("rowCount").value(1);
        writer.name("tracks");
        writer.beginArray();
        if (MapHelper.hasValidSpectrumLocation(spectrum)) {
            writer.beginArray();
            writePoint(writer, spectrum.getLongitude(), spectrum.getLatitude(), 0f, spectrum.getSpectrumDate());
            writer.endArray();
        }
        writer.endArray();
    }

    private static void writeSpectrogram(JsonWriter writer) throws IOException {
        AtomSpectraSpectrogramData.MapTrackSnapshot snapshot =
                AtomSpectraSpectrogramData.instance.getMapSnapshot();
        int rowCount = 0;
        for (List<MapPoint> track : snapshot.tracks) {
            rowCount += track.size();
        }

        writer.name("mode").value("spectrogram");
        writer.name("recordingId").value(snapshot.recordingId != null ? snapshot.recordingId : "");
        writer.name("rowCount").value(rowCount);
        writer.name("tracks");
        writer.beginArray();
        for (List<MapPoint> track : snapshot.tracks) {
            writer.beginArray();
            for (MapPoint point : track) {
                writePoint(writer, point.longitude, point.latitude, point.totalCps, point.timestamp);
            }
            writer.endArray();
        }
        writer.endArray();
    }

    private static void writePoint(JsonWriter writer, double lon, double lat, float cps, long timestamp)
            throws IOException {
        writer.beginArray();
        writer.value(lon);
        writer.value(lat);
        writer.value(cps);
        writer.value(timestamp);
        writer.endArray();
    }
}
