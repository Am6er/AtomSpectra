package org.fe57.atomspectra;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MenuItem;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Offline-capable map: bundled Leaflet + Natural Earth countries, measurement
 * data from {@code /map-data/track.json}, optional basemap tiles from
 * {@code /map-data/tiles.json}, and current device position from
 * {@code /map-data/location.json}.
 */
public class AtomSpectraMap extends Activity {
    private static final String TAG = "AtomSpectraMap";
    private static final String ASSET_BASE =
            "https://appassets.androidplatform.net/assets/map/index.html";

    public static final String EXTRA_MAP_MODE = "map_mode";
    public static final int MODE_SPECTRUM = 0;
    public static final int MODE_SPECTROGRAM = 1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private int mapMode = MODE_SPECTRUM;
    private boolean pageReady;
    private boolean receiverRegistered;
    private boolean trackRefreshScheduled;
    private boolean trackRefreshRunning;
    private boolean locationRefreshScheduled;

    private final Runnable trackRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            trackRefreshScheduled = false;
            if (webView == null || !pageReady || mapMode != MODE_SPECTROGRAM) {
                return;
            }
            trackRefreshRunning = true;
            webView.evaluateJavascript(
                    "window.AtomSpectraMapPage && AtomSpectraMapPage.refreshTrack && AtomSpectraMapPage.refreshTrack()",
                    value -> {
                        trackRefreshRunning = false;
                        if (trackRefreshScheduled) {
                            mainHandler.post(trackRefreshRunnable);
                        }
                    });
        }
    };

    private final Runnable locationRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            locationRefreshScheduled = false;
            if (webView == null || !pageReady) {
                return;
            }
            webView.evaluateJavascript(
                    "window.AtomSpectraMapPage && AtomSpectraMapPage.refreshLocation && AtomSpectraMapPage.refreshLocation()",
                    null);
        }
    };

    private final BroadcastReceiver mapReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Constants.ACTION.ACTION_SPECTROGRAM_UPDATED.equals(action)) {
                scheduleTrackRefresh();
            } else if (Constants.ACTION.ACTION_DEVICE_LOCATION_UPDATED.equals(action)) {
                scheduleLocationRefresh();
            }
        }
    };

    public static void openSpectrumMap(Context context) {
        Intent intent = new Intent(context, AtomSpectraMap.class);
        intent.putExtra(EXTRA_MAP_MODE, MODE_SPECTRUM);
        context.startActivity(intent);
    }

    public static void openSpectrogramMap(Context context) {
        Intent intent = new Intent(context, AtomSpectraMap.class);
        intent.putExtra(EXTRA_MAP_MODE, MODE_SPECTROGRAM);
        context.startActivity(intent);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        String lang = PrefHelper.getLocale(newBase);
        super.attachBaseContext(LocaleContextWrapper.wrap(newBase, lang));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_atom_spectra_map);
        setTitle(R.string.map_activity_title);

        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setDisplayHomeAsUpEnabled(true);
            bar.setDisplayShowHomeEnabled(true);
        }

        mapMode = getIntent().getIntExtra(EXTRA_MAP_MODE, MODE_SPECTRUM);
        webView = findViewById(R.id.map_webview);
        setupWebView();
        loadMapPage();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        webView.setBackgroundColor(Color.parseColor("#d7dde5"));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            settings.setAllowFileAccessFromFileURLs(false);
            settings.setAllowUniversalAccessFromFileURLs(false);
        }
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        String baseUa = settings.getUserAgentString();
        settings.setUserAgentString(baseUa + " AtomSpectra/" + BuildConfig.VERSION_NAME);

        webView.addJavascriptInterface(new MapJsBridge(), "AtomSpectraAndroid");

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/map-data/", new MapDataPathHandler())
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null) {
                    return false;
                }
                String host = uri.getHost();
                if ("appassets.androidplatform.net".equals(host)) {
                    return false;
                }
                openExternal(uri);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                webView.evaluateJavascript(
                        "window.AtomSpectraMapPage && AtomSpectraMapPage.invalidateSize && AtomSpectraMapPage.invalidateSize()",
                        null);
                scheduleLocationRefresh();
                if (mapMode == MODE_SPECTROGRAM) {
                    scheduleTrackRefresh();
                }
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        }
    }

    private void loadMapPage() {
        pageReady = false;
        String mode = mapMode == MODE_SPECTROGRAM ? "spectrogram" : "spectrum";
        String centerLabel = Uri.encode(getString(R.string.map_center_location));
        String fitLabel = Uri.encode(getString(R.string.map_fit_track));
        webView.loadUrl(ASSET_BASE + "?mode=" + mode
                + "&centerLabel=" + centerLabel
                + "&fitLabel=" + fitLabel);
    }

    private void snapshotViewState() {
        if (webView == null || !pageReady) {
            return;
        }
        webView.evaluateJavascript(
                "(function(){try{"
                        + "if(!window.AtomSpectraMapPage||!AtomSpectraMapPage.getViewState)return null;"
                        + "var s=AtomSpectraMapPage.getViewState();"
                        + "if(window.AtomSpectraAndroid&&AtomSpectraAndroid.saveViewState&&s)"
                        + "AtomSpectraAndroid.saveViewState(s);"
                        + "return s;"
                        + "}catch(e){return null;}})()",
                value -> MapViewSession.forMode(mapMode).saveFromJsResult(value));
    }

    private void scheduleTrackRefresh() {
        if (mapMode != MODE_SPECTROGRAM || webView == null) {
            return;
        }
        trackRefreshScheduled = true;
        if (trackRefreshRunning) {
            return;
        }
        mainHandler.removeCallbacks(trackRefreshRunnable);
        mainHandler.post(trackRefreshRunnable);
    }

    private void scheduleLocationRefresh() {
        if (webView == null) {
            return;
        }
        locationRefreshScheduled = true;
        mainHandler.removeCallbacks(locationRefreshRunnable);
        mainHandler.post(locationRefreshRunnable);
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No app to open " + uri, e);
        }
    }

    private void registerMapReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.ACTION.ACTION_DEVICE_LOCATION_UPDATED);
        if (mapMode == MODE_SPECTROGRAM) {
            filter.addAction(Constants.ACTION.ACTION_SPECTROGRAM_UPDATED);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(mapReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mapReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterMapReceiver() {
        if (!receiverRegistered) {
            return;
        }
        unregisterReceiver(mapReceiver);
        receiverRegistered = false;
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerMapReceiver();
        scheduleLocationRefresh();
    }

    @Override
    protected void onStop() {
        unregisterMapReceiver();
        mainHandler.removeCallbacks(trackRefreshRunnable);
        mainHandler.removeCallbacks(locationRefreshRunnable);
        trackRefreshScheduled = false;
        locationRefreshScheduled = false;
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        snapshotViewState();
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(trackRefreshRunnable);
        mainHandler.removeCallbacks(locationRefreshRunnable);
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private final class MapJsBridge {
        @JavascriptInterface
        public void saveViewState(@Nullable String json) {
            MapViewSession.forMode(mapMode).saveFromJsResult(json);
        }
    }

    private final class MapDataPathHandler implements WebViewAssetLoader.PathHandler {
        @Nullable
        @Override
        public WebResourceResponse handle(@NonNull String path) {
            if ("tiles.json".equals(path)) {
                return jsonResponse(buildTilesJson());
            }
            if ("track.json".equals(path)) {
                return jsonResponse(buildTrackJson());
            }
            if ("location.json".equals(path)) {
                return jsonResponse(buildLocationJson());
            }
            if ("view-state.json".equals(path)) {
                return jsonResponse(buildViewStateJson());
            }
            return null;
        }

        @Nullable
        private byte[] buildViewStateJson() {
            try {
                return MapViewSession.forMode(mapMode).toJsonBytes();
            } catch (IOException e) {
                Log.e(TAG, "Failed to build view-state payload", e);
                return null;
            }
        }

        @Nullable
        private byte[] buildTrackJson() {
            try {
                return MapPayload.build(mapMode);
            } catch (IOException e) {
                Log.e(TAG, "Failed to build map payload", e);
                return null;
            }
        }

        @Nullable
        private byte[] buildTilesJson() {
            try {
                return MapTileProviders.toJson(MapTileProviders.resolve(AtomSpectraMap.this));
            } catch (IOException e) {
                Log.e(TAG, "Failed to build tiles config", e);
                return null;
            }
        }

        @Nullable
        private byte[] buildLocationJson() {
            try {
                return AtomSpectraService.getDeviceLocationSnapshot().toJson();
            } catch (IOException e) {
                Log.e(TAG, "Failed to build location payload", e);
                return null;
            }
        }

        @NonNull
        private WebResourceResponse jsonResponse(@Nullable byte[] json) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            if (json == null) {
                return new WebResourceResponse(
                        "application/json",
                        "UTF-8",
                        500,
                        "Internal Error",
                        headers,
                        new ByteArrayInputStream("{\"error\":\"payload\"}".getBytes(StandardCharsets.UTF_8)));
            }
            return new WebResourceResponse(
                    "application/json",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    new ByteArrayInputStream(json));
        }
    }
}
