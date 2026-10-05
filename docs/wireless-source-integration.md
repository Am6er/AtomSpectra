# Wireless source integration: BluZ BLE assessment

Investigation notes for adding Bluetooth LE spectrometers as input sources, with
BluZ as the proof of concept. Two wireless types are in view — `bluz_bt` and
`atomspectra_bt` — next to the existing `audio` and Spectra Pro (USB) sources.

Revised 2026-10-05 against branch `refactor/input-source` (HEAD `74c38f3`).
**Nothing on that branch has been compiled or run** (no JDK/Android SDK on the
development machine), so every "already in place" below means "present in the
source tree", not "verified working".

Sources reviewed:

- `C:\Projects\Personal\AtomSpectra` @ `refactor/input-source`
- `C:\Projects\Personal\BluZ\Android\BluZ_v2\BluZ\Android\BluZ_LPM` — the current
  BluZ app (v2, versionName 1.13, minSdk 27, compileSdk/targetSdk 36). Its BLE
  stack is `BluetoothInterface.kt` (786 lines) with `DeviceFrame.kt`; protocol
  notes are in `Android\BluZ_v2\BLE_PROTOCOL.md` (partly inaccurate, see §2).
  `Android\BluZ_LPM\` (1162-line `BluetoothInterface.kt`) and `BluZ_archive/` are
  older versions; do not use them as reference.
- Firmware `C:\Projects\Personal\BluZ\stm32cubeide\BluZ_LPM` — the source of truth
  for protocol byte offsets and the firmware this app talks to (it has command 8,
  which the v2 app sends). `BluZ_LPM_v2` in the same folder is a separate
  variant (different ADC setup, no command 8) with the same frame layout; the
  firmware line numbers below refer to `BluZ_LPM` unless stated.

---

## Part 1 — Where the application is now

The prerequisite refactor this document used to wait for has landed in the
working tree (`docs/input-source-refactor.md` is gone; its result is described
in `docs/device-state-machine.md`). What that changes for BLE:

### 1.1 Model

- **The user picks one device and it is locked.** There is no automatic fallback
  between sources. Session state is `UNSELECTED` / `LOCKED` / `OFFLINE`; the
  device state (`NONE/WAITING/IDLE/RECORDING/BUSY/ERROR`) is derived from the
  source. The choice is persisted as `DeviceChoice` (type + identity + name).
- **A source owns its device's lifecycle.** `SpectrumSource.requestConnect()`
  must wait silently for an absent device and reconnect after a loss; only a
  failed handshake is an error, and it is terminal until the user retries.
  Nothing in the service polls or retries.
- **Sources are created from `(type, identity)`** by
  `AtomSpectraService.createSource()`, which handles only `TYPE_AUDIO` and
  `TYPE_SPECTRA_PRO` today. `SpectrumSource.TYPE_BLUZ = 3` is already reserved.
  There is no type constant for `atomspectra_bt`.
- **Discovery is `DeviceScanner`**, used only by `AtomSpectraDeviceSelect`. It
  returns `DeviceDescriptor`s (type, identity, display name, permission flag,
  opaque token). `scanBluetooth()` exists as an empty seam called from `scan()`.
  Sources do their own waiting and do not use the scanner.
- **Replies are broadcasts** carrying `EXTRA_SOURCE_INSTANCE_ID`: `READY`
  (status, device id, channel count, calibration coefficients), `STATUS`,
  `ERROR` (op/reason/text), `DISCONNECTED` (reason text), `DATA` (histogram,
  recording time, cps), `DATA_SKIPPED`, `CALIBRATION_SAVED`.
- **"Device holds its own data" is `!supportsInitialHistogram()`.** Pro returns
  false, audio true. The service uses that one flag in the connect decision
  (`reconcileScreenWithDevice`), the start decision (`canContinueScreenSpectrum`
  → `startDecision`) and to decide whether the screen spectrum is pushed to the
  source before a start (`pushSpectrumToSource`). BluZ holds its spectrum in
  firmware, so it behaves exactly like Pro here with no new code.

### 1.2 Type-specific places in the service and UI

Everything that is still keyed on the two existing types and will need a BLE
branch:

| Where | What |
|---|---|
| `AtomSpectraService.createSource()` | add the type |
| `AtomSpectraService.lockedSourceName()` and `createNewServiceNotification()` | audio/usb strings only |
| `AtomSpectraService.updateInputDeviceInfo()` | ternary: audio → audio info text, anything else → USB info text |
| `AtomSpectraService.onDeviceReturned()` | USB-attached toast when type is Pro |
| `AtomSpectraService.onInputError()` | toast strings `log_usb_command_failed/timeout` ("… USB command failed"); `OP_CALIBRATION_SAVE` → `cal_wrong_store_usb` ("Can't store calibration to a USB device") |
| `AtomSpectraService.onSourceDisconnected()` | suspend reason is a ternary Pro→`USB_DISCONNECT`, else `AUDIO_REMOVED` |
| `RECORDING_SUSPEND_REASON_*` | only `AUDIO_REMOVED` (1) and `USB_DISCONNECT` (3) |
| `AtomSpectra.updateSelectedInputIndicator()` | icon switch: `input_usb`, `input_mic`, `input_none` |
| `AtomSpectra` suspended-dialog text | switch on the two reasons |
| `DeviceIdentity` | only `audio(...)` and `usb(...)` |
| `AppPermissions` | `Capability` is `MIC, LOCATION, STORAGE, NOTIFICATIONS`; no Bluetooth |
| `AndroidManifest.xml` | no Bluetooth permissions or `uses-feature`; service type is `specialUse\|microphone\|location` |

### 1.3 Data path

`onSourceData()` (service) writes the histogram and time to the foreground
spectrum, takes `cp1s` from the source's extra, and derives `cp1sInterval`, dose
rate, the spectrum-change data and the search histories from the **difference
between consecutive histograms** (guarded by `new_time > old_time` and
`old_time > 0`). Idle snapshots (`showIdleSnapshot`) only display the histogram.

Channel count is adopted from the source, once per lock. The source reports it
in `ACTION_SOURCE_READY` (`EXTRA_SOURCE_CHANNEL_COUNT`, must equal
`channelCount()`); on the first ready the service stores it as
`deviceChannelCount`. `reconcileScreenWithDevice()` then always takes the screen
over (or asks, if the screen has unsaved data) when it differs from the screen's
count, and `resetServiceSpectrum()` resets `SpectrumData` to `deviceChannelCount`
(and calls `UIViewState.onChannelCountChanged`) before
`applyDeviceCalibration()` builds the calibration at that count.
`Constants.isValidChannelCount()` accepts any power of two >= 1024. Data never
resizes the screen: `isFrameSizeValid()` drops a `DATA` histogram whose length
differs from the screen's and counts it as skipped. A re-ready after a loss
(`onDeviceReturned`) does **not** re-read the count, so a source whose count
changes between connections would have all its frames dropped. Audio and Pro both
return a fixed `HIST_POINTS` (8192) = `DEFAULT_CHANNEL_COUNT`, so a count other
than 8192 has never been exercised. See section 4.1.

Calibration: the source puts coefficients in `ACTION_SOURCE_READY`;
`reconcileScreenWithDevice`/`adoptDeviceSpectrum` apply them through
`SpectrumData.applyDeviceCalibration()`. `Calibration.Calculate(double[])` takes
coefficients lowest order first (`c0 + c1·ch + c2·ch² + …`), any length, trailing
zeros trimmed. `isCorrect()` requires at least two terms and energy strictly
increasing over every channel; otherwise the default linear 0–3 MeV calibration
is applied with the "wrong calibration" toast.

### 1.4 Process and threading

Unchanged: the service owns all device I/O and runs it on its input thread; the
activity talks to it through intents and static state. `DeviceScanner` lives on
the main thread (callbacks posted to the main looper).

---

## Part 2 — BluZ protocol

It describes the firmware and the v2 app, not AtomSpectra. Where
`BLE_PROTOCOL.md` in the BluZ repo disagrees with the code, the code and firmware
are followed here.

### 2.1 Transport

BLE GATT, from v2 `BluetoothInterface.kt` and firmware `app_ble.c`:

| | |
|---|---|
| Service | `0000fe80-cc7a-482a-984a-7f2ed5b3e58f` |
| Notify (device → app) | `0000fe81-8e22-4541-9d4c-21edae82ed19` |
| Write (app → device) | `0000fe82-8e22-4541-9d4c-21edae82ed19` |
| MTU | 251 requested; firmware notifications are 244-byte chunks, the app splits writes at 248 (`MTU − 3`) |
| Connection priority | `CONNECTION_PRIORITY_HIGH` |
| Advertised name | `BluZ` (app constant `propCfgBLEDeviceName`) |
| Advertisement | complete local name + manufacturer data (company id `0x0030`, 4 bytes updated by the firmware, `0xFFFFFFFF` on overload). **No service UUID is advertised.** |
| CCCD | `00002902-…`; the app enables indication if the characteristic has it, else notification |

The v2 app connects with `connectGatt(autoConnect=false, TRANSPORT_LE)`, calls
`discoverServices()` and `requestMtu(251)` right after `STATE_CONNECTED` and again
in `onServicesDiscovered`, and enables notifications in `onMtuChanged`;
"connected" means the CCCD write succeeded.

### 2.2 Inbound frames

A frame begins with a notification whose first three bytes are `<B>`, followed
by a type byte. The app sets the number of chunks from the type and counts them
down (there is no sequence number); the next chunks are appended to one buffer.
The last chunk's bytes 242/243 are the 16-bit little-endian sum checksum of the
bytes accumulated from all chunks. A chunk lost in the middle is not detected
until the checksum fails, and the next `<B>` restarts the frame.

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
| 34/38/42 | firmware "1024" calibration A/B/C (float) — unused by the v2 app |
| 46 | cps → µR/h factor (float) |
| 50 / 52 | HV setting / comparator threshold (10-bit) |
| 54/56/58 | alarm levels 1/2/3 (uint16) |
| 60, 61 | device config bitfield (60: LED, sound, 3 sound-level, 3 vibro-level flags; 61: bit 0 autostart, bit 1 click ÷10, bit 2 LED ÷10) |
| 62/66/70 | firmware "2048" calibration A/B/C (float). **The v2 app stores calibration terms D (@66) and E (@70) here**, see below |
| 74/78/82 | firmware "4096" calibration A/B/C (float) |
| 86 | **spectrometer time, seconds (uint32)** |
| 90 | **spectrometer pulse count (uint32)** |
| 94 | dosimeter averaging window (uint16) |
| 96 | **bits per channel** (firmware 16..32, default 20; the app also clamps) |
| 97 | bits 0-2 ADC sample time; bit 3 SiPM overload flag |
| 100 | dosimeter ring buffer (512 × uint16) |
| 1124 | log entries (50 × 6 bytes: uint32 time, uint16 event type) |
| 1424 | **spectrum / history data**, 2 bytes per channel |

Multi-byte fields are little-endian (the v2 app reads offset 86 and 90 that way;
`BLE_PROTOCOL.md` wrongly says big-endian for 86).

**Calibration in the v2 app is one quartic, not per-resolution quadratics.** It
evaluates `E = A·x⁴ + B·x³ + C·x² + D·x + E` (Horner, `energyCalculator.kt`) with
A/B/C from offsets 74/78/82 and D/E from 66/70, where `x` is the channel in
**4096-bin units** (`x = 4·ch` for a 1024 frame, `2·ch` for 2048, `ch` for 4096).
The firmware does not know this: it stores those bytes as the "2048" B and C
coefficients and the settings write (`SettingsFragment.kt:689-717`) overwrites
them with D and E. A device whose calibration was never written by the v2 app may
hold unrelated 2048-set values at 66/70, so D and E are meaningful only for a
device the v2 app has configured.

Header-packet fields (read from the raw first notification, not the reassembled
buffer): total pulses @10, pulses/sec @14, total time @18, average cps @22
(float), MCU temperature @26 (float), battery volts @30 (float). "Total" and
"time" here are the dosimeter's (`pulseCounter`, `currentTime`: since power-on or
the last command 3), not the spectrometer's; the spectrum's time and pulses are
the fields at 86 and 90. `spectrometerTime` only increments while the
spectrometer is on; it is not cleared by stop, but by command 1 or a sample-time
change.

### 2.3 Spectrum encoding — lossy

Channel counts are logarithmically compressed into 16 bits:

```
count = exp(v * bitsPerChannel / 65535 * ln 2) - 1
```

With the default 20 bits per channel a 16-bit code spans 0..2^20. Decompression
is exact enough for display but **frame-to-frame differences are not reliable**
— see §4.2.

### 2.4 Outbound commands

`<S>` + command byte, zero-padded, 16-bit little-endian sum of bytes 0..241 at
offsets 242/243. Commands go to the write characteristic as write-without-response.

**Write length is unresolved.** The firmware (`bluz_app.c:140-148`) accepts a
write only if `Length > 10`, starts with `<S>`, and the sum of bytes
`0 .. Length-4` equals bytes 242/243. That matches a sum over 0..241 only for a
received `Length` of 245. The v2 app builds a 255-byte `sendBuffer` and `write()`
sends it as a 248-byte write followed by a 7-byte one. These do not obviously
agree (a 248-byte write would make the firmware sum include the checksum bytes
themselves); capture what a working write looks like on the wire (or
re-check the stack's reported `Length`) before fixing the source's command size.

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
| 8 | clear history spectrum (`BluZ_LPM` firmware only; absent in `BluZ_LPM_v2`) |

Command 5 answers with the next frame as a history frame (type 4..6, same size as
the spectrum frame of the current resolution), then the device goes back to its
previous frame type. The "history" spectrum is a separate RAM buffer
(`historySpecterBuffer`) that the pulse handler fills only while the
spectrometer is on and an alarm level is exceeded (`history_active`), and that
is zero on a device that never ran. It that is not kept across power cycles. It is
not the accumulated spectrum.

---

## Part 3 — Mapping BluZ onto `SpectrumSource`

A `BluZBleSource implements SpectrumSource` (`TYPE_BLUZ`, identity
`bluz:<MAC>`), one per lock, like the other two.

| Interface member | BluZ |
|---|---|
| `requestConnect()` | connect GATT to the stored MAC; if absent or dropped, keep waiting (`autoConnect`, or a MAC-filtered scan) and stay `STATUS_DISCONNECTED`. Handshake = MTU, enable notifications, first full frame → `READY` |
| `supportsInitialHistogram()` | **false** — the spectrum lives in firmware, so the existing connect/start decisions apply unchanged |
| `requestStart()` / `requestStop()` | reconciler over the toggle command, §3.2 |
| `requestReset()` | command 1, §3.3 |
| `requestShowData()` | the latest decoded **spectrum** frame (type 1..3). An idle device sends none (§3.1), so show is not supported: the source answers with an all-zero `DATA` histogram of `channelCount()` channels and time 0 (decided), never silence |
| `requestSaveCalibration()` | out of scope; must answer with an error, §4.3 |
| `channelCount()` | from the frame type (1024/2048/4096), or fixed 4096 if locked; must be known at `READY` and constant afterwards; policy open, §4.1 |
| `calibration()` | the quartic from offsets 74/78/82/66/70 converted to `Calibration` order and rescaled to `channelCount()`, §4.3 |
| `status()` | `COLLECTING` iff last frame type ≠ 0, else `IDLE` (types 4..6 are one-off history replies and do not change it) |

### 3.1 Status — free

Frame type 0 is idle, `1..3` is collecting. Read from every frame, so the
source's `status()` and the derived device state follow the device even when it
was started or stopped from its own buttons.

**An idle device does not transmit its accumulated spectrum.** The firmware
sends type 0 (dosimeter and log only) while the spectrometer is off; the spectrum
stays in RAM and reappears in the first type 1..3 frame after a start (start does
not clear it, and `spectrometerTime` continues). The v2 app's own notes list this
as a known limitation. Consequences: the resolution is not visible while idle
(§4.1), and the history frame (command 5) is a different spectrum, see §4.1
Finding 3.

**Decided: show is unsupported, fallback is an empty histogram.** The service
only calls `requestShowData()` for a non-collecting device from
`requestDeviceSpectrum()`, right after `adoptDeviceSpectrum()` has reset the
screen, so a zero histogram of `channelCount()` channels with time 0 matches what
the screen already holds. `showIdleSnapshot()` accepts it (length check passes)
and still refuses to overwrite unsaved data that did not come from the device.
Reading the real accumulated spectrum would need a brief start/stop and is not
planned.

### 3.2 Start / stop — needs a reconciler

Command 2 is a **toggle**, not a set. Model it inside the source as a desired
state reconciled against the `dataType` of the next frame:

```
if (desiredCollecting != (dataType != 0)) send(CMD_TOGGLE) with a retry deadline
```

`requestStart()`/`requestStop()` report success when a frame confirms the state
and `OP_START`/`OP_STOP` `REASON_TIMEOUT` when the deadline passes. The service
(`onInputError`) sets `is_recording = false` and shows a "command failed/timeout"
toast; the source's `STATUS_CONNECTED_COMMAND_FAILED` maps to `DeviceState.ERROR`.
Note the service is optimistic: it flips the recording state before the source
answers. Without the reconciler a dropped or duplicated command
silently inverts the device state — the one place BLE is meaningfully worse than
USB.

### 3.3 Reset

Command 1 (clear device spectrum). The screen-side clear is the service's job.

**Checked in firmware:** command 1 zeroes the whole 4096-bin buffer,
`spectrometerPulse` and `spectrometerTime` (`bluz_app.c:369-375`), so offset 86
restarts from 0 together with the counts. The same clear happens on a
sample-time change in command 0. `DeleteSpc()` also resets the service spectrum
(time 0), so the first frame after a reset has `old_time = 0`: it is shown but
gives no rate (`cp1sInterval = 0`), as the `old_time > 0` guard in
`onSourceData()` intends.

### 3.4 Loss and return — now a source concern

This replaces the old plan ("add a suspend reason, drive it from
`onConnectionStateChange`, add backoff" in the service). The service already
handles loss and return generically (`onSourceDisconnected` / `onDeviceReturned`,
suspended-recording dialog, resume). The source must:

- report `ACTION_SOURCE_DISCONNECTED` from `onConnectionStateChange` →
  `STATE_DISCONNECTED` (a real callback; no polling) and when Bluetooth is
  switched off (do **not** copy BluZ's `exitProcess(-1)` in `startScan`, or its
  `GO.needTerminate` path in `initLeDevice`);
- go back to waiting and reconnect by itself, with its own backoff, then send
  `READY` again;
- never retry after a failed handshake, per the interface contract.

Discard BluZ's `intervalTimer.kt` (every 10 s, if not `connected`:
`destroyDevice()` + `initLeDevice()`, a blind teardown and reconnect).

Service/UI work that remains is only the type-keyed spots in §1.2:
a `RECORDING_SUSPEND_REASON_BT_DISCONNECT`, its dialog text, an `input_bt` icon,
notification wording, and generalising the "USB attached" toast.

### 3.5 Selection and discovery

- `DeviceIdentity.bluz(mac)` → `bluz:<MAC>`.
- `DeviceScanner.scanBluetooth()` returns bonded BluZ devices plus live BLE scan
  results. The firmware advertises **no service UUID** (§2.1), so a UUID
  `ScanFilter` would find nothing; filter on the local name `BluZ` (what the v2
  app does), optionally plus manufacturer id `0x0030`. Scan only while the
  selection screen is open: `start()` begins it, `stop()` ends it, results call
  `refresh()`. Token = `BluetoothDevice`.
- `DeviceDescriptor.permissionGranted` carries the Bluetooth permission state;
  `AtomSpectraDeviceSelect.requestPermission()` currently handles only USB and
  needs a Bluetooth branch going through `AppPermissions`.
- Remembered device = `DeviceChoice.device(TYPE_BLUZ, "bluz:<MAC>", name)`.
  Restore locks it and the source waits — no scan is needed at startup.
- Audio names come from `AtomSpectraService.audioDeviceNames` and the Pro name
  from `R.string.device_spectra_pro`; BLE names come from the device.

---

## Part 4 — Risks and open decisions

### 4.1 Channel count — open decision

**Finding 1: the service adopts a source's channel count, but only once.** See
§1.3. The source must put its final count in the first `READY`
(`EXTRA_SOURCE_CHANNEL_COUNT`); the service then resizes the spectra, calibration
and view state to it (`resetServiceSpectrum`, `onChannelCountChanged`), and
drops any later frame of another length. So no service change is needed for a
fixed BluZ count (e.g. 4096), but the count has to be known at handshake, which
for an idle device means the probe of Finding 3, and it cannot change
afterwards without a new lock. Not exercised at any count other than 8192.
Alternative: expand to 8192 inside the source (rescaled calibration and
duplicated/interpolated channels).

**Finding 2: the resolution is a device setting, stored in flash.** It is not
in the frame header. It is visible only as the frame type (1/2/3) of a frame that
carries a spectrum, so an idle device (type 0) does not reveal it. The header does
carry all three calibration polynomials, HV, comparator, alarm levels, config
flags, bits per channel, sample time and overload flag, idle or not.

**Finding 3: resolution can be probed while idle, without starting the
spectrometer.** Command 5 (history request) makes the firmware build the next
frame as type 4/5/6 from the current resolution, then restore the previous data
type (`main.c:483-497`, restored at `main.c:596`). The frame is large
(16-40 chunks). From the firmware: on a device that has never run the history
buffer is all zeros, which is harmless for a probe that only reads the frame
type; the request is a one-shot flag, so the only lasting effect is the restored
`dataType`. *Not tested on a device.* Alternatives: start briefly (changes device state, hits the
toggle problem of §3.2) or add a resolution bit to the header in firmware
(bits 11-15 of the config word are free; needs reflashing every device).

**Finding 4: there is no "set resolution only" command.** Command 0 replaces the
whole settings block (alarm levels, three calibration polynomials, HV,
comparator, flags, bits per channel, sample time, dosimeter window, cps-to-uR/h
factor); resolution is payload byte 37. Changing it means read-modify-write:
rebuild the block from the last frame, change one byte, send all of it. Hazards:

- the frame layout (little-endian, read offsets) differs from the write payload
  (big-endian alarm levels and float coefficients, different offsets); a
  mis-mapped field silently overwrites HV or calibration in flash. Calibration
  has a further trap: the v2 app's D/E terms live in the "2048" B/C slots
  (§2.2), and the payload carries all nine coefficient slots, so a round trip
  must copy every one unchanged;
- each command 0 erases and rewrites flash; a firmware TODO notes the write only
  takes effect on the second call, and an interrupted write can leave settings
  half-written;
- changing resolution does not clear the device spectrum (only a sample-time
  change does), and it does not need to: the device always accumulates 4096 raw
  bins and the resolution only groups them when a frame is built (Finding 5), so
  no counts mix and command 1 is not needed. The sample-time bits of byte 38
  must be sent back unchanged, otherwise the spectrum and spectrometer time are
  cleared;
- another app (the BluZ app settings screen) can change the resolution again.

**Finding 5: resolution is applied when a frame is built, not when counts are
stored** (firmware source, not tested on a device). The pulse ISR
(`stm32wbaxx_it.c:305`) always does `tmpLevel = (TVLevel[0] + OFFSET_CHAN) & 0xFFF`
into one 4096-bin `tmpSpecterBuffer`; the per-resolution shifts are commented out.
`main.c:572-589` builds each frame by summing `chanCnt` adjacent raw bins per
output channel (4 for 1024, 2 for 2048, 1 for 4096), then log2-compresses the sum.
Changing resolution therefore applies to the next frame with no data loss and no
clear. Only the frame size, the frame type and which calibration set applies
change. Log compression and bits-per-channel growth (`main.c:590-594`) mean a 4096
frame is not an exact recombination of 1024 bins.

**Finding 6: there is no flash read command, and none is needed.** Commands are
0-8 only in `BluZ_LPM` (0-7 in `BluZ_LPM_v2`) (write settings, clear spectrum, start/stop, clear dosimeter, clear log,
history, find device, battery calibration, clear history). `writeFlash()`
(`rw_FLASH.c:55`) rebuilds the whole flash block from RAM variables, so command 0
needs only the variables it sets. Comparing command 0 (`bluz_app.c:200-338`) with
the frame header (`main.c:623-716`):

- everything command 0 sets is in the header except **resolution**;
- `VibroEnable` and `battKoeff` are stored in flash but are neither in the header
  nor set by command 0, so they keep their RAM value and are preserved;
- alarm levels are 32-bit in the payload but `uint16_t` in RAM, flash and
  header, so the firmware truncates them on receipt (`level1 = …` in
  `bluz_app.c`); nothing spills into a neighbouring field in `writeFlash()`.
  Levels copied from the header always fit;
- payload byte 38 carries three settings besides sample time: bit 0 autostart,
  bits 1-3 ADC sample time, bit 4 click ÷10, bit 5 LED ÷10. All of them must be
  rebuilt from the header (byte 61 bits 0-2 and byte 97 bits 0-2), not just the
  sample time.

So "read the whole flash" is: take the last frame header, rebuild the command 0
payload from it (header little-endian, payload big-endian, different offsets),
set payload byte 37 to the new resolution, rebuild byte 38 as above, add the
16-bit sum at bytes 242-243, send. The only missing input is the current
resolution, from the probe in Finding 3.

**Reflashing is not possible**, so firmware fixes (a resolution bit in the header, or a
dedicated "set resolution" command) are out. Any resolution change must be the
read-modify-write above, with a unit test that round-trips a captured frame
through the payload builder and checks every field is byte-identical except the
one changed.

Command 2 (start/stop spectrometer) never touches flash or settings. The
"autostart on power-up" flag is a stored setting (bit 0 of payload byte 38 in
command 0) and must be sent back unchanged by any command 0.

**How the device works (simplified):**

```
 detector pulse
      |
      v
  ADC -> 12-bit value (0..4095)
      |
      v  always, whatever the resolution setting is
 +------------------------------+
 | tmpSpecterBuffer[4096]       |   RAM, 32-bit counters
 | one counter per raw bin      |   cleared only by: command 1,
 +------------------------------+   or a sample-time change
      |
      |  every frame (timer), the resolution setting picks the grouping:
      |
      +-- 1024: sum 4 raw bins  -> 1 channel  --+
      +-- 2048: sum 2 raw bins  -> 1 channel  --+--> log2 compress
      +-- 4096: 1 raw bin       -> 1 channel  --+     -> BLE frame
                   ^
                   |
         resolutionSpecter (RAM)  <---- loaded at boot from flash
                   ^
                   | command 0 writes the whole settings block
                   |
 +------------------------------+
 | flash settings block         |   levels, HV, comparator, flags,
 | (rewritten as ONE unit)      |   9 calibration coefs, resolution,
 +------------------------------+   sample time, VibroEnable, battKoeff
```

```
 Changing resolution from the phone (no reflash):

 last frame header ---> rebuild cmd 0 payload ---> set byte 37 = new resolution
 (has every setting      (header LE -> payload BE,   rebuild byte 38 (flags)   
  except resolution)      different offsets)          add checksum (bytes 242-243)
                                                            |
                                                            v
                        device: RAM variables updated -> writeFlash()
                        VibroEnable, battKoeff untouched (kept from RAM)
                        spectrum buffer untouched -> next frame has new size
```

**Options (no decision yet):**

| | Behaviour | Writes settings? | Notes |
|---|---|---|---|
| A. Force 4096 | probe; if not 4096, write the block with resolution = 4096 (no clear needed, Finding 5) | yes, once per mis-set device | needs byte-for-byte round-trip test of the mapping, a confirmation dialog, and a re-probe after the write |
| B. Require 4096 | probe; if not 4096, connect error naming the fix ("set 4096 in the BluZ app") | no | nothing can be lost by construction; one-time user action |
| C. Accept any | source reports the frame's count | no | adds the issues below |

A and B can be combined: B first, A as a later step.

**Issues specific to C** (A and B avoid them; Findings 1-2 apply to all):

1. Resolution change while connected: the service does not follow it.
   `isFrameSizeValid()` drops every frame of the new size (and the count is not
   re-read on return, §1.3), so recording would silently stall with "histogram
   skipped" warnings until the device is re-locked. Following it would need a
   re-ready that goes through the connect-decision flow mid-recording, with
   spectrogram recording in progress. Under A/B a frame of another size is simply
   an error.
2. Count-dependent features see three device values: background subtraction
   (equal count required), calibration page and `lastCalibrationChannel` pref,
   `scaleMinFor` zoom limits, add/load spectrum, spectrogram and map data. The
   code takes the count as a parameter, but none of it has been run at more than
   one count.
3. Device switching: more mismatch cases in the connect and start decisions
   ("continue" is refused unless counts match).
4. Test matrix multiplies by three on an unverified refactor.
5. 1024 channels is coarse; users may be on it without knowing.

**Not decided:** which option, and whether a non-8192 count is reported as is
(Finding 1, no service change) or expanded to 8192 inside the source. Enabling the spectrometer on
every connect was discussed and is **not** proposed: the probe makes it
unnecessary, and it would accumulate an unrequested spectrum and raise the
connect-decision dialog on every connect.

### 4.2 Lossy compression and the diffing path — decided: no change

`onSourceData()` computes `cp1sInterval`, dose rate and spectrum change from
histogram differences; BluZ's log-compressed counts make those differences
coarse at high rates. **Decision: accept it and use the existing calculations as
they are.** No capability flag, no separate branch. The source still supplies
`cp1s` through `EXTRA_SOURCE_DATA_CP1S` (the device's `pulsePerSec` is the natural
value). Revisit only if real-hardware results are unusable.

### 4.3 Calibration

The device calibration must be applied on connect or the energy axis is wrong.
The mechanism exists (`READY` extra → `applyDeviceCalibration`), and
`Calibration.Calculate(double[])` accepts a polynomial of any length, lowest
order first (§1.3), so the source has to convert. The BluZ polynomial is a
quartic in 4096-bin units with the highest order first (§2.2):
`E = A·x⁴ + B·x³ + C·x² + D·x + E0`, `x = ch·(4096/N)`. For N channels:
`coeffs = { E0, D·s, C·s², B·s³, A·s⁴ }` with `s = 4096/N`. Open: whether
AtomSpectra's channel index needs the same half-bin convention as BluZ's bin
index (the v2 app evaluates at the integer bin), and that the result passes
`isCorrect()` (strictly increasing over all channels) for real devices, including
ones whose D/E were never written (§2.2). On failure the service applies the
default linear calibration and shows "Wrong calibration from the device".

The toast texts for `cal_apply_usb` and `cal_wrong_usb` are neutral ("…from the
device"); only the resource names say usb (there is a TODO in
`SpectrumData.applyDeviceCalibration` to rename the second). The texts that do
name USB are `cal_wrong_store_usb`, `log_usb_command_failed/timeout` and
`action_usb_attached`.

Writing calibration back to the device (`requestSaveCalibration`) is out of
scope; the source should answer `OP_CALIBRATION_SAVE` with an error rather than
silently ignore it. The interface has no "unsupported" signal, so the UI can
still offer the action — decide whether to add a capability query.

### 4.4 Permissions

Manifest additions:

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
```

- On Android 11 and lower, BLE scanning and connecting need `BLUETOOTH`,
  `BLUETOOTH_ADMIN` and a location permission (`ACCESS_FINE_LOCATION`, already
  declared; Android docs page "Bluetooth permissions"). AtomSpectra keeps
  `ACCESS_FINE_LOCATION` for GPS, so it must not get `maxSdkVersion="30"` as
  the docs suggest for apps that need no location. Whether scan results also
  need the location *services* switch on for 6–11 is not stated on that page;
  check on a device. Handle the permission in `AppPermissions`.
- **Correction to the earlier note:** the service's `foregroundServiceType` is
  `specialUse|microphone|location` — it does **not** include `connectedDevice`.
  Adding it (manifest, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, and
  `AppPermissions.foregroundServiceType()` when Bluetooth is granted) matches
  the use, and Android 14+ refuses to start a `connectedDevice` service unless
  one prerequisite holds (per the FGS types page: a granted `BLUETOOTH_CONNECT`,
  `BLUETOOTH_SCAN` or `BLUETOOTH_ADVERTISE`, or `UWB_RANGING`, or one of a few
  manifest-only permissions, or a USB permission), same pattern as microphone
  today. The existing `specialUse` type would keep the service alive without it,
  so this is a choice, not a requirement; if the type is added, gate it on the
  granted Bluetooth permission.
- Add `Capability.BLUETOOTH` to `AppPermissions` (`isGranted`,
  `requestablePermissions`, an `isBluetoothGranted` helper). Permissions are
  requested from the selection screen when a Bluetooth device is picked, not at
  startup. A remembered BLE device without permission follows the existing
  rule: `REASON_PERMISSION` on connect → session falls back to `UNSELECTED`.
- `<uses-feature ... required="false">` keeps the app installable on audio-only
  devices, unlike BluZ, which requires BLE.
- Update `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, which currently reads
  `usb_serial_spectrometer_data_acquisition`, if the store description matters.

### 4.5 Process and ownership

Keep all GATT ownership inside the source, driven by the service on its input
thread, as with USB. Do not let the selection screen or `DeviceScanner` hold a
`BluetoothGatt`; the scanner only scans.

GATT callbacks arrive on a binder thread; hand frames to the input thread
before they touch service state. Only one GATT connection per device — ensure
the previous source's GATT is closed (`close()`) before a new lock connects,
since `doSelectDevice()` closes the old source first.

### 4.6 Do not import BluZ source

The v2 `BluetoothInterface.kt` (786 lines) is much cleaner than the old 1163-line
one: frame assembly and decoding (`processIncomingPacket`,
`decodeHardwareConfig`) end in a `DeviceFrame` emitted on a `SharedFlow`, and the
UI reads `GO` only after that. It is still not importable: it is Kotlin with
coroutines and `kotlinx` flows, it depends on the global `GO` singleton
(`GO.mainContext`, `GO.LEMAC`, `GO.drawLOG`, `GO.propCfgBLEDeviceName`,
`GO.configDataReady`), it shows `Toast`s, can call `exitProcess(-1)` and sets
`GO.needTerminate`, and it is driven by `intervalTimer`.
AtomSpectra is Java-only (no Kotlin plugin), minSdk 21, compileSdk/targetSdk 34
(BluZ v2: minSdk 27, SDK 36).

Port the framing and decoding (`processIncomingPacket` and
`decodeHardwareConfig` are about 180 lines) into a Java `BluZBleSource`
(ideally a pure `BluZFrameDecoder` with no Android types, so it is unit-testable
without a device). `BluetoothGatt.writeDescriptor(desc, value)` is API 33+; the
pre-33 path uses the deprecated two-step form. BluZ handles both
(`BluetoothInterface.kt:423-432`).

### 4.7 Not building on the old plan

Dropped from the first assessment because the design moved on: automatic
fallback between inputs, a descriptor table, source-type checks in the service
for capabilities, and a service-driven reconnect/backoff. `atomspectra_bt` has
no device, protocol or type constant yet; nothing here should be built for it
until one exists.

---

## Part 5 — Suggested order of work

Blocker: the refactor is unverified. Build it and prove audio and Spectra Pro on
hardware (per `docs/device-state-machine.md`) before layering BLE on top; a
defect in the source contract would be inherited by the third source.

1. **Permissions and manifest.** `Capability.BLUETOOTH`, manifest entries,
   `connectedDevice` foreground type, Bluetooth branch in the selection screen's
   permission request.
2. **Frame decoder.** `BluZFrameDecoder`: reassembly, checksum, header fields,
   log-decompression, resolution from frame type. Unit tests with captured
   frames (no device needed).
3. **`BluZBleSource`.** Connect/wait/reconnect, handshake, resolution probe, toggle reconciler,
   reset, `READY`/`STATUS`/`DATA`/`DISCONNECTED`/`ERROR` broadcasts,
   `supportsInitialHistogram() == false`.
4. **Discovery and identity.** `DeviceIdentity.bluz`, `DeviceScanner`
   `scanBluetooth()` with scan lifecycle, `createSource()` case, `TYPE_BLUZ`
   handling in the selection screen.
5. **Service and UI type spots** from §1.2: suspend reason, dialog text, icon,
   notification, toast.
6. **Channel count at ready.** The service already adopts it from the first
   `READY` (§4.1 Finding 1); the work is in the source (probe or fixed count) and
   in exercising a non-8192 count for the first time. Can precede step 3.
7. **Hardware pass:** confirm §2.4 (command write length), §4.1 (probe
   behaviour, chosen resolution policy), §4.3 (calibration conversion and
   `isCorrect()` on real devices). §3.3 (reset vs time) is settled by the
   firmware source.

Rough effort: the protocol codec is small; the source's wait/reconnect
behaviour, the toggle reconciler and the quartic calibration conversion are the
real work, and the channel-count decision in §4.1 is the likeliest cause of
rework.
