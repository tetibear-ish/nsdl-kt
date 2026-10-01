# Teaching scenarios

## Computer-to-printer job

The first application scenario connects a `computer`, an `ethernet-switch`, and a `printer`.
Both endpoint types use DHCP. After they receive addresses, the computer submits a named print job
to the printer's address and MAC address. The application exchange is intentionally small:

```text
computer                         printer
   |------ START(job, size) -------->|
   |------ DATA(job, 0, bytes) ----->|
   |------ DATA(job, 1, bytes) ----->|
   |------ COMPLETE(job) ----------->|
   |<----- REPLY(job, ACCEPTED) -----|
```

The exchange uses typed UDP payloads on destination port 9100. It is not TCP or wire-compatible
IPP. The current network model has neither TCP nor ARP, so the caller supplies the printer's MAC
address as well as its IPv4 address. This boundary keeps the lesson accurate: students can inspect
job boundaries, UDP/IP packets, Ethernet forwarding, chunk counts, byte counts, and the printer's
accept/reject decision without being shown a fictional TCP handshake.

The printer accepts a job only when every numbered chunk is present exactly once and their byte
count matches the announced size. An incomplete job receives `REJECTED` and is not added to the
completed queue. The client and server expose bounded summaries in their inspectable snapshots;
packet and frame detail remains in the simulation event journal.

The deterministic integration test in `PrintJobIntegrationTest` is the executable scenario. A UI
action or application command for initiating the same exchange is a follow-up slice; adding one
does not require changing the protocol.

Suggested student checks:

1. Confirm the switch initially floods the printer-bound frames before learning its MAC.
2. Correlate each `PRINT DATA` packet with frame send/receive events on the selected switch ports.
3. Verify the completed job's byte and chunk totals.
4. Disconnect a cable before submission and observe client rejection caused by link unavailability.
5. Omit a chunk in a protocol-level exercise and explain the printer's `REJECTED` reply.
