# Follow-up to the independent review of cbd9b69

The user supplied a read-only review of build 23. This change addresses its confirmed application-flow issues and adds regression tests. The original goal of manufacturer service functions remains unimplemented.

## Addressed observations

1. Connection enables ATH1 and decodes addressed CAN (protocols 6–9), ISO 9141-2 (3) and ISO 14230-4 (4–5) replies. Multiple Mode 01 PID 00 responders no longer cause automatic connection failure. Their supported PID sets remain separate. One responder is selected automatically; multiple responders require explicit user selection by source address. No address is assumed to identify the engine ECU. Measurements and diagnostics use only that source. Other ELM protocol numbers are now explicitly unsupported rather than interpreted without source validation. An adapter that ignores ATH1 is rejected with an explanation.
2. Every accepted diagnostic operation pauses live polling and invalidates its old display. A UI timer also updates displayed ages independently of network results, using Android elapsed realtime.
3. A clear permit binds the saved DTC set to one connection/source for a monotonic maximum of two minutes. Before Mode 04, fresh discovery verifies that the responder set is still singular, a fresh Mode 03 must match the saved codes, and fresh RPM/speed checks must pass. The confirmation dialog expires at the permit deadline. The backup is still partial; ECU state cannot be captured atomically and unidentified modules are not backed up.
4. Any transport failure after starting the Mode 04 exchange, or missing/wrong-source positive acknowledgement, produces an explicit unknown-outcome exception and UI message. No automatic retry is performed.
5–6. Progress labels and polling pauses occur only after the operation is accepted; disconnected/busy requests leave previous reports and polling untouched.
7. Clear acknowledgement must come from exactly the expected source. Because requests still use functional addressing, clearing is disabled when more than one ECU was discovered. This is a deliberate limitation pending separately validated physical targeting; read-only ECU selection does not imply targeted writes. Even a successful acknowledgement proves only the reporting responder's result; complete vehicle-wide clearing is not claimed.
8. Generic command() rejects Mode 04 and initialization commands. Only clearDtcs() can dispatch Mode 04, after the shared checks. Initialization exchanges use a private transport function.
9. ECU names may contain suffix NUL padding in the four-byte acronym before the fixed '-' separator. Other embedded NULs remain rejected. Both CAN and legacy decoders have regression cases.
10. The exact reported legacy Mode 09 support-mask variant with one leading record index 01 is accepted only on non-CAN; other extra bytes/counts remain invalid. This is a narrowly tested compatibility form, not a claim that every vehicle emits it.
11. No relaxation of ELM numbered-message length checks: ordinary ISO-TP frame padding is already removed using its declared length. Surplus bytes in a headerless numbered ELM response remain rejected until an actual adapter trace establishes a safe alternative interpretation.

## Tests and remaining validation

New tests cover addressed two-ECU CAN/K-line sessions and selection, KWP length/checksum validation, clear conditions and unknown acknowledgements, permit expiration/session mismatch, ECU acronym padding, and the legacy mask variant. Robolectric tests exercise rejected/busy button presses, age expiry without new data, displaying an unknown clear result, and cancelling confirmation on mode changes. Robolectric runs at API 28; this does not replace Android 16 device testing, Wi-Fi hardware testing or a full end-to-end backup/confirmation test with a real adapter.

The backup explicitly records the selected ECU and that its saved responses are normalized OBD payloads, not original bus frames. Manufacturer-specific functions, coding and calibration writes are unchanged and remain absent. No secret or signing material is changed. The black SX4 image and launcher assets are preserved.

Source for addressed ELM examples and transport envelope behavior: ELM Electronics ELM327 datasheet, OBD message formats and multiline responses: https://elmelectronics.com/wp-content/uploads/2020/05/ELM327DSL.pdf

Build validation results must be read from the CI run for this commit; these notes do not imply a vehicle test.
