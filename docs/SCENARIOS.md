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
IPP. This protocol-level scenario supplies the printer's IPv4 address and, optionally, its MAC
address (omitted, the computer resolves it with ARP). The software layer's `print-spooler` reaches
the same exchange by printer name instead (see "Software layer" in ARCHITECTURE.md). This boundary
keeps the lesson accurate: students can inspect
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

## SSH handshake, one command, and a file transfer

The second application scenario connects a `computer`, an `ethernet-switch`, a `gateway` (DHCP), and a
`linux-host`. No SSH client, server, or protocol existed anywhere in the codebase before this scenario;
`com.a2z.nsdl.ssh` adds a deliberately small one, in the same style as `dhcp`/`print` -- typed immutable
messages over UDP, not wire bytes, and explicitly **not** a real SSH implementation:

```text
computer                          linux-host
   |------ HELLO(version) ------------>|
   |<----- HELLO(version) -------------|
   |------ AUTH(user, pass) ---------->|
   |<----- AUTH-REPLY(ACCEPTED) -------|
   |------ EXEC(neofetch) ------------>|
   |<----- OUTPUT(text, exit=0) -------|
   |------ FILE-START(name, size) ---->|
   |------ FILE-CHUNK(0, bytes) ------>|
   |------ FILE-CHUNK(1, bytes) ------>|
   |------ FILE-CHUNK(2, bytes) ------>|
   |------ FILE-COMPLETE(name) ------->|
   |<----- FILE-RESULT(accepted) ------|
   |------ DISCONNECT ----------------->|
```

Modeled: a version exchange, password authentication against one configured account, exactly one
exec'd command (`neofetch`, `whoami` and `pwd` return fixed output with exit code 0; anything else
returns `command not found` with exit code 127), and one chunked file transfer validated the same way
the print server validates a job. **Not** modeled, deliberately: key exchange or encryption of any
kind, host-key verification, an interactive shell, multiple commands or channels within one session,
and concurrent sessions from the same client. A real terminal session is out of scope for this slice;
this is "SSH-shaped" the way the print protocol is "IPP-shaped" -- enough to teach the session
structure (handshake, authenticated exchange, semantic commands, a bulk transfer) without claiming
protocol compliance.

`SshClient` exposes the whole session as a single `Actionable` action, `openSession`: every step after
that first call -- auth, the command, the transfer, the disconnect -- is triggered reactively by the
server's previous reply, the same way `DhcpClient` reacts to each DHCP message. There is no retry or
timeout machinery because this teaching model has no failure mode that needs one.

The deterministic integration test in `SshSessionIntegrationTest` drives the protocol classes directly
over one cable. `com.a2z.nsdl.scenario.teaching.sshSessionScenario()` is the same session expressed as
runnable scenario data (see `SshSessionScenarioTest`): it adds the gateway's DHCP pool and the switch,
waits for both hosts to acquire addresses, then opens the session addressing the host live rather than
hard-coding it, exactly as the print job scenario does for the printer.

Suggested student checks:

1. Compare the `AUTH`/`AUTH-REPLY` exchange with a wrong password to the accepted case.
2. Run an unrecognized command and explain the `127` exit code.
3. Correlate the three `FILE-CHUNK` packets with the file's declared size and chunk count.
4. Contrast this scenario's `DecisionRecorded` switch-forwarding evidence with the print job
   scenario's -- the same switch logic explains forwarding for either application protocol.

## ARP lesson

Five small scenarios, one per beat of the lesson, each starting from the same office: `gateway1`
(DHCP and DNS), `switch1`, `computer1` and `printer1`. They ping by IPv4 address, so ARP is the only
address resolution involved. Two assertions exist for them: `ArpRequestsSent` counts the ARP
requests an interface broadcast (optionally only those for another endpoint's address), and
`ArpResolved` checks that one interface's ARP table maps another's address to its MAC. Both read
addresses when they are evaluated, since DHCP assigns them during the run.

| Scenario | What happens | What it shows |
|---|---|---|
| `arp-first-contact` | one ping to the printer | one broadcast request; both sides learn each other, so the printer never has to ask |
| `arp-cache` | two pings | still one request: the second ping uses the cache |
| `arp-forget` | ping, `clearArp`, ping | two requests; the printer, which kept its cache, still asks nothing |
| `arp-unanswered` | ping an address nobody has | the ping waits in `arpPending`, then times out with "no ARP reply from ..." |
| `arp-via-router` | ping `8.8.8.8` | the computer asks for the gateway's MAC, not the destination's |

Run one with `nsdl scenario run arp-first-contact`; each assertion is worded as the point it makes,
e.g. "the printer learns the computer's MAC from the request itself".

## Printing by name

`print-by-name` is the software-layer counterpart of the computer-to-printer job: the computer's
`print-spooler` prints `minutes.txt` on `printer1` by name. The assertions trace the layers beneath
that one action -- a DNS lookup, an ARP request for the DNS server, then one for the printer -- and
check that the printer recorded the job as `computer1/job-1` with all 6000 bytes.

