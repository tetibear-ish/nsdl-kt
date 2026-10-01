# Teaching scenarios

## Scenario format

A `Scenario` (`com.a2z.nsdl.scenario.Scenario`) is plain, versioned Kotlin data, built either as a
literal or through a small `scenario { }` DSL (`com.a2z.nsdl.scenario.Scenario.kt`) mirroring the
existing `topology { }` builder:

- an initial topology, expressed with the existing `nsdl` `TopologySpec`/DSL -- a scenario never
  invents its own topology format
- an ordered list of steps: `Do` (any non-topology `Command` -- power, connect, configure, ...),
  `Advance` (sugar for `Command.Advance`, and what the declared bound is checked against), and
  `Invoke` (dispatches `Command.Invoke` to a component's `Actionable.perform`, e.g. a print client's
  "submit" action, resolving each param as either a literal or a live reference -- `AddressOf`/`MacOf`
  read from an endpoint's current state right before the call, since a DHCP-assigned address doesn't
  exist until an earlier step has run)
- a virtual-time `bound`: a static safety cap on the total Advance time the steps may request. A
  scenario whose steps already exceed it is rejected as malformed before anything runs. Whether an
  assertion's condition actually shows up before the steps finish is a separate, ordinary failure
  (worded as a timeout against that bound) -- there's no separate wait/poll machinery, because
  assertions are evaluated against the complete accumulated event trace, not one sampled instant
- a list of `Assertion`s evaluated once the steps finish: `AddressInPool`, `LinkIsUp`, `PacketReaches`
  (the three outcome kinds PLAN.md names), `DecisionMatches` (a causal assertion against the existing
  `DecisionRecord` model -- e.g. a switch's MAC-table `FORWARD` decision), and two general escape
  hatches, `SnapshotField`/`SnapshotListContains`, for protocol-specific checks a named assertion type
  doesn't cover (e.g. a completed print job's byte count)

`ScenarioRunner` (`com.a2z.nsdl.scenario.ScenarioRunner`) drives any `SimulationRuntime` through its
ordinary `submit`/`subscribe` calls, so the exact same engine runs a scenario from a CLI subcommand,
an HTTP endpoint, or directly inside a JUnit test. Its result is one of `Malformed` (the scenario was
structurally invalid and never ran), `Errored` (a step was rejected mid-run; later steps did not run)
or `Completed` (every step ran; carries one pass/fail outcome per assertion, each with a human-readable
message, supporting event excerpts, and -- on the overall result -- final snapshots of every object an
assertion referenced).

A gateway firewall rule match (mentioned in PLAN.md alongside the switch MAC-table example) has no
assertion type yet: no firewall exists anywhere in the codebase to assert against. `DecisionMatches`
is written generically enough to cover one once a firewall slice adds `Responsibility.FIREWALL`
decision records; it does not need to change.

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

The deterministic integration test in `PrintJobIntegrationTest` drives the protocol classes directly.
`com.a2z.nsdl.scenario.teaching.printJobScenario()` is the same exchange expressed as runnable
scenario data (see `PrintJobScenarioTest`): it adds the gateway's DHCP pool and the switch between the
computer and the printer, waits for both to acquire addresses, then invokes the computer's print
client with the printer's live (DHCP-assigned) address and MAC rather than hard-coding them. A UI
action for initiating the same exchange from the running lab (rather than from a scenario run) is
still a follow-up slice; adding one does not require changing the protocol.

Suggested student checks:

1. Confirm the switch initially floods the printer-bound frames before learning its MAC.
2. Correlate each `PRINT DATA` packet with frame send/receive events on the selected switch ports.
3. Verify the completed job's byte and chunk totals.
4. Disconnect a cable before submission and observe client rejection caused by link unavailability.
5. Omit a chunk in a protocol-level exercise and explain the printer's `REJECTED` reply.
