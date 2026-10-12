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
- Software store: `SharedPreferences`, keyed per device id, in the same style as `CONF_CAL_DEVICE`. Unused in phase 1.

The screen merges stores by descriptor scope.

## 2. Source API (`SpectrumSource`)

This replaces the TODO at the end of the interface. There is no read request: BluZ and Pro already learn their settings during the handshake and during operation, like calibration.

- `settingsSchema()`: null or empty for Audio.
- `settings()`: snapshot `Map<String,Object>`, null until the handshake or first frame has provided it.
- `requestSaveSettings(Map changes)`: async, only the changed keys.
- `ACTION_SOURCE_SETTINGS`: values changed. Fired on handshake, when a BluZ frame header differs from the last one, and after a save.
- `ACTION_SOURCE_SETTINGS_SAVED`: a save was confirmed.
- Errors use the existing `OP_SETTINGS_SAVE`.

`AtomSpectraService` forwards the save request the same way it does `requestSaveCalibration`.

## 3. Screen and menu

- New `AtomSpectraDeviceSettings` activity. Plain Views/XML like the other activities, with a RecyclerView of generated rows: category header, int, decimal, bool, select.
- Opened from a new `action_device_settings` item in the Device submenu (`atom_spectra.xml`). Visible when connected and the schema is non-empty, disabled while `settings()` is null. Visibility goes in `updateRecordStatusMenu()`.
- Expert settings are always shown, visually marked as expert, with no warning dialog or hiding switch. Each has a one-line help text.
- One Save button, enabled only when something changed. Inline per-field validation blocks Save while any field is invalid.
- Back with unsaved changes asks "Save / Не сохранять". This is the only guard besides the destructive one.
- If a changed setting is `destructive`, Save first shows a confirmation listing those settings.
- The screen listens to `ACTION_SOURCE_SETTINGS`. Rows the user has edited are not overwritten by an incoming update. Untouched rows refresh.
- The screen closes on disconnect or device switch.
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

- A settings model parses the retained header into named fields. A builder applies the changes and emits the command-0 packet. It replaces `resolution4096` and `calibration4096` in `BluZCommandCodec`, keeping byte 37 at 4096.
- `settings()` is rebuilt from the header of each valid frame.
- A save is confirmed when a later frame header shows the new values. Calibration is compared as the float32 round-trip of what the user typed. A timeout reports the save as failed.
- No automatic retry: every command 0 rewrites flash.
- Saving the ADC sample time clears the live spectrum, hence the `destructive` flag.

## 5. Spectra Pro

| Category | Setting | Type | Level | Notes |
|---|---|---|---|---|
| Calibration | cal0…cal4 | decimal (double) | basic | read with `-cal` at connect, written with the existing `buildCalibrationCommands` |
| Device | NOS | int 0–4095 | expert | `NOISE` key in the `-inf` answer, written with `-nos <n>` |

- NOS is parsed from the `-inf` answer at handshake. The answer is space-separated `KEY value` pairs, for example `... NOISE 12 ...`. `getParameter(answer, "NOISE")` is used, after checking how it tokenizes next to the bracketed array values.
- A save sends the commands and expects `-ok`. On success the source updates its stored values from what was sent.
- If the calibration CRC is invalid, the device has no valid calibration and the cal fields start empty.
- Other `-inf` parameters (`RISE`, `FALL`, `HYST`, `STEP`, `POT`, ...) can become settings later by adding descriptors.

## 6. Calibration is separate

The settings screen edits the device's own calibration only. Saving `cal0…cal4` from settings does not touch the app's saved calibration for the device (`CONF_CAL_DEVICE`) or the active calibration, and it is not applied to the current spectrum. The Calibration menu's store and retrieve items keep working as they do now. Only the low-level wire encoders are shared.

## 7. Order of work

1. Framework: model, `SpectrumSource` API, service forwarding, activity, menu, with a stub schema to check the UI.
2. BluZ settings model, codec builder, source integration.
3. Pro: NOS parsing and write, calibration through settings.
4. Software settings later, with no framework changes.
