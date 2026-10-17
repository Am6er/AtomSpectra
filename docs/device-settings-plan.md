# Device Settings Plan

Two kinds of settings, one framework:

- **Hardware** settings live on the device. Phase 1 covers these only.
- **Software** settings are saved per device in app preferences. Not implemented in phase 1, but the framework must not need changes to add them.

All settings are edited on one screen with a single Save button. There is no per-setting save.

## 1. Model

`SettingDescriptor`:

- key, type (`INT`, `DECIMAL`, `BOOL`, `SELECT`; `TEXT` reserved), label, help, unit
- min / max, select options, default
- category
- level: `BASIC` or `EXPERT`
- scope: `HARDWARE` or `SOFTWARE`
- `destructive`: saving a change to this setting wipes data, so the user must be warned

`SettingsSchema` is an ordered list of categories, each holding descriptors. Each source type supplies its own schema.

`SettingsStore`: `values()` and `write(Map changes)`.

- Hardware store: goes through the active source.
- Software store: `SharedPreferences`, keyed per device id, in the same style as `CONF_CAL_DEVICE` (`PrefHelper`). Unused in phase 1.

The screen merges stores by descriptor scope.

## 2. Source API (`SpectrumSource`)

This replaces the TODO at the end of the interface. All additions are default methods, so `AtomSpectraAudioSource` needs no change.

There is no read request: BluZ and Pro already learn their settings during the handshake and during operation, like calibration. There is no change notification either. The source always holds the latest snapshot and nothing outside the app changes the device settings. This is a simplification, not strictly true (the device buttons or another app could change them), and it is accepted.

- `settingsSchema()`: empty by default, so also for Audio.
- `settings()`: snapshot `Map<String,Object>`, null until the handshake or first frame has provided it. Called from the UI thread while the source updates on its own thread, so it returns an immutable copy published through a `volatile` field (same pattern as `calibrationCoeffs` in Pro).
- `requestSaveSettings(Map changes)`: async, only the changed keys. The default is a no-op: a source with an empty schema is never asked.
- `ACTION_SOURCE_SETTINGS_SAVED`: a save was confirmed.
- Errors use the existing `OP_SETTINGS_SAVE`. It covers both user saves and the BluZ automatic switch to 4096 resolution, which is also a settings write. The two stay separate states inside `BluZBleSource` (see section 4).

The activity does not use `requestSaveCalibration`'s broadcast path: that one carries no payload. The screen binds to `AtomSpectraService` like `AtomSpectraDeviceSelect` (`LocalBinder.getService()`), and the service gets `settingsSchema()`, `settingsSnapshot()` and `saveDeviceSettings(Map)`. They post to the input thread and call the active source.

The service forwards `ACTION_SOURCE_SETTINGS_SAVED` to the UI as `Constants.ACTION.ACTION_DEVICE_SETTINGS_SAVED`. The action is added to `makeAtomSpectraServiceIntentFilter()` and the `onSourceReply` switch. A failed save arrives as the existing error reply and goes through `onInputError` (generic toast and log).

## 3. Screen and menu

- New `AtomSpectraDeviceSettings` activity, registered in `AndroidManifest.xml` like the other settings screens (portrait lock, `stateAlwaysHidden`). Plain Views/XML, with a RecyclerView of generated rows: category header, int, decimal, bool, select.
- Looks like the other settings screens (`AtomSpectraIntervalSettings`): `Activity` with `LocaleContextWrapper` in `attachBaseContext`, action bar with up button, black background, cyan title, white text, `@drawable/divider` between rows, `RECEIVER_NOT_EXPORTED` receivers, `AtomSpectraLog.action` for logged changes. Text sizes and margins are in `dp`, not `sp`.
- The menu item `action_device_settings` already exists (the `calibration_device` submenu in `atom_spectra.xml`, disabled, string `device_settings`). It is enabled when connected, the schema is non-empty and `settings()` is not null, otherwise it stays disabled. Audio has no settings, so it stays disabled for Audio. The state is set in `updateRecordStatusMenu()` next to `action_switch_device`, and the click is handled where `action_switch_device` is.
- Expert settings are always shown, visually marked as expert, with no warning dialog or hiding switch. Each has a one-line help text.
- One Save button, enabled only when something changed. Inline per-field validation blocks Save while any field is invalid.
- Back with unsaved changes asks "Save / Не сохранять". This is the only guard besides the destructive one.
- If a changed setting is `destructive`, Save first shows a confirmation listing those settings.
- Values are loaded from `settings()` when the screen opens and again after a confirmed save, which also clears the changed state. A failed save keeps the user's edits.
- The screen listens to `ACTION_DEVICE_STATE_CHANGED` and closes on disconnect or device switch.
- New strings go in both `values/strings.xml` and `values-ru/strings.xml`.

## 4. BluZ

Everything goes through command 0, a read-modify-write on the latest retained 100-byte header (see `docs/bluz-device-protocol.md`).

| Category | Setting | Type | Level | Notes |
|---|---|---|---|---|
| Calibration | cal0…cal4 | decimal (float32) | basic | 4096 mode, `{E0,D,C,B,A}` at command offsets 47/43/59/55/51 |
| Indication | Pulse LED | bool | basic | byte 60 bit 0 |
| Indication | Pulse sound | bool | basic | byte 60 bit 1 |
| Indication | Sound ÷10 | bool | basic | byte 61 bit 1 |
| Indication | LED ÷10 | bool | basic | byte 61 bit 2 |
| Spectrometer | Autostart | bool | basic | byte 61 bit 0 |
| Spectrometer | ADC sample time | int 0–7, raw | expert | `destructive`: clears live spectrum and time |
| Spectrometer | HV | int 0–1023, raw | expert | 10-bit |
| Spectrometer | Comparator | int 0–1023, raw | expert | 10-bit |

Not exposed: resolution (the app is fixed at 4096 and sets it itself), bits per channel (managed by firmware), the unused raw calibration slots, battery calibration (command 7) and `VibroEnable`. All unexposed raw bytes and floats are preserved bit-for-bit.

Implementation:

- A settings model parses the retained header into named fields. A builder applies the changes and emits the command-0 packet. It replaces `calibration4096` in `BluZCommandCodec` (its only caller, `requestSaveCalibration`, moves to the builder) and keeps byte 37 at 4096. `resolution4096` stays as it is for the automatic resolution fix.
- `latestSettings` is already kept from every valid frame, history frames included. `settings()` is built from it. Only settings bytes are parsed; live fields (pulses, temperature, battery, times) are ignored. There is no change detection.
- A save is confirmed when a later normal frame (type 0–3) shows the new values. Calibration is compared as the float32 round-trip of what the user typed; ADC sample time is read from byte 97 bits 0–2. A timeout reports the save as failed with `OP_SETTINGS_SAVE`.
- The confirmation follows the existing calibration one (`calibrationPending`, `calibrationSent`, `calibrationDeadline`), generalised into a `settingsPending` state holding the expected values. It excludes `resolutionPending` and `calibrationPending` (same "already pending" error). `updateObservedStatus()`, `recover()` and the error cleanup treat `OP_SETTINGS_SAVE` as pending while either `resolutionPending` or `settingsPending` is set.
- No automatic retry: every command 0 rewrites flash.
- Saving the ADC sample time clears the live spectrum, hence the `destructive` flag.
- Any save sets the resolution to 4096 (byte 37), also while idle. The user cannot see this and it is not a setting.

## 5. Spectra Pro

| Category | Setting | Type | Level | Notes |
|---|---|---|---|---|
| Calibration | cal0…cal4 | decimal (double) | basic | read with `-cal` at connect, written with the existing `buildCalibrationCommands` |
| Device | NOS | int 0–4095 | expert | `NOISE` key in the `-inf` answer, written with `-nos <n>` |

- NOS is parsed from the `-inf` answer at handshake, next to `VERSION` in `checkDeviceVersion`. The answer is space-separated `KEY value` pairs, for example `... NOISE 12 ...`. `getParameter(answer, "NOISE")` is used, after checking how it tokenizes next to the bracketed array values (it returns null on a stray `]`).
- Every firmware reports NOS, so the schema is fixed. If the `-inf` answer has no valid `NOISE`, the source logs a warning and `settings()` stays null, so the settings screen is unavailable.
- A save sends the changed groups as one `executeCommandBatch` (the `-cal` words with a fresh CRC and/or `-nos <n>`) and expects `-ok` for each. On success the source updates its stored values from what was sent, emits `ACTION_SOURCE_SETTINGS_SAVED` and requests a status resync, as the calibration save does.
- A failed batch can leave the device partly written (the calibration accepted, `-nos` not). The source keeps its old values in that case and does not read them back.
- If the calibration CRC is invalid, the screen still shows the coefficients as read (the current code keeps them and only shows a toast). Saving the calibration writes a new CRC, which repairs the device.
- Other `-inf` parameters (`RISE`, `FALL`, `HYST`, `STEP`, `POT`, ...) can become settings later by adding descriptors.

## 6. Calibration is separate

The settings screen edits the device's own calibration only. Saving `cal0…cal4` from settings does not touch the app's saved calibration for the device (`CONF_CAL_DEVICE`) or the active calibration, and it is not applied to the current spectrum. The Calibration menu's store and retrieve items keep working as they do now. Only the low-level wire encoders are shared. A calibration stored through the Calibration menu updates the same source field `settings()` reads, so the settings screen shows it the next time it opens.

## 7. Order of work

1. Framework: model, `SpectrumSource` API, service methods and broadcast, activity, menu enablement. There is no stub schema: the UI is first exercised with the BluZ settings in step 2.
2. BluZ settings model, codec builder, source integration.
3. Pro: NOS parsing and write, calibration through settings.
4. Software settings later, with no framework changes.
