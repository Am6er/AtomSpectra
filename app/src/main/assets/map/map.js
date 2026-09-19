(function () {
  "use strict";

  var params = new URLSearchParams(window.location.search);
  var mode = params.get("mode") === "spectrogram" ? "spectrogram" : "spectrum";
  var centerLabel = params.get("centerLabel") || "My location";

  L.Icon.Default.imagePath = "leaflet/images/";

  var map = L.map("map", {
    center: [20, 0],
    zoom: 2,
    minZoom: 1,
    maxZoom: 18,
    zoomControl: true,
    attributionControl: true,
  });

  map.attributionControl.setPrefix(false);
  map.attributionControl.addAttribution(
    '<a href="https://leafletjs.com" target="_blank" rel="noopener">Leaflet</a>' +
      ' | Made with <a href="https://www.naturalearthdata.com" target="_blank" rel="noopener">Natural Earth</a>'
  );

  var graticuleLayer = L.layerGroup().addTo(map);
  var countryLayer = null;
  var tileLayer = null;
  var deviceMarker = null;
  var deviceAccuracy = null;
  var deviceLatLng = null;
  var countryLabelEl = document.getElementById("country-label");
  var legendEl = document.getElementById("cps-legend");
  var legendColorbarEl = legendEl.querySelector(".cps-legend-colorbar");
  var legendMinEl = legendEl.querySelector(".cps-legend-min");
  var legendMaxEl = legendEl.querySelector(".cps-legend-max");
  var legendSwatchWrapEl = legendEl.querySelector(".cps-legend-swatch-wrap");
  var legendSwatchEl = legendEl.querySelector(".swatch");
  var legendHandleMinEl = legendEl.querySelector(".cps-legend-handle-min");
  var legendHandleMaxEl = legendEl.querySelector(".cps-legend-handle-max");
  var decimationModeBtn = document.getElementById("decimation-mode");
  var popupEl = document.getElementById("point-popup");
  var centerBtn = document.getElementById("center-location");
  var measurementFocus = null; // { lat, lon } for country label preference
  var trackLayer = null;
  var initialFitDone = false;
  var colorBarMinFraction = 0;
  var colorBarMaxFraction = 1;
  var COLOR_BAR_MIN_SPAN = 0.01;
  var activeColorScale = null;
  var legendDragHandle = null; // "min" | "max" | null

  var TRACK_POINT_FILL_ALPHA = 0.82;
  var TRACK_POINT_STROKE = "#111111";
  var TRACK_POINT_STROKE_WIDTH = 2.5;
  var TRACK_POINT_STROKE_OUTER = "rgba(255, 255, 255, 0.95)";
  var TRACK_POINT_STROKE_OUTER_WIDTH = 5;
  // Fixed screen-pixel size for spectrum and spectrogram (not scaled by zoom).
  var TRACK_POINT_RADIUS = 10;
  var TRACK_POINT_HIT_RADIUS = 34;
  var MAX_DRAWN_POINTS = 2500;
  var DECIMATION_MODES = ["max", "min", "avg"];
  // Per-cell aggregate when decimating: "max" | "min" | "avg"
  var DECIMATION_MODE = "max";
  // Below this zoom, world cells shrink so zoomed-out tracks stay denser.
  var DECIMATION_REF_ZOOM = 15;
  // Base world cell at/above REF_ZOOM (~point radius → ~2x denser than diameter).
  var DECIMATION_BASE_CELL = TRACK_POINT_RADIUS;
  var DECIMATION_MIN_CELL = 2;
  var refreshInFlight = null;
  var refreshQueued = false;

  var PALETTE = [
    [15, 85, 185],
    [40, 210, 160],
    [255, 235, 55],
    [255, 155, 30],
    [230, 30, 35],
  ];

  function countryStyle(tilesEnabled) {
    if (tilesEnabled) {
      return {
        color: "#4a5562",
        weight: 1,
        opacity: 0.75,
        fillColor: "#eef2f6",
        fillOpacity: 0.12,
      };
    }
    return {
      color: "#4a5562",
      weight: 1,
      opacity: 0.9,
      fillColor: "#eef2f6",
      fillOpacity: 0.85,
    };
  }

  function applyCountryStyle(tilesEnabled) {
    if (countryLayer) {
      countryLayer.setStyle(countryStyle(tilesEnabled));
    }
  }

  function applyTileConfig(config) {
    if (tileLayer) {
      map.removeLayer(tileLayer);
      tileLayer = null;
    }
    var enabled = !!(config && config.enabled && config.urlTemplate);
    if (!enabled) {
      applyCountryStyle(false);
      return;
    }
    var options = {
      attribution: config.attribution || "",
      maxZoom: config.maxZoom || 19,
      opacity: 1,
      errorTileUrl:
        "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7",
      crossOrigin: true,
    };
    if (config.subdomains) {
      options.subdomains = config.subdomains;
    }
    tileLayer = L.tileLayer(config.urlTemplate, options);
    tileLayer.on("tileerror", function () {
      // Tile failures must not affect overlay visibility.
    });
    tileLayer.addTo(map);
    tileLayer.bringToBack();
    applyCountryStyle(true);
  }

  function loadTiles() {
    return fetch("/map-data/tiles.json?ts=" + Date.now(), { cache: "no-store" })
      .then(function (response) {
        if (!response.ok) {
          throw new Error("tiles.json HTTP " + response.status);
        }
        return response.json();
      })
      .then(applyTileConfig)
      .catch(function (err) {
        console.error(err);
        applyTileConfig(null);
      });
  }

  function drawGraticule() {
    graticuleLayer.clearLayers();
    var style = {
      color: "#8a93a0",
      weight: 0.6,
      opacity: 0.55,
      interactive: false,
    };
    for (var lat = -80; lat <= 80; lat += 20) {
      graticuleLayer.addLayer(
        L.polyline(
          [
            [lat, -180],
            [lat, 180],
          ],
          style
        )
      );
    }
    for (var lon = -180; lon <= 180; lon += 30) {
      graticuleLayer.addLayer(
        L.polyline(
          [
            [-90, lon],
            [90, lon],
          ],
          style
        )
      );
    }
  }

  function pointInRing(lon, lat, ring) {
    var inside = false;
    for (var i = 0, j = ring.length - 1; i < ring.length; j = i++) {
      var xi = ring[i][0];
      var yi = ring[i][1];
      var xj = ring[j][0];
      var yj = ring[j][1];
      var intersect =
        yi > lat !== yj > lat &&
        lon < ((xj - xi) * (lat - yi)) / (yj - yi + 0.0) + xi;
      if (intersect) {
        inside = !inside;
      }
    }
    return inside;
  }

  function pointInPolygon(lon, lat, geometry) {
    if (!geometry) {
      return false;
    }
    var polys;
    if (geometry.type === "Polygon") {
      polys = [geometry.coordinates];
    } else if (geometry.type === "MultiPolygon") {
      polys = geometry.coordinates;
    } else {
      return false;
    }
    for (var p = 0; p < polys.length; p++) {
      var rings = polys[p];
      if (!rings.length || !pointInRing(lon, lat, rings[0])) {
        continue;
      }
      var inHole = false;
      for (var h = 1; h < rings.length; h++) {
        if (pointInRing(lon, lat, rings[h])) {
          inHole = true;
          break;
        }
      }
      if (!inHole) {
        return true;
      }
    }
    return false;
  }

  function formatCoords(lat, lon) {
    return lat.toFixed(4) + ", " + lon.toFixed(4);
  }

  function countryNameAt(lat, lon) {
    var wrappedLon = ((((lon + 180) % 360) + 360) % 360) - 180;
    var name = null;
    if (countryLayer) {
      countryLayer.eachLayer(function (layer) {
        if (name || !layer.feature) {
          return;
        }
        if (pointInPolygon(wrappedLon, lat, layer.feature.geometry)) {
          name = layer.feature.properties.name || layer.feature.properties.code;
        }
      });
    }
    return name;
  }

  function updateCountryLabel() {
    var lat;
    var lon;
    if (measurementFocus) {
      lat = measurementFocus.lat;
      lon = measurementFocus.lon;
    } else {
      var center = map.getCenter();
      lat = center.lat;
      lon = center.lng;
    }
    var name = countryNameAt(lat, lon);
    countryLabelEl.textContent = name || formatCoords(lat, lon);
  }

  function log1p(x) {
    return Math.log(1 + Math.max(0, x));
  }

  function percentile(sorted, p) {
    if (!sorted.length) {
      return 0;
    }
    var idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
    return sorted[idx];
  }

  function lerpColor(t) {
    if (t <= 0) {
      return PALETTE[0];
    }
    if (t >= 1) {
      return PALETTE[PALETTE.length - 1];
    }
    var scaled = t * (PALETTE.length - 1);
    var i = Math.floor(scaled);
    var f = scaled - i;
    var a = PALETTE[i];
    var b = PALETTE[i + 1];
    return [
      Math.round(a[0] + (b[0] - a[0]) * f),
      Math.round(a[1] + (b[1] - a[1]) * f),
      Math.round(a[2] + (b[2] - a[2]) * f),
    ];
  }

  function buildColorScale(tracks) {
    var values = [];
    for (var t = 0; t < tracks.length; t++) {
      var track = tracks[t];
      for (var i = 0; i < track.length; i++) {
        values.push(log1p(track[i][2]));
      }
    }
    values.sort(function (a, b) {
      return a - b;
    });
    var dataMinV = values.length ? values[0] : 0;
    var dataMaxV = values.length ? percentile(values, 99) : 1;
    if (dataMaxV <= dataMinV) {
      dataMaxV = dataMinV + 1e-6;
    }
    var dataSpan = dataMaxV - dataMinV;
    var minV = dataMinV + colorBarMinFraction * dataSpan;
    var maxV = dataMinV + colorBarMaxFraction * dataSpan;
    if (maxV <= minV) {
      maxV = minV + 1e-6;
    }
    var minCps = Math.exp(minV) - 1;
    var maxCps = Math.exp(maxV) - 1;
    var dataMinCps = Math.exp(dataMinV) - 1;
    var dataMaxCps = Math.exp(dataMaxV) - 1;
    return {
      dataMinV: dataMinV,
      dataMaxV: dataMaxV,
      dataMinCps: dataMinCps,
      dataMaxCps: dataMaxCps,
      minV: minV,
      maxV: maxV,
      minCps: minCps,
      maxCps: maxCps,
      colorFor: function (cps) {
        var v = (log1p(cps) - minV) / (maxV - minV);
        if (v < 0) {
          v = 0;
        }
        if (v > 1) {
          v = 1;
        }
        var rgb = lerpColor(v);
        return (
          "rgba(" +
          rgb[0] +
          "," +
          rgb[1] +
          "," +
          rgb[2] +
          "," +
          TRACK_POINT_FILL_ALPHA +
          ")"
        );
      },
    };
  }

  function formatLegendCps(value) {
    if (!isFinite(value) || value < 0) {
      value = 0;
    }
    if (value >= 1000) {
      var kcps = value / 1000;
      if (kcps >= 100) {
        return kcps.toFixed(1) + "k";
      }
      if (kcps >= 10) {
        return kcps.toFixed(2) + "k";
      }
      return kcps.toFixed(3) + "k";
    }
    if (value >= 100) {
      return value.toFixed(1);
    }
    if (value >= 10) {
      return value.toFixed(2);
    }
    return value.toFixed(3);
  }

  function paintLegendSwatch() {
    if (!legendSwatchEl || !legendSwatchEl.getContext) {
      return;
    }
    var cssW = legendSwatchEl.clientWidth || 18;
    var cssH = legendSwatchEl.clientHeight || 160;
    if (cssW < 2 || cssH < 2) {
      return;
    }
    var dpr = window.devicePixelRatio || 1;
    var w = Math.max(2, Math.round(cssW * dpr));
    var h = Math.max(2, Math.round(cssH * dpr));
    if (legendSwatchEl.width !== w) {
      legendSwatchEl.width = w;
    }
    if (legendSwatchEl.height !== h) {
      legendSwatchEl.height = h;
    }
    var ctx = legendSwatchEl.getContext("2d");
    var img = ctx.createImageData(w, h);
    var data = img.data;
    var minF = colorBarMinFraction;
    var maxF = colorBarMaxFraction;
    var activeSpan = Math.max(1e-6, maxF - minF);
    for (var y = 0; y < h; y++) {
      // Top of bar = high values, bottom = low values.
      var fraction = h <= 1 ? 1 : 1 - y / (h - 1);
      var t;
      if (fraction <= minF) {
        t = 0;
      } else if (fraction >= maxF) {
        t = 1;
      } else {
        t = (fraction - minF) / activeSpan;
      }
      var rgb = lerpColor(t);
      for (var x = 0; x < w; x++) {
        var i = (y * w + x) * 4;
        data[i] = rgb[0];
        data[i + 1] = rgb[1];
        data[i + 2] = rgb[2];
        data[i + 3] = 255;
      }
    }
    ctx.putImageData(img, 0, 0);
  }

  function positionLegendHandles() {
    if (!legendSwatchWrapEl) {
      return;
    }
    var height = legendSwatchWrapEl.clientHeight || 160;
    // fraction 0 at bottom, 1 at top
    legendHandleMinEl.style.top = (1 - colorBarMinFraction) * height + "px";
    legendHandleMaxEl.style.top = (1 - colorBarMaxFraction) * height + "px";
    legendHandleMinEl.setAttribute("aria-valuemin", "0");
    legendHandleMinEl.setAttribute("aria-valuemax", "1");
    legendHandleMinEl.setAttribute("aria-valuenow", String(colorBarMinFraction));
    legendHandleMaxEl.setAttribute("aria-valuemin", "0");
    legendHandleMaxEl.setAttribute("aria-valuemax", "1");
    legendHandleMaxEl.setAttribute("aria-valuenow", String(colorBarMaxFraction));
  }

  function updateLegend(scale, isSpectrum) {
    if (!scale) {
      legendEl.hidden = true;
      activeColorScale = null;
      return;
    }
    legendEl.hidden = false;
    syncDecimationModeButton();
    if (isSpectrum) {
      legendColorbarEl.hidden = true;
      activeColorScale = null;
      return;
    }
    legendColorbarEl.hidden = false;
    activeColorScale = scale;
    legendMinEl.textContent = formatLegendCps(scale.minCps);
    legendMaxEl.textContent = formatLegendCps(scale.maxCps);
    paintLegendSwatch();
    positionLegendHandles();
  }

  function syncDecimationModeButton() {
    if (!decimationModeBtn) {
      return;
    }
    decimationModeBtn.textContent = DECIMATION_MODE;
    decimationModeBtn.setAttribute(
      "title",
      "Decimation: " + DECIMATION_MODE + " (tap to cycle)"
    );
    decimationModeBtn.setAttribute(
      "aria-label",
      "Decimation mode " + DECIMATION_MODE
    );
  }

  function cycleDecimationMode() {
    var idx = DECIMATION_MODES.indexOf(DECIMATION_MODE);
    if (idx < 0) {
      idx = 0;
    }
    DECIMATION_MODE = DECIMATION_MODES[(idx + 1) % DECIMATION_MODES.length];
    syncDecimationModeButton();
    if (trackLayer) {
      trackLayer._reset();
    }
  }

  function applyColorBarRange() {
    if (!trackLayer || trackLayer._isSpectrum || !activeColorScale) {
      return;
    }
    var dataMinV = activeColorScale.dataMinV;
    var dataMaxV = activeColorScale.dataMaxV;
    var dataSpan = dataMaxV - dataMinV;
    var minV = dataMinV + colorBarMinFraction * dataSpan;
    var maxV = dataMinV + colorBarMaxFraction * dataSpan;
    if (maxV <= minV) {
      maxV = minV + 1e-6;
    }
    activeColorScale.minV = minV;
    activeColorScale.maxV = maxV;
    activeColorScale.minCps = Math.exp(minV) - 1;
    activeColorScale.maxCps = Math.exp(maxV) - 1;
    activeColorScale.colorFor = function (cps) {
      var v = (log1p(cps) - minV) / (maxV - minV);
      if (v < 0) {
        v = 0;
      }
      if (v > 1) {
        v = 1;
      }
      var rgb = lerpColor(v);
      return (
        "rgba(" +
        rgb[0] +
        "," +
        rgb[1] +
        "," +
        rgb[2] +
        "," +
        TRACK_POINT_FILL_ALPHA +
        ")"
      );
    };
    trackLayer._scale = activeColorScale;
    updateLegend(activeColorScale, false);
    if (trackLayer._ctx) {
      trackLayer._draw();
    }
  }

  function clientYToColorBarFraction(clientY) {
    var rect = legendSwatchWrapEl.getBoundingClientRect();
    if (rect.height <= 0) {
      return 0;
    }
    var t = (clientY - rect.top) / rect.height;
    if (t < 0) {
      t = 0;
    }
    if (t > 1) {
      t = 1;
    }
    // top = high = fraction 1
    return 1 - t;
  }

  function setColorBarFractionFromPointer(clientY) {
    var fraction = clientYToColorBarFraction(clientY);
    if (legendDragHandle === "min") {
      colorBarMinFraction = Math.min(fraction, colorBarMaxFraction - COLOR_BAR_MIN_SPAN);
      colorBarMinFraction = Math.max(0, colorBarMinFraction);
    } else if (legendDragHandle === "max") {
      colorBarMaxFraction = Math.max(fraction, colorBarMinFraction + COLOR_BAR_MIN_SPAN);
      colorBarMaxFraction = Math.min(1, colorBarMaxFraction);
    } else {
      return;
    }
    applyColorBarRange();
  }

  function endLegendDrag() {
    if (!legendDragHandle) {
      return;
    }
    legendDragHandle = null;
    legendEl.classList.remove("cps-legend-dragging");
  }

  function pickLegendHandle(clientY) {
    var rect = legendSwatchWrapEl.getBoundingClientRect();
    if (rect.height <= 0) {
      return "max";
    }
    var yMin = rect.top + (1 - colorBarMinFraction) * rect.height;
    var yMax = rect.top + (1 - colorBarMaxFraction) * rect.height;
    return Math.abs(clientY - yMin) <= Math.abs(clientY - yMax) ? "min" : "max";
  }

  function startLegendDrag(handle, event) {
    if (legendEl.hidden) {
      return;
    }
    legendDragHandle = handle;
    legendEl.classList.add("cps-legend-dragging");
    if (event.touches && event.touches[0]) {
      setColorBarFractionFromPointer(event.touches[0].clientY);
    } else if (typeof event.clientY === "number") {
      setColorBarFractionFromPointer(event.clientY);
    }
  }

  function onLegendPointerMove(event) {
    if (!legendDragHandle) {
      return;
    }
    if (event.cancelable) {
      event.preventDefault();
    }
    if (event.touches && event.touches[0]) {
      setColorBarFractionFromPointer(event.touches[0].clientY);
    } else if (typeof event.clientY === "number") {
      setColorBarFractionFromPointer(event.clientY);
    }
  }

  function onLegendBarPointerDown(event) {
    if (legendEl.hidden) {
      return;
    }
    var clientY =
      event.touches && event.touches[0]
        ? event.touches[0].clientY
        : event.clientY;
    if (typeof clientY !== "number") {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    startLegendDrag(pickLegendHandle(clientY), event);
  }

  legendHandleMinEl.addEventListener("mousedown", function (e) {
    e.preventDefault();
    e.stopPropagation();
    startLegendDrag("min", e);
  });
  legendHandleMaxEl.addEventListener("mousedown", function (e) {
    e.preventDefault();
    e.stopPropagation();
    startLegendDrag("max", e);
  });
  legendHandleMinEl.addEventListener(
    "touchstart",
    function (e) {
      e.preventDefault();
      e.stopPropagation();
      startLegendDrag("min", e);
    },
    { passive: false }
  );
  legendHandleMaxEl.addEventListener(
    "touchstart",
    function (e) {
      e.preventDefault();
      e.stopPropagation();
      startLegendDrag("max", e);
    },
    { passive: false }
  );
  legendSwatchWrapEl.addEventListener("mousedown", onLegendBarPointerDown);
  legendSwatchWrapEl.addEventListener("touchstart", onLegendBarPointerDown, {
    passive: false,
  });
  window.addEventListener("mousemove", onLegendPointerMove);
  window.addEventListener(
    "touchmove",
    onLegendPointerMove,
    { passive: false }
  );
  window.addEventListener("mouseup", endLegendDrag);
  window.addEventListener("touchend", endLegendDrag);
  window.addEventListener("touchcancel", endLegendDrag);

  function formatTimestamp(ts) {
    if (!ts) {
      return "—";
    }
    try {
      var d = new Date(ts);
      if (isNaN(d.getTime())) {
        return String(ts);
      }
      function pad(n) {
        return n < 10 ? "0" + n : String(n);
      }
      return (
        d.getFullYear() +
        "-" +
        pad(d.getMonth() + 1) +
        "-" +
        pad(d.getDate()) +
        " " +
        pad(d.getHours()) +
        ":" +
        pad(d.getMinutes()) +
        ":" +
        pad(d.getSeconds())
      );
    } catch (e) {
      return String(ts);
    }
  }

  var popupLatLng = null;

  function hidePopup() {
    popupLatLng = null;
    popupEl.hidden = true;
  }

  function positionPopup(latlng) {
    var point = map.latLngToContainerPoint(latlng);
    popupEl.style.left = point.x + 12 + "px";
    popupEl.style.top = point.y - 12 + "px";
  }

  function showPopup(latlng, html) {
    popupLatLng = latlng;
    popupEl.innerHTML = html;
    popupEl.hidden = false;
    positionPopup(latlng);
  }

  function repositionPopup() {
    if (!popupLatLng || popupEl.hidden) {
      return;
    }
    positionPopup(popupLatLng);
  }

  map.on("move zoom resize", repositionPopup);

  var TrackCanvasLayer = L.Layer.extend({
    initialize: function (tracks, options) {
      this._tracks = tracks || [];
      this._options = options || {};
      this._scale = this._options.scale || buildColorScale(this._tracks);
      this._isSpectrum = !!this._options.isSpectrum;
      this._points = [];
      this._displayPoints = [];
      this._selected = null;
      this._prepare();
    },

    onAdd: function (mapObj) {
      this._map = mapObj;
      // Do not use leaflet-zoom-animated: a full-viewport canvas cannot be
      // CSS-scaled like tiles, and that class fights with redraw on zoom.
      this._canvas = L.DomUtil.create("canvas", "leaflet-layer");
      this._canvas.style.pointerEvents = "none";
      this._ctx = this._canvas.getContext("2d");
      var pane = mapObj.getPanes().overlayPane;
      pane.appendChild(this._canvas);
      mapObj.on("moveend zoomend viewreset resize", this._reset, this);
      mapObj.on("zoomstart", this._onZoomStart, this);
      mapObj.on("click", this._onClick, this);
      this._reset();
    },

    onRemove: function (mapObj) {
      mapObj.off("moveend zoomend viewreset resize", this._reset, this);
      mapObj.off("zoomstart", this._onZoomStart, this);
      mapObj.off("click", this._onClick, this);
      if (this._canvas && this._canvas.parentNode) {
        this._canvas.parentNode.removeChild(this._canvas);
      }
      this._canvas = null;
      this._ctx = null;
      this._map = null;
      this._selected = null;
      hidePopup();
    },

    _clearSelection: function () {
      this._selected = null;
      hidePopup();
      if (this._ctx) {
        this._draw();
      }
    },

    _prepare: function () {
      this._points = [];
      this._displayPoints = [];
      for (var t = 0; t < this._tracks.length; t++) {
        var track = this._tracks[t];
        var prepared = [];
        for (var i = 0; i < track.length; i++) {
          var pt = track[i];
          prepared.push({
            lon: pt[0],
            lat: pt[1],
            cps: pt[2],
            ts: pt[3],
            x: 0,
            y: 0,
            latlng: L.latLng(pt[1], pt[0]),
          });
        }
        this._points.push(prepared);
      }
    },

    _onZoomStart: function () {
      // Hide stale bitmap while Leaflet animates the basemap; redraw on zoomend.
      if (this._canvas) {
        this._canvas.style.visibility = "hidden";
      }
    },

    _rebuildDisplayPoints: function (topLeft) {
      var mapObj = this._map;
      var size = mapObj.getSize();
      var pad = TRACK_POINT_RADIUS + TRACK_POINT_STROKE_OUTER_WIDTH + 8;
      var minX = -pad;
      var minY = -pad;
      var maxX = size.x + pad;
      var maxY = size.y + pad;

      var mapBounds = mapObj.getBounds();
      var wrapsLng = mapBounds.getWest() > mapBounds.getEast();
      var sw = mapObj.containerPointToLatLng(L.point(minX, maxY));
      var ne = mapObj.containerPointToLatLng(L.point(maxX, minY));
      var minLat = Math.min(sw.lat, ne.lat);
      var maxLat = Math.max(sw.lat, ne.lat);
      var minLon = Math.min(sw.lng, ne.lng);
      var maxLon = Math.max(sw.lng, ne.lng);

      // World-pixel grid at current zoom: pan-stable (unlike container bins).
      var zoom = mapObj.getZoom();
      // Prefer denser sampling when zoomed out (track compresses on screen).
      // At/above DECIMATION_REF_ZOOM, cells stay at DECIMATION_BASE_CELL; below that they shrink.
      var cellSize =
        DECIMATION_BASE_CELL *
        Math.pow(2, Math.min(0, zoom - DECIMATION_REF_ZOOM));
      cellSize = Math.max(DECIMATION_MIN_CELL, Math.ceil(cellSize));

      var mode = DECIMATION_MODE;
      var isAvg = mode === "avg";
      var isMin = mode === "min";

      var visible = [];
      for (var t = 0; t < this._points.length; t++) {
        var track = this._points[t];
        for (var i = 0; i < track.length; i++) {
          var pt = track[i];
          if (
            !wrapsLng &&
            (pt.lat < minLat ||
              pt.lat > maxLat ||
              pt.lon < minLon ||
              pt.lon > maxLon)
          ) {
            continue;
          }
          var lp = mapObj.latLngToLayerPoint(pt.latlng);
          var px = lp.x - topLeft.x;
          var py = lp.y - topLeft.y;
          if (px < minX || px > maxX || py < minY || py > maxY) {
            continue;
          }
          pt.x = px;
          pt.y = py;
          var world = mapObj.project(pt.latlng, zoom);
          visible.push({ pt: pt, wx: world.x, wy: world.y });
        }
      }

      function binVisible(sizePx) {
        var cells = Object.create(null);
        var count = 0;
        for (var v = 0; v < visible.length; v++) {
          var item = visible[v];
          var vp = item.pt;
          var cx = Math.floor(item.wx / sizePx);
          var cy = Math.floor(item.wy / sizePx);
          var key = cx + ":" + cy;
          var cell = cells[key];
          if (isAvg) {
            if (!cell) {
              cells[key] = {
                sumCps: vp.cps,
                sumX: vp.x,
                sumY: vp.y,
                count: 1,
              };
              count += 1;
            } else {
              cell.sumCps += vp.cps;
              cell.sumX += vp.x;
              cell.sumY += vp.y;
              cell.count += 1;
            }
          } else if (!cell) {
            cells[key] = vp;
            count += 1;
          } else if (isMin) {
            if (vp.cps < cell.cps) {
              cells[key] = vp;
            }
          } else if (vp.cps > cell.cps) {
            cells[key] = vp;
          }
        }
        return { cells: cells, count: count };
      }

      var binned = binVisible(cellSize);
      // Cap draw count without forcing large cells when the track is sparse on screen.
      if (binned.count > MAX_DRAWN_POINTS) {
        cellSize = Math.ceil(
          cellSize * Math.sqrt(binned.count / MAX_DRAWN_POINTS)
        );
        binned = binVisible(cellSize);
      }

      var cells = binned.cells;
      var display = [];
      if (isAvg) {
        for (var avgKey in cells) {
          if (!Object.prototype.hasOwnProperty.call(cells, avgKey)) {
            continue;
          }
          var agg = cells[avgKey];
          var ax = agg.sumX / agg.count;
          var ay = agg.sumY / agg.count;
          var avgCps = agg.sumCps / agg.count;
          var avgLl = mapObj.containerPointToLatLng(L.point(ax, ay));
          display.push({
            lon: avgLl.lng,
            lat: avgLl.lat,
            cps: avgCps,
            ts: 0,
            x: ax,
            y: ay,
            latlng: avgLl,
          });
        }
      } else {
        for (var keyOut in cells) {
          if (Object.prototype.hasOwnProperty.call(cells, keyOut)) {
            display.push(cells[keyOut]);
          }
        }
      }

      this._displayPoints = display;
    },

    _reset: function () {
      if (!this._map || !this._canvas) {
        return;
      }
      var topLeft = this._map.containerPointToLayerPoint([0, 0]);
      L.DomUtil.setPosition(this._canvas, topLeft);
      this._canvas.style.visibility = "";
      var size = this._map.getSize();
      if (this._canvas.width !== size.x) {
        this._canvas.width = size.x;
      }
      if (this._canvas.height !== size.y) {
        this._canvas.height = size.y;
      }
      this._rebuildDisplayPoints(topLeft);
      this._draw();
    },

    _draw: function () {
      var ctx = this._ctx;
      var size = this._map.getSize();
      ctx.clearRect(0, 0, size.x, size.y);

      var pts = this._displayPoints;
      for (var j = 0; j < pts.length; j++) {
        var pt = pts[j];
        ctx.beginPath();
        ctx.fillStyle = this._isSpectrum
          ? "rgba(192, 57, 43, " + TRACK_POINT_FILL_ALPHA + ")"
          : this._scale.colorFor(pt.cps);
        ctx.arc(pt.x, pt.y, TRACK_POINT_RADIUS, 0, Math.PI * 2);
        ctx.fill();
        if (pt === this._selected) {
          ctx.lineWidth = TRACK_POINT_STROKE_OUTER_WIDTH;
          ctx.strokeStyle = TRACK_POINT_STROKE_OUTER;
          ctx.stroke();
          ctx.lineWidth = TRACK_POINT_STROKE_WIDTH;
          ctx.strokeStyle = TRACK_POINT_STROKE;
          ctx.stroke();
        }
      }
    },

    _onClick: function (e) {
      if (!this._map) {
        return;
      }
      var click = this._map.latLngToContainerPoint(e.latlng);
      var best = null;
      var bestDist = TRACK_POINT_HIT_RADIUS;
      var pts = this._displayPoints;
      for (var i = 0; i < pts.length; i++) {
        var dx = pts[i].x - click.x;
        var dy = pts[i].y - click.y;
        var d = Math.sqrt(dx * dx + dy * dy);
        if (d < bestDist) {
          bestDist = d;
          best = pts[i];
        }
      }
      if (!best) {
        this._clearSelection();
        return;
      }
      this._selected = best;
      this._draw();
      var html =
        formatCoords(best.lat, best.lon) +
        "<br>" +
        formatTimestamp(best.ts);
      if (!this._isSpectrum) {
        html += "<br>CPS: " + best.cps.toFixed(best.cps >= 10 ? 1 : 2);
      }
      showPopup(best.latlng, html);
    },
  });

  function collectBounds(tracks) {
    var bounds = null;
    for (var t = 0; t < tracks.length; t++) {
      for (var i = 0; i < tracks[t].length; i++) {
        var pt = tracks[t][i];
        var ll = L.latLng(pt[1], pt[0]);
        if (!bounds) {
          bounds = L.latLngBounds(ll, ll);
        } else {
          bounds.extend(ll);
        }
      }
    }
    return bounds;
  }

  function setMeasurementFocusFromTracks(tracks) {
    var bounds = collectBounds(tracks);
    if (!bounds) {
      measurementFocus = null;
      updateCountryLabel();
      return;
    }
    var c = bounds.getCenter();
    measurementFocus = { lat: c.lat, lon: c.lng };
    updateCountryLabel();
  }

  function fitToTracks(tracks, isSpectrum) {
    var bounds = collectBounds(tracks);
    if (!bounds) {
      return;
    }
    if (isSpectrum || bounds.getNorthEast().equals(bounds.getSouthWest())) {
      map.setView(bounds.getCenter(), isSpectrum ? 16 : Math.min(16, map.getMaxZoom()));
    } else {
      map.fitBounds(bounds.pad(0.15), { maxZoom: 16 });
    }
    initialFitDone = true;
  }

  function updateCenterButton() {
    centerBtn.setAttribute("aria-label", centerLabel);
    centerBtn.setAttribute("title", centerLabel);
    centerBtn.hidden = !deviceLatLng;
  }

  function centerOnDeviceLocation() {
    if (!deviceLatLng) {
      return;
    }
    var targetZoom = Math.max(map.getZoom(), 15);
    map.setView(deviceLatLng, targetZoom, { animate: true });
  }

  function invalidateMapSize() {
    map.invalidateSize({ animate: false });
    if (trackLayer) {
      trackLayer._reset();
    }
    if (!legendEl.hidden) {
      paintLegendSwatch();
      positionLegendHandles();
    }
  }

  function applyPayload(payload) {
    var tracks = payload.tracks || [];
    var isSpectrum = payload.mode === "spectrum";
    if (trackLayer) {
      map.removeLayer(trackLayer);
      trackLayer = null;
    }
    if (!tracks.length) {
      legendEl.hidden = true;
      measurementFocus = null;
      updateCountryLabel();
      return;
    }
    var scale = buildColorScale(tracks);
    updateLegend(scale, isSpectrum);
    trackLayer = new TrackCanvasLayer(tracks, {
      scale: scale,
      isSpectrum: isSpectrum,
    });
    map.addLayer(trackLayer);
    setMeasurementFocusFromTracks(tracks);
    if (!initialFitDone) {
      fitToTracks(tracks, isSpectrum);
    }
  }

  function applyDeviceLocation(payload) {
    if (deviceMarker) {
      map.removeLayer(deviceMarker);
      deviceMarker = null;
    }
    if (deviceAccuracy) {
      map.removeLayer(deviceAccuracy);
      deviceAccuracy = null;
    }
    deviceLatLng = null;
    if (!payload || !payload.available) {
      updateCenterButton();
      return;
    }
    var latlng = L.latLng(payload.latitude, payload.longitude);
    deviceLatLng = latlng;
    if (payload.accuracyMeters != null && isFinite(payload.accuracyMeters) && payload.accuracyMeters > 0) {
      deviceAccuracy = L.circle(latlng, {
        radius: payload.accuracyMeters,
        color: "#1a73e8",
        weight: 1,
        opacity: 0.45,
        fillColor: "#1a73e8",
        fillOpacity: 0.12,
        interactive: false,
      }).addTo(map);
    }
    deviceMarker = L.circleMarker(latlng, {
      radius: 7,
      color: TRACK_POINT_STROKE,
      weight: TRACK_POINT_STROKE_WIDTH,
      fillColor: "#1a73e8",
      fillOpacity: 1,
      interactive: false,
    }).addTo(map);
    updateCenterButton();
  }

  function loadLocation() {
    return fetch("/map-data/location.json?ts=" + Date.now(), { cache: "no-store" })
      .then(function (response) {
        if (!response.ok) {
          throw new Error("location.json HTTP " + response.status);
        }
        return response.json();
      })
      .then(applyDeviceLocation)
      .catch(function (err) {
        console.error(err);
        applyDeviceLocation(null);
      });
  }

  function loadTrack() {
    if (refreshInFlight) {
      refreshQueued = true;
      return refreshInFlight;
    }
    refreshInFlight = fetch("/map-data/track.json?ts=" + Date.now(), {
      cache: "no-store",
    })
      .then(function (response) {
        if (!response.ok) {
          throw new Error("track.json HTTP " + response.status);
        }
        return response.json();
      })
      .then(applyPayload)
      .catch(function (err) {
        console.error(err);
      })
      .then(function () {
        refreshInFlight = null;
        if (refreshQueued) {
          refreshQueued = false;
          return loadTrack();
        }
      });
    return refreshInFlight;
  }

  var CpsLegendControl = L.Control.extend({
    options: { position: "bottomright" },
    onAdd: function () {
      L.DomEvent.disableClickPropagation(legendEl);
      L.DomEvent.disableScrollPropagation(legendEl);
      return legendEl;
    },
  });
  map.addControl(new CpsLegendControl());

  syncDecimationModeButton();
  if (decimationModeBtn) {
    decimationModeBtn.addEventListener("click", function (e) {
      e.preventDefault();
      e.stopPropagation();
      cycleDecimationMode();
    });
  }

  drawGraticule();
  updateCenterButton();

  centerBtn.addEventListener("click", centerOnDeviceLocation);

  map.on("moveend", function () {
    if (!measurementFocus) {
      updateCountryLabel();
    }
  });
  map.on("zoomend", function () {
    if (!measurementFocus) {
      updateCountryLabel();
    }
  });

  window.addEventListener("resize", invalidateMapSize);
  // WebView often finishes layout after Leaflet's first size read.
  setTimeout(invalidateMapSize, 0);
  setTimeout(invalidateMapSize, 250);

  fetch("countries.geojson")
    .then(function (response) {
      if (!response.ok) {
        throw new Error("countries.geojson HTTP " + response.status);
      }
      return response.json();
    })
    .then(function (geojson) {
      countryLayer = L.geoJSON(geojson, {
        interactive: false,
        style: countryStyle(false),
      }).addTo(map);
      updateCountryLabel();
    })
    .catch(function (err) {
      countryLabelEl.textContent = "Country layer unavailable";
      console.error(err);
    })
    .then(function () {
      return loadTiles();
    })
    .then(function () {
      return loadTrack();
    })
    .then(loadLocation);

  window.AtomSpectraMapPage = {
    mode: mode,
    map: map,
    refreshTrack: loadTrack,
    refreshTiles: loadTiles,
    refreshLocation: loadLocation,
    refreshCountryLabel: updateCountryLabel,
    centerOnDeviceLocation: centerOnDeviceLocation,
    invalidateSize: invalidateMapSize,
  };
})();
