# Multi-input support: BluZ BT integration assessment

> **OUTDATED — needs revision.** Written against the old input-source refactor,
> whose doc has been removed. Revise after the device selection work
> (`docs/device-selection-refactor.md`) lands: sources are now picked by the
> user and locked (no automatic fallback), discovery lives in `DeviceScanner`,
> and loss/return handling belongs to each source. References below to
> `docs/input-source-refactor.md` and to the fallback/descriptor-table design no
> longer apply.

Investigation notes for supporting four input types — `audio`, `usb`, `bluz_bt`,
`atomspectra_bt` — using BluZ as the proof of concept for the BLE path.

**Prerequisite:** the input-source refactor across the existing `audio` and
`usb` sources must land and both sources must work through it first — see
`docs/input-source-refactor.md`. Everything below assumes that refactor is
done; this doc covers only the BLE-specific work (protocol, new source, UI,
risks).

Sources reviewed:

- `C:\Projects\Personal\AtomSpectra` @ `feat/centralized-permissions` (88b0acb)
- `C:\Projects\Personal\BluZ\Android\BluZ_LPM` — the current BluZ app.
  `BluZ_archive/` is the old single-activity version; `BluZ_v2/` is an accidental
  nested clone of the whole repo (`BluZ_v2/BluZ/Android/BluZ_LPM/...`).
  The STM32 firmware in `BluZ/stm32cubeide/` is the source of truth for the
  protocol byte offsets.

---

## Part 2 — BluZ protocol

### 2.1 Transport

BLE GATT, from `BluetoothInterface.kt`:

| | |
|---|---|
| Service | `0000fe80-cc7a-482a-984a-7f2ed5b3e58f` |
| Notify (device → app) | `0000fe81-8e22-4541-9d4c-21edae82ed19` |
| Write (app → device) | `0000fe82-8e22-4541-9d4c-21edae82ed19` |
| MTU | 251 requested, 244-byte payload |
| Connection priority | `CONNECTION_PRIORITY_HIGH` |
| Advertised name | `BluZ` |

### 2.2 Inbound frames

A frame begins with a notification whose first three bytes are `<B>`, followed
by a type byte. Subsequent notifications are continuation chunks, reassembled
into one buffer. The final chunk's last two bytes are a 16-bit sum checksum of
the payload.

| type | contents | chunks |
|---|---|---|
| 0 | dosimeter + log only — **spectrometer stopped** | 6 |
| 1 | + spectrum, 1024 ch | 16 |
| 2 | + spectrum, 2048 ch | 23 |
| 3 | + spectrum, 4096 ch | 40 |
| 4/5/6 | + *history* spectrum, 1024/2048/4096 | 16/23/40 |

Header field offsets in the reassembled buffer (little-endian):

| offset | field |
|---|---|
| 34/38/42 | calibration A/B/C, 1024 ch (float) |
| 46 | cps → µR/h factor (float) |
| 50 / 52 | HV setting / comparator threshold (10-bit) |
| 54/56/58 | alarm levels 1/2/3 |
| 60, 61 | device config bitfield (LED/sound/vibro/autostart) |
| 62..73 | calibration A/B/C, 2048 ch |
| 74..85 | calibration A/B/C, 4096 ch |
| 86 | **spectrometer time, seconds (uint32)** |
| 90 | **spectrometer pulse count (uint32)** |
| 94 | dosimeter averaging window |
| 96 | **bits per channel** (clamped 16..32, default 20) |
| 97 | bits 0-2 ADC sample time; bit 3 SiPM overload flag |
| 100 | dosimeter ring buffer (512 × uint16) |
| 1124 | log entries (50 × {uint32 time, uint8 event}) |
| 1424 | **spectrum / history data**, 2 bytes per channel |

Header-packet fields (read from the raw first notification, not the reassembled
buffer): total pulses @10, pulses/sec @14, total time @18, average cps @22
(float), MCU temperature @26 (float), battery volts @30 (float).

### 2.3 Spectrum encoding — lossy

Channel counts are logarithmically compressed into 16 bits:

```
count = exp(v * bitsPerChannel / 65535 * ln 2) - 1
```

With the default 20 bits per channel a 16-bit code spans 0..2^20. Decompression
is exact enough for display but **frame-to-frame differences are not reliable**
— see §4.2.

### 2.4 Outbound commands

`<S>` + command byte, padded to a 244-byte buffer, 16-bit sum checksum at
offsets 242/243.

| code | meaning |
|---|---|
| 0 | write settings (payload from offset 4) |
| 1 | clear spectrum buffer |
| 2 | **toggle** spectrometer on/off |
| 3 | reset dosimeter |
| 4 | clear log |
| 5 | request history spectrum |
| 6 | find device (sound + vibro) |
| 7 | report real battery voltage (float at offset 4) |
| 8 | clear history |

---

## Part 3 — Mapping BluZ onto the requested features

### 3.1 Status — free

`dataType == 0` means the spectrometer is idle; `1..3` means it is collecting.
This is read from the first frame after connect and is the exact analogue of the
USB `-stt` → `-ok collecting` answer already handled at
`AtomSpectraService.java:1651`.

### 3.2 Start / stop — needs a reconciler

Command 2 is a **toggle**, not a set. There is no absolute start or stop. Model
it as a desired state that is reconciled against the `dataType` of the next
inbound frame:

```
if (desiredCollecting != (dataType != 0)) send(CMD_TOGGLE) with a retry deadline
```

Without this, a dropped or duplicated command silently inverts the device state.
This is the one place the BT source is meaningfully worse than USB.

### 3.3 Reset

Command 1 (clear device spectrum) plus the local `DeleteSpc()`.

**Open question for the firmware:** does command 1 also zero the spectrometer
time at offset 86? The service's diffing keys off `new_time > old_time`
(`AtomSpectraService.java:1506`), so if counts reset while time keeps running,
the first post-reset frame produces a large negative delta. Verify in
`stm32cubeide/` before implementing.

### 3.4 Disconnects

Discard BluZ's approach — `intervalTimer.kt` polls every 10 s and blindly tears
down and reconnects, and `BluetoothInterface.kt` calls `exitProcess(-1)` when
the adapter is off.

AtomSpectra's existing machinery is much better and is already parameterised by
`recordingSuspendInputType`:

- `onUSBConnectionLostDuringRecording()` / `onUSBConnectionRestored()`
  (`AtomSpectraService.java:3172-3184`)
- `RECORDING_SUSPEND_REASON_*` + the suspended-notification path

Work needed: add `RECORDING_SUSPEND_REASON_BT_DISCONNECT`, drive it from
`onConnectionStateChange` → `STATE_DISCONNECTED` (a real callback, so no
polling), and add reconnect backoff. Rename the pair to
`onConnectionLostDuringRecording(inputType)` as part of the Group B rename
(see `docs/input-source-refactor.md`).

---

## Part 4 — Risks and open decisions

### 4.1 Channel count mismatch

AtomSpectra's internal histogram is 8192 channels (`ADC_EFF_BITS = 13`). BluZ
delivers 1024, 2048 or 4096, and **the resolution can change at runtime** when
the user alters device settings.

`Spectrum.setSpectrum()` swaps in an array of any length, and file-loaded
spectra already carry arbitrary channel counts, so shorter arrays are not
categorically rejected. But the audio path and several fixed-size arrays assume
`NUM_HIST_POINTS`.

Recommendation: expand into 8192 with a fixed multiplier and scale the
calibration polynomial to match, keeping everything downstream uniform. Needs a
read of the drawing and scale-factor code before committing — this is the single
decision most likely to cause rework.

### 4.2 Lossy compression breaks the diffing path

The service derives cps, dose rate and spectrum-change search from *differences*
between consecutive cumulative histograms
(`AtomSpectraService.java:1506-1540`). With log-compressed counts those
differences are quantised and become unusable at high count rates.

**Use the device's own `pulsePerSec` and `spectrometerPulse` fields for rate**,
and use the histogram only for the displayed spectrum. This is a real
divergence from the USB path and needs an explicit branch — ideally expressed as
a source capability ("provides authoritative count rate") rather than a type
check.

### 4.3 Calibration cannot be skipped entirely

Even leaving calibration UI out of scope, the device's A/B/C polynomial must be
applied on connect or the energy axis is wrong. This is the `-cal` equivalent.
Calibration ownership moved into the service in 88b0acb, so the seam is fresh.
The polynomial to read depends on the active resolution (offsets 34 / 62 / 74).

### 4.4 Permissions

Manifest additions:

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
```

Add `Capability.BLUETOOTH` to `AppPermissions` — good timing given the
`feat/centralized-permissions` branch. `foregroundServiceType` already includes
`connectedDevice`; nothing to change there.

Note `<uses-feature ... required="false">`: BluZ marks BLE required, but
AtomSpectra must stay installable on audio-only devices.

### 4.5 Process and ownership

The app runs in `:atom_process`. Keep all GATT ownership inside the service and
drive it from the activity via intents, exactly as USB does. Do not let the
picker dialog hold the `BluetoothGatt`.

### 4.6 Device selection UI

BluZ hardcodes the name `BluZ` and stores a MAC address. AtomSpectra needs a
real picker (bonded devices + scan results) persisting the MAC in prefs, with
auto-reconnect on startup.

Placement: `main_popup.xml` is currently empty and `inputTypeButton`
(`AtomSpectra.java:3647`) is a passive indicator with no click handler — both are
clean hooks for a "Device → Connect Bluetooth…" entry.

### 4.7 Do not import BluZ source

`BluetoothInterface.kt` is 1163 lines in which the GATT notification callback
parses bytes *and* writes to `TextView`s, redraws canvases, inserts GPS rows into
Room, and terminates the process — all through a global `GO` singleton.
AtomSpectra is Java-only (no Kotlin plugin), minSdk 21.

Port the ~200 lines of framing and decoding into a Java `BluZBleSource`. Adding
the Kotlin plugin to pull in the rest is not worth it.

Minor API note: `BluetoothGatt.writeDescriptor(desc, value)` is API 33+; the
pre-33 path uses the deprecated two-step form. BluZ already handles both
(`BluetoothInterface.kt:533-547`).

---

## Part 5 — Suggested order of work

1. Input-source abstraction refactor — see `docs/input-source-refactor.md`. No
   new features, no BLE.
2. `Capability.BLUETOOTH` + manifest + picker UI.
3. `BluZBleSource`: scan, connect, reassemble, checksum, decode → broadcast the
   same extras the serial path emits.
4. Wire into the service: connect/disconnect mirroring `onUSBAttached` /
   `onUSBDetached`, start/stop/reset, watchdog, suspend/resume.
5. Resolve §4.1 (channel count) and §4.2 (count rate source) with real hardware.

Rough effort: the refactor is the bulk of it; the protocol codec itself is small.
Step 1 is a prerequisite for `atomspectra_bt` regardless of whether BluZ
support ships.
