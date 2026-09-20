package org.fe57.atomspectra;

import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/**
 * In-process map UI session: camera, selection, and display modes.
 * Survives Activity finish; cleared on process death or explicit {@link #clear()}.
 * Spectrum and spectrogram keep separate sessions.
 */
final class MapViewSession {
    private static final String TAG = "MapViewSession";

    private static final MapViewSession SPECTRUM = new MapViewSession();
    private static final MapViewSession SPECTROGRAM = new MapViewSession();

    private boolean valid;
    private String recordingId = "";
    private double lat;
    private double lon;
    private double zoom;
    private boolean hasSelection;
    private long selectionTs;
    private double selectionLat;
    private double selectionLon;
    private String decimation = "max";
    private String scale = "log1p";
    private String clip = "p99";
    private double colorBarMinFraction;
    private double colorBarMaxFraction = 1.0;

    private MapViewSession() {
    }

    @NonNull
    static MapViewSession forMode(int mapMode) {
        return mapMode == AtomSpectraMap.MODE_SPECTROGRAM ? SPECTROGRAM : SPECTRUM;
    }

    synchronized void clear() {
        valid = false;
        recordingId = "";
        hasSelection = false;
    }

    /**
     * Parse JSON from {@code AtomSpectraMapPage.getViewState()} (raw object JSON
     * or an {@code evaluateJavascript} quoted string wrapper) and store it.
     */
    synchronized void saveFromJsResult(@Nullable String jsResult) {
        if (jsResult == null || jsResult.isEmpty() || "null".equals(jsResult)) {
            return;
        }
        String json = jsResult;
        if (json.length() >= 2 && json.charAt(0) == '"') {
            try {
                JsonReader unquote = new JsonReader(new StringReader(json));
                unquote.setLenient(true);
                json = unquote.nextString();
                unquote.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to unquote view state", e);
                return;
            }
        }
        if (json == null || json.isEmpty() || "null".equals(json)) {
            return;
        }
        try {
            parseAndStore(json);
        } catch (IOException e) {
            Log.w(TAG, "Failed to parse view state", e);
        }
    }

    private void parseAndStore(String json) throws IOException {
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.beginObject();
        boolean gotCamera = false;
        String rid = "";
        double cLat = 0;
        double cLon = 0;
        double cZoom = 0;
        boolean sel = false;
        long selTs = 0;
        double selLat = 0;
        double selLon = 0;
        String dec = "max";
        String scl = "log1p";
        String clp = "p99";
        double minF = 0;
        double maxF = 1;
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case "recordingId":
                    rid = reader.nextString();
                    break;
                case "lat":
                    cLat = reader.nextDouble();
                    gotCamera = true;
                    break;
                case "lon":
                    cLon = reader.nextDouble();
                    gotCamera = true;
                    break;
                case "zoom":
                    cZoom = reader.nextDouble();
                    gotCamera = true;
                    break;
                case "decimation":
                    dec = reader.nextString();
                    break;
                case "scale":
                    scl = reader.nextString();
                    break;
                case "clip":
                    clp = reader.nextString();
                    break;
                case "colorBarMinFraction":
                    minF = reader.nextDouble();
                    break;
                case "colorBarMaxFraction":
                    maxF = reader.nextDouble();
                    break;
                case "selection":
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull();
                    } else {
                        reader.beginObject();
                        while (reader.hasNext()) {
                            String sn = reader.nextName();
                            switch (sn) {
                                case "ts":
                                    selTs = reader.nextLong();
                                    sel = true;
                                    break;
                                case "lat":
                                    selLat = reader.nextDouble();
                                    sel = true;
                                    break;
                                case "lon":
                                    selLon = reader.nextDouble();
                                    sel = true;
                                    break;
                                default:
                                    reader.skipValue();
                                    break;
                            }
                        }
                        reader.endObject();
                    }
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.endObject();
        reader.close();

        if (!gotCamera || !Double.isFinite(cLat) || !Double.isFinite(cLon) || !Double.isFinite(cZoom)) {
            return;
        }
        if (cZoom < 1 || cZoom > 22) {
            return;
        }
        if (!MapHelper.isFiniteInRange(cLat, cLon)) {
            return;
        }

        this.valid = true;
        this.recordingId = rid != null ? rid : "";
        this.lat = cLat;
        this.lon = cLon;
        this.zoom = cZoom;
        this.hasSelection = sel;
        this.selectionTs = selTs;
        this.selectionLat = selLat;
        this.selectionLon = selLon;
        this.decimation = dec != null ? dec : "max";
        this.scale = scl != null ? scl : "log1p";
        this.clip = clp != null ? clp : "p99";
        this.colorBarMinFraction = clamp01(minF);
        this.colorBarMaxFraction = clamp01(maxF);
        if (this.colorBarMaxFraction - this.colorBarMinFraction < 0.01) {
            this.colorBarMinFraction = 0;
            this.colorBarMaxFraction = 1;
        }
    }

    private static double clamp01(double v) {
        if (!Double.isFinite(v)) {
            return 0;
        }
        if (v < 0) {
            return 0;
        }
        if (v > 1) {
            return 1;
        }
        return v;
    }

    /** JSON for {@code /map-data/view-state.json}, or {@code {"valid":false}}. */
    @NonNull
    synchronized byte[] toJsonBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        JsonWriter writer = new JsonWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.beginObject();
        writer.name("valid").value(valid);
        if (valid) {
            writer.name("recordingId").value(recordingId);
            writer.name("lat").value(lat);
            writer.name("lon").value(lon);
            writer.name("zoom").value(zoom);
            writer.name("decimation").value(decimation);
            writer.name("scale").value(scale);
            writer.name("clip").value(clip);
            writer.name("colorBarMinFraction").value(colorBarMinFraction);
            writer.name("colorBarMaxFraction").value(colorBarMaxFraction);
            if (hasSelection) {
                writer.name("selection");
                writer.beginObject();
                writer.name("ts").value(selectionTs);
                writer.name("lat").value(selectionLat);
                writer.name("lon").value(selectionLon);
                writer.endObject();
            } else {
                writer.name("selection").nullValue();
            }
        }
        writer.endObject();
        writer.close();
        return out.toByteArray();
    }
}
