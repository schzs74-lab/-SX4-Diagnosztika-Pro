# Oxygen graphs and measurement recording

This extends the source-aware test app with two narrowband voltage traces (Mode 01 PID 14/15), a fast oxygen-only polling profile and timestamped CSV recording/export. It introduces no new adapter or ECU commands.

## Phone workflow

1. Connect in real mode and select the responding ECU as before. Start live data to see the graphs. DEMO uses explicit simulated samples without contacting an adapter; both chart and CSV identify DEMO.
2. Optionally enable **Gyors szondamérés** and start live data again. Only supported PID 14/15 voltage channels are polled in this profile. Other live values are paused. This profile does not convert wideband lambda PID 24 into a narrowband voltage graph.
3. Press **Rögzítés indítása** to start a CSV recording (live polling starts if needed). **Rögzítés lezárása és mentése** finalizes the recording while live graphs may continue.
4. Choose **Mentett mérés megosztása** for Android's chooser, or **Mentett mérés mentése fájlba** to pick a destination using the system document picker. Close the active recording first. Opening another app/document picker stops the adapter session under the existing foreground-only lifecycle policy; reconnect on return.

Diagnostics, polling-profile changes, ECU selection, DEMO mode changes, manual measurement stop and backgrounding close the recording. Each file is bound to one mode, ECU and polling profile; recording does not silently resume after these boundaries. Maximum recording limits: one hour, 100,000 sample/error rows or 10 MiB, whichever comes first. Completed logs persist in the application's private measurements folder and remain available after app restart. App uninstall removes these internal files; exported copies are independent.

## Graph semantics

- Separate receive timestamps; the two channels are not sampled simultaneously.
- A 60-second rolling window with a bounded 1,024-point history per channel. Each received point is shown; there is no resampling to invent simultaneous measurements.
- An error or a gap exceeding five seconds starts a new line segment. Missing/unsupported values never become zeroes. Paused charts are labelled and freeze their time window; session/source changes clear the history.
- This display is not a catalyst-health verdict. Poll frequency depends on adapter/vehicle latency and the selected profile; even the fast view is not an oscilloscope. No P0420 pass/fail classifier is added.

## CSV format and storage

UTF-8 with BOM, comma delimiter, CRLF records and CSV quote escaping. Columns: schema, session_id, mode (REAL/DEMO), ecu, polling_profile, app_version, utc_time, elapsed_ms, pid_hex, value, unit, payload_hex, reading, status. Numeric values use a decimal point and explicit units. Text/bitfield values keep their complete decoded reading and bytes without inventing a numeric scalar. ERROR rows have empty numeric value/payload; END records explain why recording stopped. UTC is anchored to start wall time plus monotonic elapsed sample time, so a wall-clock adjustment does not move samples out of order.

The recorded payload bytes are the selected ECU's normalized Mode 01 data, not original bus frames. No VIN, network password or signing key is added. Text fields that could be interpreted as spreadsheet formulas are prefixed with an apostrophe; numeric fields retain signs. Every file carries immutable source metadata on every row, including zero-sample recordings via END.

The single polling worker owns file I/O. Files are written as .csv.part, synced periodically and on close, then atomically renamed in the same directory to .csv. Only finished .csv logs appear in export choices. An unclean process kill/storage error may leave an incomplete .part file; it is not advertised as a completed measurement and there is no recovery UI yet. File errors stop recording and are reported separately from adapter errors.

Sharing uses AndroidX FileProvider with a narrowly scoped files/measurements path, exported=false and a temporary read grant. The app exposes neither diagnostic-backup files nor unfinished logs through the share workflow. Save As uses ACTION_CREATE_DOCUMENT and requires no broad storage permission. Share/save require the user to choose the receiving app or destination; the application does not automatically send files.

## Validation

Regression tests cover independent timestamped traces, gaps/window bounds, numeric sample decoding, DEMO data, CSV metadata/escaping, failed samples, row/time limits, source separation, mode/profile/background finalization, and content-URI read grants/path restrictions. A native Robolectric render creates a clearly labelled DEMO chart preview in CI test artifacts. CI also runs all existing clear/transport/UI checks, lint, debug/release compilation and package/signer checks.

Phone testing of graph appearance, actual sampling rate, CSV opening in target apps and the Android 16 document/share pickers remains necessary. The previously supplied screenshots verify basic build25 OBD reads on this user's vehicle; they do not verify these newly added features.

References: https://developer.android.com/reference/androidx/core/content/FileProvider and https://developer.android.com/training/data-storage/shared/documents-files

## Export for Hungarian spreadsheet settings

**Mentett mérés mentése fájlba** now asks for the format of the exported copy:

- **Szabványos CSV** – byte-exact copy of the stored file (comma separator, decimal point), for programs and scripts.
- **Magyar Excel** – a converted copy named `…-excel-hu.csv`: semicolon separator and decimal comma, as expected by spreadsheet programs with Hungarian regional settings. Only the numeric measurement columns (schema, elapsed_ms, value, rate_hz, request_ms, channel_errors, rpm, rpm_age_ms, fuel_status, fuel_age_ms) get a decimal comma; text such as decoded readings, UTC time and payload bytes is unchanged. Column detection uses the header, so older schema 1 recordings convert too. UTF-8 BOM, quoting and formula protection are retained.

The stored measurement is never modified; sharing still sends the standard file. The conversion runs completely before the destination is opened, so a malformed source cannot leave a truncated target file. If a document provider rejects the truncating `"wt"` write mode, the app falls back to `"w"`, which is equivalent for the freshly created empty document.

Tests: `CsvExportTest` (JVM) covers numeric/text column handling, separators, quotes and line breaks inside fields, schema 1 input and malformed input; `ExportUiTest` (Robolectric) covers the converted and byte-exact exports through the activity result path and format persistence across recreation. Opening the converted file in a spreadsheet program on the phone or PC remains a manual check.
