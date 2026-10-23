# Standard DTC scan and diagnostic report

This extends build27 with a separate three-service DTC scan and a fresh, durable text report. It does not add ECU writes, manufacturer sessions or new adapter commands: 03, 07 and 0A were already on the read allowlist. Existing guarded clearing remains unchanged.

## Phone workflow

- Connect, select an ECU if required, then choose **Összes hibakódtípus olvasása**. Stored (03), pending (07) and permanent (0A) results are shown separately. The old stored-only button remains available.
- Choose **Friss diagnosztikai jelentés készítése**. This pauses live polling and closes any active CSV recording. It freshly reads all three DTC services, adapter/Mode09 identification, readiness, vehicle-status PIDs and freeze frame 0. It does not reuse UI labels or claim simultaneous sampling.
- **Mentett jelentés megtekintése** opens a selectable, scrollable copy in the app. **Megosztása** opens Android's chooser; **Mentése fájlba** uses the system document picker to create a separate TXT copy. Completed reports persist across app restarts. Uninstalling deletes internal reports; exported copies remain independent. Returning from another app requires reconnecting, as before.
- DEMO creates an explicitly simulated report without adapter traffic or a genuine VIN. DEMO reads never resolve a previous uncertain real clearing outcome.

## Meaning and limits

An empty DTC list is shown only after successful strict decoding of that service's positive response. NO DATA, malformed/truncated data, a negative response, an unexpected service, or a missing selected responder is displayed as **NEM ELLENŐRIZHETŐ**, never as absence of codes or confirmed lack of support. Optional-service failures do not hide successful stored/pending results. Permanent codes are cleared by the ECU's own checks; Mode04 does not remove them. The 2007 vehicle may not support permanent codes.

The small Hungarian glossary covers explicitly listed generic codes only. Unknown or manufacturer-specific meanings require factory documentation; a code description is not a failed-part diagnosis. There is no automatic vehicle/catalyst pass/fail decision, OEM service function, ECU parameter adjustment, or emissions-monitor change.

Every report is scoped to one selected responder and one REAL/DEMO mode. It includes app version, UTC start, monotonic duration and section read windows. The configured model/engine is labelled user-supplied, not ECU-verified. Missing identities/readiness/freeze-frame fields remain explicit in their respective sections. No report claims a full scan of ABS, airbag, body or other modules. A previous uncertain clear outcome is recorded as a warning, not retrospectively treated as successful. VIN may appear if the ECU returns it; the UI states this before export.

Command execution is serialized with the existing polling worker. Session changes/backgrounding invalidate pending results; connection loss aborts the fresh scan instead of saving a misleading clean report. Existing archived reports retain their original metadata. Completed scans are written as UTF-8 .txt.part, synced, closed and renamed in the same private directory. Only completed named .txt reports are listed/exported. Report storage failures are reported, with no fallback to a fake success. The FileProvider is limited to the separate measurements and reports folders; diagnostic backups and signing material are outside its roots. Sharing grants temporary read access only.

## Validation

Added tests cover separate CAN/legacy DTC services, optional failures, strict empty-result handling, wrong-service/negative/truncated replies, per-source filtering, transport/cancellation, timestamped fresh report collection, missing fields, REAL/DEMO separation, atomic completed-file listing, scoped content URI reading, UI rejection/busy behavior, persistence and simulated-outcome separation. A TCP adapter simulator also exercises the three services and full report through Elm327 with no writes. Existing transport/clear/graph/CSV tests remain in CI along with lint, debug/release builds and stable package/signature checks.

Actual Mode07/0A availability, device share/document-picker behavior and report readability on Android16 still require phone/vehicle testing. Previous screenshots confirm build25 standard OBD reads, not these new functions.

References: Elm Electronics ELM327 datasheet, Talking to the Vehicle / Interpreting Trouble Codes / Resetting Trouble Codes: https://elmelectronics.com/wp-content/uploads/2020/05/ELM327DSL.pdf ; Android FileProvider: https://developer.android.com/reference/androidx/core/content/FileProvider
