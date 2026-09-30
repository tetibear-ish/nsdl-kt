# Architecture

NSDL is a deterministic, behavioral discrete-event network simulation. It
models typed immutable messages and protocol state, not wire-compatible bytes,
electrical signaling, or host networking.

## Responsibilities and dependency direction

Dependencies point inward toward the simulation core:

```text
sim <- model/net <- link <- ip <- dhcp/device <- app <- nsdl/events/runtime <- ipc <- Composition/Main
```

- `sim` owns deterministic virtual time, scheduled work, and cancellation.
- `model` and `net` define identity, snapshots, events, addresses, and packets.
- `link` models Ethernet interfaces and passive point-to-point cables.
- `ip` provides the minimal IPv4/UDP host stack.
- `dhcp` and `device` provide protocol state machines and reusable host lifecycle.
- `app` validates types and commands before changing simulation state.
- `nsdl` defines topology input; `events` sequences retained event history;
  `runtime` confines mutations to one thread and journals accepted input.
- `ipc` maps the application API to versioned NDJSON over loopback TCP.
- `web` adapts the same runtime to HTTP commands and bounded SSE subscriptions;
  the separate Kotlin/Wasm target runs the portable core directly in a browser.
- `Composition.kt` is the composition root. It is the only place that chooses
  the scheduler, event hub, registered object types, and runtime concretes.

The simulation core has no dependency on JSON, IPC, persistence, parsing, or
host sockets. The runtime's single named daemon thread is the only place that
mutates simulation state.

## Object lifecycle and extension

A device owns a generation-scoped `PowerLifecycle`. Power-on begins a new
generation and schedules boot completion. Once booted, interfaces are enabled
before services start. Power-off stops services, disables interfaces, and
invalidates scheduled work from that generation. Repeated power commands are
no-ops.

To build another host type:

1. Use `HostBuilder` with its object id, type name, scheduler, and event sink.
2. Add interfaces with `ethernet(name, mac)`; each call returns its `Ipv4Stack`.
3. Attach services implementing `DeviceService`.
4. Call `build` with a boot-duration supplier.

To expose the device through commands, implement `ObjectType`: declare its
`ObjectTypeSchema`, construct a `SimObject`, and register it in `Composition`'s
`TypeRegistry`. Property validation fills defaults and rejects missing,
unknown, or malformed values before `create` runs. Mutable configuration must
also provide a `SimObject.configure` callback.

## Link model

The CAT5 object explicitly selects the `100BASE-TX` profile. It is a
point-to-point, full-duplex link with 1 ms propagation delay. The nominal speed
is informational: there is no serialization delay, loss, collision, electrical
behavior, duplex negotiation, or ARP.

A link is operational only when its cable has two endpoints and both interfaces
are enabled. Powering a device off leaves the cable attached but takes the link
down. An in-flight frame is dropped as `DISCONNECTED_IN_FLIGHT` if the cable is
disconnected or reconnected before arrival, and as `RECEIVER_DISABLED` if its
receiver powers off. Other drops include `LINK_DOWN`, `NOT_FOR_US`, and
`NO_LISTENER`.

The four-port `ethernet-switch` is a powered learning bridge. Its ports receive
promiscuously, learn source MAC addresses, forward known unicasts to one port,
and flood broadcasts and unknown unicasts to every other port. Hosts retain
normal destination-MAC filtering. Each host-to-switch attachment still uses an
independent point-to-point cable.

## Browser modes

The server-backed web lab keeps one JVM `SimulationRuntime` authoritative.
Browsers submit version-1 command envelopes over HTTP and receive the sequenced
event stream over SSE, so several clients can inspect the same topology. The
HTTP adapter never mutates simulation objects directly.

The standalone Kotlin/Wasm build shares scheduler, model, link, IP, DHCP,
device, application, and JSON sources with the JVM build. It replaces only the
process boundary: commands execute synchronously against a browser-local
`SimulationService`. It is useful for offline, single-user experimentation;
the server-backed mode is preferred for shared or persistent sessions.

## Persistent and volatile state

Persistent object configuration, such as boot time, MAC address, static IP,
and DHCP pool, survives a power cycle. DHCP client state and acquired network
configuration, DHCP server offers and leases, and operational link state are
cleared at power-off.

## DHCP subset

The simulation implements the DHCPv4 acquisition sequence
DISCOVER -> OFFER -> REQUEST -> ACK. Clients use UDP port 68, servers use port
67, and messages are broadcast with the broadcast flag. Replies are matched by
transaction id, client hardware address, and server identifier; the first offer
wins, and configuration is installed only after a matching ACK.

Retries start at 4 seconds, use seeded jitter of plus or minus 1 second, and
back off to 64 seconds. Four unanswered REQUESTs or a NAK restart acquisition
with a new transaction id. Per-object randomness derives from the runtime seed
and object id, making a journal replay deterministic for the same seed.

Renewal, rebinding, lease expiry, DECLINE, conflict detection, RELEASE, INFORM,
relays, INIT-REBOOT, client identifier option 61, and other DHCP options are
deferred. This is deliberately not full RFC 2131 compliance.
