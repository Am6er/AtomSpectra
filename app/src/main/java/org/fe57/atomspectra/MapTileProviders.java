package org.fe57.atomspectra;

import android.content.Context;
import android.util.JsonWriter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Map basemap tile providers. Selection is stored in preferences so the URL is not
 * hard-coded in the WebView JavaScript.
 */
public final class MapTileProviders {
    public static final String ID_NONE = "none";
    public static final String ID_OSM = "osm";
    public static final String ID_CUSTOM = "custom";

    public static final String DEFAULT_PROVIDER_ID = ID_OSM;

    /** Built-in providers shown in settings (custom URL support is kept in code but hidden for now). */
    public static final String[] SETTINGS_PROVIDER_IDS = {
            ID_NONE,
            ID_OSM,
    };

    private static final String OSM_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    private static final String OSM_ATTRIBUTION =
            "&copy; <a href=\"https://www.openstreetmap.org/copyright\" target=\"_blank\" rel=\"noopener\">OpenStreetMap</a>";

    public static final class Config {
        public final String providerId;
        public final boolean enabled;
        @Nullable
        public final String urlTemplate;
        @NonNull
        public final String attributionHtml;
        public final int maxZoom;
        @NonNull
        public final String subdomains;

        private Config(String providerId, boolean enabled, @Nullable String urlTemplate,
                       @NonNull String attributionHtml, int maxZoom, @NonNull String subdomains) {
            this.providerId = providerId;
            this.enabled = enabled;
            this.urlTemplate = urlTemplate;
            this.attributionHtml = attributionHtml;
            this.maxZoom = maxZoom;
            this.subdomains = subdomains;
        }
    }

    private MapTileProviders() {
    }

    public static int indexOfProvider(@Nullable String providerId) {
        for (int i = 0; i < SETTINGS_PROVIDER_IDS.length; i++) {
            if (SETTINGS_PROVIDER_IDS[i].equals(providerId)) {
                return i;
            }
        }
        // Hidden/unknown ids (e.g. custom) fall back to the default shown option.
        return indexOfProvider(DEFAULT_PROVIDER_ID);
    }

    public static String displayName(@NonNull Context context, @Nullable String providerId) {
        if (ID_NONE.equals(providerId)) {
            return context.getString(R.string.map_tile_provider_none);
        }
        if (ID_CUSTOM.equals(providerId)) {
            return context.getString(R.string.map_tile_provider_custom);
        }
        return context.getString(R.string.map_tile_provider_osm);
    }

    @NonNull
    public static Config resolve(@NonNull Context context) {
        String providerId = PrefHelper.getMapTileProviderId(context);
        if (ID_NONE.equals(providerId)) {
            return disabled(ID_NONE);
        }
        if (ID_CUSTOM.equals(providerId)) {
            String url = PrefHelper.getMapTileCustomUrl(context);
            if (!isUsableTileUrl(url)) {
                return disabled(ID_CUSTOM);
            }
            String attribution = PrefHelper.getMapTileCustomAttribution(context);
            if (attribution == null || attribution.trim().isEmpty()) {
                attribution = OSM_ATTRIBUTION;
            }
            return new Config(ID_CUSTOM, true, url.trim(), attribution, 19, "");
        }
        // Default / unknown → OSM
        return new Config(ID_OSM, true, OSM_URL, OSM_ATTRIBUTION, 19, "");
    }

    private static Config disabled(String providerId) {
        return new Config(providerId, false, null, "", 19, "");
    }

    static boolean isUsableTileUrl(@Nullable String url) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("https://")) {
            return false;
        }
        return trimmed.contains("{z}") && trimmed.contains("{x}") && trimmed.contains("{y}");
    }

    static byte[] toJson(@NonNull Config config) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        JsonWriter writer = new JsonWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.beginObject();
        writer.name("providerId").value(config.providerId);
        writer.name("enabled").value(config.enabled);
        if (config.urlTemplate != null) {
            writer.name("urlTemplate").value(config.urlTemplate);
        } else {
            writer.name("urlTemplate").nullValue();
        }
        writer.name("attribution").value(config.attributionHtml);
        writer.name("maxZoom").value(config.maxZoom);
        writer.name("subdomains").value(config.subdomains);
        writer.endObject();
        writer.close();
        return out.toByteArray();
    }
}
