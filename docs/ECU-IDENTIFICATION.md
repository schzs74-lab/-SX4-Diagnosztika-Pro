# Standard OBD identification

The requested goal is dealer-level service functions for the user's 2007 SX4 1.5 petrol. This change is a prerequisite for investigating actual vehicle support, not an implementation of that goal. No manufacturer-specific command definitions or validated hardware compatibility are available yet.

The new identification button reads the adapter's self-reported ATI and ATDPN strings, Mode 09 PID 00 support and, when supported, VIN (02), calibration IDs (04), calibration verification numbers (06), and ECU name (0A). Adapter self-identification does not authenticate its hardware or firmware. Unsupported values and malformed responses are explicitly reported. Missing support masks allow bounded attempts of these four read requests only. Older vehicles may provide none of the Mode 09 identity fields. These identifiers do not guarantee a manufacturer ECU part number or availability of service procedures.

CAN replies use the record count and fixed record widths. Non-CAN replies require contiguous, unique numbered four-byte fragments; VIN requires the standard three leading padding bytes and 17 characters. Addressed CAN replies retain source ECU addresses. Multiple headerless CAN replies are rejected as ambiguous. The pure parser tests include ELM's published CAN and non-CAN VIN examples, malformed lengths, sequence errors and transport cancellation. A TCP adapter test checks the exact additional command allowlist. No changes to ECU-write capabilities are made. The UI labels the vehicle profile as user-provided, rather than presenting the previously hardcoded M15A as a measured identifier.

References:
- ELM Electronics, ELM327 datasheet, vehicle identification examples: https://www.elmelectronics.com/wp-content/uploads/2017/01/ELM327DS.pdf
- Suzuki European service portal, manufacturer repair information (registration required): https://serviceportal.suzuki.eu/

Validation required after installation: actual adapter/vehicle Mode 09 responses, supported transport, and OEM ECU identity/procedure documentation. Full dealer-level service, coding, adaptations and reprogramming remain unimplemented. Neither unit tests nor a successful build establish vehicle compatibility.
