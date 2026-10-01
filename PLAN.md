# NSDL implementation plan

Kotlin/JVM foundation for NSDL: a deterministic discrete-event network simulation, controlled from other processes over IPC.

## Constraints

- Build: `nix develop --command ./gradlew test --offline --console=plain`. The JDK comes only from the flake.
- Only JUnit 5 is available offline. No kotlin-test, kotlin-reflect, kotlinx-serialization or coroutines, and no new dependencies, so the JSON codec is hand-written.
- Genuine TDD. For each behavior: write a failing test, see it fail for the intended reason, write the minimum code, get to green, then refactor.
- One well-defined commit per slice. Stage explicit paths only. Never commit `kls_database.db`, `.gradle/`, `build/` or `.kotlin/`. Leave the user's `GreeterTest.kt` and `MainTest.kt` untouched.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Dependency direction

```
sim ← model/net ← link ← ip ← dhcp/device ← app ← nsdl/events/runtime ← ipc ← Composition/Main
```

The simulation core (`sim`, `model`, `net`, `link`, `ip`, `dhcp`, `device`) never depends on parsing, IPC, serialization, persistence or host networking. Concrete classes are chosen only in the composition root.

## Part 1: Commit the existing green work

Status: implemented and green, not yet committed.

| # | Commit | Files (`src/{main,test}/kotlin/com/a2z/nsdl/`) |
|---|---|---|
| C1 | `build: run tests on the JUnit 5 platform` | `build.gradle.kts` (JUnit 5 plus failed-test logging) |
| C2 | `docs: add README with nix development instructions` | `README.md` (unchanged) |
| C3 | `feat(sim): add deterministic virtual-time scheduler` | `sim/Scheduler.kt`, `sim/VirtualScheduler.kt`, `VirtualSchedulerTest` |
| C4 | `feat(model): add object identity, snapshots, typed events and packet model` | `model/Model.kt`, `model/Events.kt`, `net/Addresses.kt`, `net/Packets.kt` |
| C5 | `feat(device): add generation-scoped power lifecycle` | `sim/WorkScope.kt`, `device/PowerLifecycle.kt`, `PowerLifecycleTest`, `testing/RecordingSink.kt` |
| C6 | `feat(link): add Ethernet interface and passive point-to-point cable` | `link/Link.kt`, `link/Cable.kt`, `link/EthernetInterface.kt`, `CableLinkTest` |
| C7 | `feat(ip): add minimal IPv4/UDP host stack` | `ip/Ipv4Stack.kt`, `Ipv4StackTest` |
| C8 | `feat(dhcp): add DHCP client acquisition state machine` | `dhcp/DhcpMessage.kt`, `device/DeviceService.kt`, `dhcp/DhcpClient.kt`, `DhcpClientTest` |
| C9 | `feat(dhcp): add minimal DHCP server with address pool` | `dhcp/DhcpServer.kt`, `DhcpServerTest` |

`device/DhcpOverCableIntegrationTest.kt` is held back until S1.

To check that every commit builds, run the suite on each commit in a clean scratch worktree:

```sh
git worktree add --detach "$WT" HEAD
for c in $(git rev-list --reverse 95873dc..HEAD); do
  git -C "$WT" checkout -q "$c" && (cd "$WT" && nix develop --command ./gradlew test --offline --console=plain) || { echo "FAIL $c"; break; }
done
git worktree remove --force "$WT"
```

## Part 2: Remaining TDD slices (one commit each)

### S1. Device composition: `feat(device): compose hosts from interfaces and services`
- `device/Device.kt`:
  - `interface PowerControl { powerState; powerOn(); powerOff() }`.
  - `Device(id, type, interfaces, services, bootDuration, scheduler, events)` owns a `PowerLifecycle`.
  - On boot it enables interfaces, then starts services. On stop it stops services, then disables interfaces.
  - Snapshot: `power`, `bootMs`, `generation`, plus relations to its `interfaces` and `services`.
- `device/HostBuilder.kt`: `ethernet(name, mac): Ipv4Stack`, `service(s)`, `build(bootDuration)`. This is how new device types reuse networking and lifecycle.
- Tests: the 7 existing tests in `DhcpOverCableIntegrationTest`. They cover messages crossing the real cable, no acquisition without the cable, power-off keeping the cable attached with the link down, shutdown while waiting for DHCP, a powered-off server staying silent, the full off → on → acquire → off → on cycle, and the device snapshot.

### S2. Typed cable problems: `refactor(link): report cable connection problems as typed values`
- `ConnectionProblem { ALREADY_CONNECTED, SELF_CONNECTION, INCOMPATIBLE_MEDIA, ENDPOINT_OCCUPIED }` and `ConnectionRejection(problem, endpoint)`.

### S3. Type registry: `feat(app): add object type registry with validated schemas`
- `app/ObjectTypes.kt`:
  - `PropertyType`, `PropertySpec(name, type, required, default, mutable, description)`, `InterfaceSpec(name, media)` and `ObjectTypeSchema`.
  - `interface ObjectType { schema; create(id, props, ctx): SimObject }`.
  - `CreationContext(scheduler, events, random(id), nextMac)`.
  - `SimObject(root, components, power?, endpoints, cable?)`.
- `app/TypeRegistry.kt`: register, find and list types. Registering a duplicate name throws.
- `app/PropertyValidator.kt`: a pure function that collects all errors and fills in defaults.
- `app/types/`:
  - `PrinterType`: `bootMs` (mutable), optional `mac`, `eth0`, DHCP client.
  - `DhcpServerHostType`: static address, pool, lease, optional router.
  - `Cat5CableType`: `profile` defaults to `100BASE-TX`. The profile is explicit and never inferred from the "CAT5" label.
- Tests: types listed with schemas, defaults filled, `MISSING_PROPERTY`, unknown property, `INVALID_PROPERTY` for a malformed IPv4, unknown cable profile rejected.

### S4. Commands: `feat(app): add validated simulation commands with structured results`
- `app/Commands.kt`: a sealed `Command` data type with Create, ApplyTopology, Connect, Disconnect, Configure, PowerOn, PowerOff, Advance, ListTypes, ListObjects and Inspect. Endpoints are referenced by `EndpointRef` (`"printer.eth0"`).
- `app/Results.kt`: `CommandResult` is either `Ok(data)` or `Rejected(CommandError(code, message, details))`. `ErrorCode` codes are stable:

  `INVALID_REQUEST, INVALID_ID, DUPLICATE_ID, UNKNOWN_TYPE, UNKNOWN_OBJECT, UNKNOWN_ENDPOINT, MISSING_PROPERTY, INVALID_PROPERTY, UNKNOWN_PROPERTY, IMMUTABLE_PROPERTY, NOT_A_CABLE, NOT_POWERABLE, INCOMPATIBLE_MEDIA, SELF_CONNECTION, CABLE_OCCUPIED, ENDPOINT_OCCUPIED, LIMIT_EXCEEDED, IDEMPOTENCY_CONFLICT, CURSOR_EXPIRED, UNSUPPORTED_VERSION, UNKNOWN_OP, INTERNAL`
- `app/SimulationService.kt` behavior:
  - **Validation first:** every command is validated before any state changes.
  - **Repeated connect:** the same pair again returns `changed=false` with no events. A different pair on a connected cable is `CABLE_OCCUPIED`.
  - **Repeated disconnect:** returns `changed=false` with no events.
  - **Power:** the reply means the command was accepted. Boot finishing is a separate `BootCompleted` event.
  - **Configure:** only mutable properties can change. The change emits `ConfigurationChanged` and takes effect at the next power-on.
  - **Advance:** bounded by `Limits`. The result reports `truncated` if the event budget ran out.
- Tests:
  - duplicate, invalid and unknown ids and types
  - incompatible media, using a test-only type
  - occupied cable and occupied endpoint
  - repeated connect and disconnect
  - power-on accepted before boot completes
  - immutable configure rejected, and `bootMs` applied at next power-on
  - `UNKNOWN_OBJECT`
  - advance limits

### S5. NSDL topology: `feat(nsdl): add topology spec, builder DSL and atomic apply`
- `nsdl/TopologySpec.kt` (objects and connections).
- `nsdl/TopologySource.kt`: the boundary a future parser will implement.
- `nsdl/TopologyDsl.kt`: a small programmatic builder.
- Atomic apply: validate the whole batch against a shadow state, then apply it. No rollback is needed.
- Tests: a valid topology applies. A failed batch leaves no objects and emits no events. Duplicate ids within a batch are rejected. The DSL produces the same spec as a literal.

### S6. Event hub: `feat(events): add sequenced event hub with replay and bounded subscriptions`
- `EventRecord(seq, timeMs, source, type, payload, correlationId)`.
- `EventHub`:
  - keeps a bounded retention ring; `lastSeq` is the revision
  - filters by object (children included) and by event type
  - `subscribe(filter, from, capacity)` replays events with seq greater than `from`
  - a cursor older than the ring gives `CURSOR_EXPIRED`
- Publishing only offers events to per-subscriber bounded queues, so subscriber code never runs on the simulation thread.
- A full queue means overflow: the subscriber gets `Gap(lastDeliveredSeq)`, the subscription closes, and the consumer re-snapshots or resubscribes from a cursor.
- Tests: stamping, filtering, replay, expired cursor, overflow gap while other subscribers are unaffected, a subscriber that never polls doesn't block, a closed subscription receives nothing.

### S7. Runtime: `feat(runtime): confine simulation to a single thread with journal and idempotency`
- `SimulationRuntime` runs on a single named daemon thread and is the only place state changes.
  - `submit(Request(requestId?, command))` and `subscribe(...)` both execute on the runtime thread.
  - Snapshot results carry `revision = lastSeq`.
  - An unexpected exception becomes an `INTERNAL` result; the thread never dies.
- `IdempotencyCache` (bounded LRU):
  - same request id and same payload returns the cached result, with no journal entry and no events
  - same request id with a different payload gives `IDEMPOTENCY_CONFLICT`
- `InputJournal` records accepted mutating commands in their accepted order.
- Randomness: each object gets `Random(seed xor id.hashCode())`. Rejected commands never draw randomness.
- Tests:
  - commands run on the runtime thread
  - idempotency cache hit and conflict
  - journal contents
  - snapshot plus subscribe-from-revision has no gap
  - replaying with the same seed and journal gives an identical event trace
  - a different seed gives a different DHCP xid

### S8. Composition root: `feat: add composition root wiring runtime concretes`
- `Composition.kt` is the only place that constructs `VirtualScheduler`, the random factory, `EventHub`, the registry with the three types, `SimulationService`, the runtime and the IPC server.

### S9. JSON: `feat(ipc): add hand-written JSON codec`
- `ipc/json/Json.kt`: parse and write Map, List, String, Long, Double, Boolean and null.
- Tests:
  - escapes and surrogate pairs
  - rejected input: control characters, trailing data, leading zeros, duplicate keys, too much nesting
  - NaN rejected on write
  - integers normalized to `Long`

### S10. IPC: `feat(ipc): add NDJSON loopback server and wire protocol v1`
- Transport: newline-delimited JSON over TCP on `127.0.0.1`. Port `0` picks a free port.
- Request: `{"v":1,"id","op","params"}`.
- Ops: `listTypes, listObjects, inspect, create, applyTopology, connect, disconnect, configure, powerOn, powerOff, advance, subscribe, unsubscribe`.
- Replies are `result` or `error` (`{code, message, details}`).
- Pushed messages are `event` (seq, timeMs, source, type, correlationId, data) and `gap` (`resync: true`).
- `WireMapper` uses an exhaustive `when` over `EventPayload`.
- `IpcServer`:
  - transport threads only parse requests and submit them to the runtime
  - one writer lock per connection
  - maximum line length
  - closing a connection closes its subscriptions
- `IpcClient`: correlates requests with replies and has an event queue.
- Tests: malformed JSON, unsupported version, unknown op, create/connect/inspect round trip, structured errors, filtered subscription streaming, expired cursor, unsubscribe, client isolation, and a mapping test for every payload.

### S11. CLI and process boundary: `feat(cli): add serve, example and demo commands`
- `Main.kt` hands off to `Cli.run(args, out, err): Int`:
  - `serve [--port] [--seed]` prints `NSDL_IPC_LISTENING port=N`
  - `example --port N` runs the full slice over IPC and prints the trace
  - `demo` starts an in-process server and runs the example against it
- `ProcessBoundaryTest`: spawns `java -cp <test classpath> com.a2z.nsdl.MainKt serve --port 0`. The test then acts as the client: create, connect, subscribe, power on, advance, inspect `10.0.0.100`, then disconnect twice (`changed:true`, then `changed:false`). Waits are bounded. The `finally` block runs destroy, waitFor and destroyForcibly.

### S12. Docs: `docs: document architecture, IPC protocol and DHCP subset`
Extend the README, keeping the existing Running section, and add `docs/IPC.md` and `docs/ARCHITECTURE.md`. Cover:
- responsibilities and dependency direction
- where concrete classes are wired
- how to run tests, the server, the example and the demo
- reusing networking and lifecycle for a new device
- registering a new type
- IPC schemas, errors and examples
- snapshot/event consistency and reconnect
- the DHCP subset and what is deferred
- the link simplifications

### S13. DHCP lease lifecycle: `feat(dhcp): renew, rebind and expire client leases`
- Extend the DHCP client state machine with `BOUND`, `RENEWING` and `REBINDING` states and generation-scoped virtual-time timers.
- Derive the default lease deadlines from the ACK:
  - T1 at 50% of the lease duration
  - T2 at 87.5% of the lease duration
  - expiry at 100% of the lease duration
- At T1, send DHCPREQUEST renewal attempts to the lease's server identifier while retaining the assigned address.
- At T2, switch to broadcast DHCPREQUEST rebinding so any available DHCP server may extend the lease.
- A valid DHCPACK replaces the active lease, resets T1/T2/expiry from the ACK's lease duration, and returns the client to `BOUND`.
- A DHCPNAK immediately clears the interface IPv4 configuration and restarts discovery when the link is available.
- Lease expiry clears the interface IPv4 configuration, emits `NetworkConfigChanged(null)`, and returns to discovery when the link is available.
- Link disconnection does **not** immediately clear an unexpired lease. Timers continue in virtual time; renewal/rebinding transmissions fail naturally while the link is down.
- Reconnecting before expiry retains the address and resumes the appropriate renewal or rebinding behavior. Reconnecting after expiry starts a fresh DISCOVER.
- Power-off still clears the volatile lease and cancels every lease timer through the existing generation-scoped `WorkScope`.
- Keep server lease accounting minimal for this slice: an ACK to a valid renewal or rebind extends the existing allocation; address reclamation policy may remain separate.
- Tests, written red-green in small commits:
  - acquisition schedules T1, T2 and expiry without clearing the address
  - disconnecting before T1 retains the address
  - a successful T1 renewal keeps the address and reschedules all deadlines
  - failed T1 attempts transition to broadcast rebinding at T2
  - a successful T2 rebind accepts an ACK and reschedules the lease
  - expiry while disconnected clears the address exactly once
  - reconnect before expiry retains the lease; reconnect after expiry emits a new DISCOVER
  - NAK during renewal or rebinding clears configuration and restarts acquisition
  - power-off cancels stale renewal, rebinding and expiry callbacks
  - inspector/event output observes the address disappearing at expiry

## Post-S13 product roadmap

Implement these slices in order unless user feedback changes the priority. Each slice follows the same red-green-refactor and focused-commit rules as the earlier plan.

### Cross-cutting observability model
- Every inspectable device, interface, port, protocol service and application process exposes a bounded decision log in addition to its current snapshot.
- Logs preserve a causal hierarchy rather than presenting one undifferentiated packet stream:
  - **intention**: the user-level goal, such as `establish SSH session`, `run neofetch`, `transfer file` or `submit print job`
  - **process/session**: the originating application process and logical session
  - **protocol exchange**: DNS lookup, TCP connection, DHCP acquisition, print transfer and similar conversations
  - **responsibility**: switching, DHCP service, routing, firewall, NAT, transport or application handling
  - **decision**: the input considered, selected action, reason and alternatives rejected
  - **packet/frame**: the concrete network artifact emitted, received, forwarded or dropped as a consequence
- Every record carries stable causal identifiers where applicable: `intentionId`, `processId`, `sessionId`, `exchangeId`, `packetId`, `parentId`, simulation time, node id, interface/port id and correlation id.
- Decision records are typed facts emitted by the simulation component making the decision; the UI must not infer authoritative decisions from packet observations.
- Logs are deterministic simulation output, bounded by retention, filterable by the causal identifiers above and replayable through the event hub.
- A host log can be grouped by process and session. An SSH example shows DNS/route resolution if needed, a TCP three-way handshake from an ephemeral client port to server port 22, an established session, and semantic intentions such as sending `neofetch` or transferring a file.
- SSH payload display distinguishes simulation semantics from on-wire visibility: an intention may say `send keys: neofetch`, while packet inspection treats encrypted SSH application bytes as opaque unless the scenario explicitly models an endpoint-side view.
- An Ethernet switch records layer-2 decisions: source-MAC learning, destination-MAC lookup, known-unicast forwarding, unknown-unicast/broadcast flooding, ingress suppression and drops. It does **not** claim to route ordinary frames by destination IP.
- A gateway separates logs by responsibility:
  - **DHCP**: pool lookup, offer selection, lease allocation, renewal, rebinding and expiry
  - **routing**: destination lookup, longest-prefix route selection, next hop, egress interface and TTL handling
  - **firewall**: rule evaluation order, matched rule and allow/reject/drop result
  - **NAT/connection tracking**: private flow tuple, public translated tuple, mapping lookup/creation and inbound demultiplexing
- Hosts behind NAT may share one Internet-visible address because the gateway maps protocol/addresses/ports back to private flows. The switch still forwards the resulting Ethernet frames by MAC address.
- Required tests:
  - causal ids join an intention to its process, exchange, decisions and packets
  - concurrent processes and sessions remain separable
  - switch logs explain known unicast, flooding and drops using MAC addresses
  - route logs identify the selected prefix and egress interface
  - firewall logs identify the exact matching rule
  - NAT logs distinguish two private hosts sharing one public address
  - endpoint-side SSH intentions remain correlated with opaque encrypted packet records
  - bounded retention reports an explicit gap rather than silently losing causal history

### S14. Topology persistence: `feat(topology): save, load, import and export labs`
- Define a versioned topology document containing object types, configuration, connections and UI positions.
- Browser-local mode saves the current lab to `localStorage` and restores it explicitly or on reload.
- Add JSON file import/export so a lab is portable between browsers and server-backed mode.
- Validate the complete document before changing the active topology; failed imports are atomic and report precise errors.
- Tests cover round trips, schema versions, malformed documents, duplicate ids, invalid connections and restoration of positions.

### S15. Object deletion and undo: `feat(topology): safely remove and restore objects`
- Add explicit deletion for devices and cables.
- Deleting a device atomically removes its attached cables, or presents a preview of those dependent removals before confirmation.
- Add a bounded client-side undo stack for topology edits such as create, connect, disconnect, move and delete.
- Server-backed mode represents deletion and undo as validated runtime commands rather than client-only graph changes.
- Tests cover attached-cable cleanup, nonexistent objects, repeated deletion, atomic failure and undo ordering.

### S16. Packet activity visualization: `feat(web): display per-port traffic activity`
- Drive transmit and receive lights from `FrameSent` and `FrameReceived` events at the corresponding port and cable endpoint.
- Display a distinct dropped-frame indication using `FrameDropped` and its reason.
- Coalesce high-rate events into bounded visual pulses so traffic cannot cause unbounded rendering or event queues.
- Preserve exact events in the activity stream even when visual pulses are coalesced.
- Make each port inspectable with link state, peer, counters, recent frames and the decisions that caused forwarding or drops.
- Tests cover endpoint mapping, TX/RX direction, drop indication, pulse expiry and burst throttling.

### S17. Simulation clock controls: `feat(web): control virtual-time execution`
- Add pause/resume, single-step, and 1×, 2× and 10× speed controls.
- Display current virtual time and whether time is browser-driven or server-driven.
- Clock changes affect simulation advancement without forcing topology redraws when no events occur.
- Define shared-server clock ownership so one client cannot accidentally create competing timers.
- Tests cover pause, resume, speed changes, stepping, elapsed-time accounting and multi-client ownership.

### S18. Structured DHCP inspection: `feat(web): present DHCP state and lease deadlines`
- Replace raw-only DHCP inspection with a concise view of address, subnet, router, server, lease duration, remaining time and client state.
- Keep the complete raw snapshot available in an expandable section.
- Update pinned inspection whenever state or virtual time changes without redrawing the topology.
- Highlight `BOUND`, `RENEWING`, `REBINDING` and expired/unconfigured states.
- Tests cover missing configuration, active leases, renewal transitions, countdown display and expiry.

### S19. Connection ergonomics and validation: `feat(web): guide valid topology wiring`
- Highlight compatible and available ports during a connection drag.
- Label switch ports and make occupied, disabled and incompatible endpoints visually distinct.
- Present structured rejection reasons near the attempted connection.
- Add confirmation or undo affordances for destructive disconnection and deletion.
- Tests cover compatible-port discovery, occupied ports, incompatible media and rejection messages.

### S20. Packet inspection: `feat(web): add a protocol-aware packet event pane`
- Add a bounded, filterable packet stream showing simulation time, source, destination, protocol and ingress/egress port.
- Make every cable inspectable with its own bounded, ordered history at both layers: the Ethernet frames that traversed it and the decoded packets carried by those frames. Preserve direction, endpoint, simulation time, delivery/drop outcome and causal identifiers so a student can move between frame and packet views without counting one transmission twice.
- Decode DHCP message type, transaction id, client address and server identifier from typed simulation packets.
- Include dropped-frame reasons and links back to the involved topology elements.
- Support pause, clear and filters without blocking the simulation runtime.
- Group packets by intention, process/session and protocol exchange, with navigation to the responsible node decision.
- Tests cover event decoding, filtering, bounded retention and drop-reason presentation.

### S21. Routed gateway: `feat(ip): add forwarding and routing tables`
- Replace the single-interface DHCP-server interpretation of `gateway` with a composable multi-interface gateway device.
- Add IPv4 forwarding, directly connected routes, static routes and a default route.
- Keep DHCP service optional and bound to a configured LAN interface.
- Explicitly defer NAT until forwarding and routing behavior are correct; add NAT as a separate follow-up slice.
- Emit responsibility-scoped decision records for route lookup and forwarding; later firewall and NAT slices use the same record model.
- Tests cover same-subnet delivery, routed delivery across two subnets, missing routes, disabled interfaces, TTL handling and DHCP service on the LAN only.

### S22. Collaborative topology editing: `feat(runtime): synchronize multi-client topology sessions`
- Move shared node positions and topology edits into the server-owned session model.
- Broadcast accepted commands and resulting revisions to all connected clients.
- Add client-scoped request identity, optimistic edit reconciliation, reconnect-from-revision and explicit conflict handling.
- Show lightweight client presence without making presence part of deterministic simulation state.
- Tests cover simultaneous edits, idempotency across clients, reconnect, stale revisions, conflict resolution and client departure.

### S23. Scenarios and assertions: `feat(testing): evaluate topology outcomes`
- Define versioned scenarios containing an initial topology, actions, virtual-time bounds and assertions.
- Support assertions such as a printer receiving an address within a declared pool, a link becoming active, or a packet reaching a destination.
- Display pass/fail results with supporting snapshots and relevant event excerpts.
- Make the same scenario runnable from the browser, CLI and automated tests.
- Include teaching scenarios for an SSH handshake/session, commands within that session, file transfer, and a computer submitting a print job.
- Assertions may target causal behavior as well as outcomes, such as requiring a switch MAC-table decision or a gateway firewall rule match.
- Tests cover successful assertions, timeouts, deterministic replay, useful failure output and malformed scenarios.

## Documented simulation model

- **Behavioral simulation:** typed immutable messages, not wire-compatible bytes, and no electrical emulation.
- **Link:** 100BASE-TX point-to-point full duplex with 1 ms propagation delay. The speed is informational only. There is no serialization delay, loss, collisions, duplex negotiation or ARP.
- **Link availability:** the link is up only when the cable is attached at both ends and both interfaces are enabled (device booted).
- **Power off:** the cable stays attached but the link goes down.
- **In-flight frames:** a frame is dropped with `DISCONNECTED_IN_FLIGHT` if the cable was disconnected or reconnected while it was travelling, and with `RECEIVER_DISABLED` if the receiver was powered off.
- **Lifecycle:** each power-on is a new generation with its own work scope. Power-off cancels and invalidates that scope, so stale timers and callbacks can't touch a later run. Repeated power commands do nothing.
- **Persistent vs volatile state:**

  | Survives a power cycle | Cleared at power-off |
  |---|---|
  | Object config: `bootMs`, MAC, static IP, pool | DHCP client state and acquired config (leases are not remembered, so there is no INIT-REBOOT) |
  | | DHCP server offers and leases |
  | | Link state |

- **DHCPv4 subset (RFC 2131):**
  - Acquisition runs DISCOVER → OFFER → REQUEST → ACK, client on port 68 and server on port 67, all broadcast using the BROADCAST flag.
  - Replies are matched on xid, chaddr and server identifier. The first OFFER wins.
  - Configuration is applied only after an acceptable ACK.
  - Retries back off from 4 s up to 64 s with seeded ±1 s jitter. After 4 REQUESTs without an answer, or on a NAK, the client restarts with a new xid.
  - **Planned in S13:** renewal (T1), rebinding (T2), and lease expiry.
  - **Deferred:** DECLINE and conflict detection, RELEASE, INFORM, relays, INIT-REBOOT, client identifier (option 61) and all other options. This is not full DHCP compliance.
