# Architecture

NSDL is a deterministic, behavioral discrete-event network simulation. It
models typed immutable messages and protocol state, not wire-compatible bytes,
electrical signaling, or host networking.

## Responsibilities and dependency direction

Dependencies point inward toward the simulation core:

```text
sim <- model/net <- link <- ip <- dhcp/dns/http/print/ssh/device <- platform <- app <- nsdl/events/runtime <- ipc <- Composition/Main
sim/model <- software <------------------------------------------'
```

- `sim` owns deterministic virtual time, scheduled work, and cancellation.
- `model` and `net` define identity, snapshots, events, addresses, and packets.
- `link` models Ethernet interfaces and passive point-to-point cables.
- `ip` provides the minimal IPv4/UDP host stack, ARP, and routing.
- `dhcp`, `dns`, `http`, `print`, `ssh`, and `device` provide protocol state
  machines and reusable host lifecycle. This is the network layer.
- `software` is the software layer: an operating system and applications that
  depend only on `sim`, `model`, and their own `NetworkServices` interface.
- `platform` implements `NetworkServices` with a host's protocol clients. It is
  the only package that sees both layers.
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
behavior, or duplex negotiation.

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

## Address resolution

Each `Ipv4Stack` resolves unicast next hops with ARP: the destination when it
is on the local subnet, otherwise the configured router (a router forwarding a
packet uses its route's next hop). The first packet to an unknown neighbor is
queued and a broadcast `who-has` request is sent; the owner replies unicast and
learns the requester's mapping, and queued packets are then sent. Requests for
other addresses are ignored silently. There are no ARP timers: a queue holds at
most 8 packets per next hop, and overflowing it drops the oldest packet as
`ARP_UNRESOLVED` and repeats the request. An off-subnet destination with no
router is dropped as `NO_ROUTE`. Callers may still pass an explicit MAC, which
bypasses resolution. Gratuitous ARP, proxy ARP, and cache aging are deferred.

An interface's inspected state lists its ARP table (`arp`) and the next hops
it is still waiting on (`arpPending`: address, packets queued, requests sent).
Invoking `clearArp` on an interface (e.g. `pc1.eth0`) forgets every resolved
neighbor, like `arp -d`.

Every IPv4 stack answers an ICMP echo request addressed to it. `computer` and
`linux-host` have a ping client: invoking `ping` on `<id>.ping` with
`{"address": "192.168.1.101"}` sends one echo request and records a reply with
its round-trip time, or after 2 seconds a timeout naming the likely cause (e.g.
`timed out: no ARP reply from 192.168.1.101`). ICMP is not translated by NAT.

## Network address translation

A `routed-gateway` with `nat=true` and a `wanAddress` masquerades its lan behind
that address, for UDP. A packet from lan leaving through wan gets the wan
address as its source and a port from 50000 up; the same inside address and port
keep one mapping. A packet to the wan address is translated back only when its
destination port is mapped and its source is a peer the inside host already
sent to (port-restricted). `portForwards`, e.g.
`8080>192.168.1.50:80, 2222>192.168.1.60:22`, adds static rules that accept any
peer, and replies from the forwarded host leave on the forwarded port. Every
translation and NAT drop is a `DecisionRecorded` event with responsibility
`nat` (decision `TRANSLATE` or `DROP`), next to the routing decision; the
router's inspected state lists the mappings. Dynamic mappings have no idle
timeout and are cleared when the lan or wan link goes down. Hairpinning, ICMP,
and port-preservation are not modeled.

## Names and the web

Hosts announce their object id as a DHCP host name (option 12). The `gateway`
and a `routed-gateway` with a DHCP pool run a DNS server that answers for its
own id (a static record) and for every bound, named lease (dynamic records that
disappear when the lease expires or the gateway stops); clients are offered it
as their name server (option 6). A workstation's DNS resolver caches positive
answers, shares concurrent lookups, and retries an unanswered query twice at
2-second intervals. The teaching DNS has one question and one A answer: no
zones, other record types, recursion, or TTLs.

The teaching web protocol is a single `GET` and a response carrying a whole page
(a title and plain text) on UDP port 80. The `web-server` device serves `/` and
`/about`; other paths are a 404 page. The web client waits 5 seconds for a
response and does not retransmit.

## Software layer

A `computer` runs an `OperatingSystem` on top of its protocol clients. Its
applications are components of the host and are driven with `invoke`:

| Component | Action | Parameters |
|---|---|---|
| `<id>.browser` | `open` | `url` such as `intranet`, `intranet/about`, or `http://intranet/` |
| `<id>.browser` | `reload` | none |
| `<id>.print-spooler` | `print` | `document`, `printer` (host name), optional `pages` |
| `<id>.terminal` | `ssh` | `target` (`user@host`), `password`, `command` |
| `<id>.terminal` | `clear` | none |

An action's reply only reports whether it was started; its outcome shows up in
the application's inspected state and as `ApplicationEvent`s. Applications
address other machines by name and never see packets, ports, interfaces, or MAC
addresses: they call `NetworkServices` (resolve, fetch a page, print a document,
run a remote command), which `platform.ProtocolNetworkServices` maps onto the
DNS, web, print, and SSH clients. Every application request has a 10-second
timeout, and results that arrive after the host has restarted are discarded.

A timeout says what timed out and why, e.g. `printing minutes.txt on printer1
timed out: no ARP reply from 192.168.1.101`. The cause comes from
`NetworkServices.diagnose`, which reports the interface's own view (link down,
no route, an unanswered ARP request) as plain text that software shows without
interpreting; with no local cause it is `<host> did not respond`. A request
that cannot be sent at all fails at once with that cause instead of timing out.
A print job the printer confirms is `ACCEPTED` (the model has no paper), and it
travels as `<host>/<job>` (e.g. `pc1/job-1`), the name the printer records.
`LayerBoundaryTest` fails the build if `software` imports a network package or
a network package imports `software` or `platform`.

The low-level protocol actions (`<id>.print-client` `submit`, `<id>.ssh-client`
`openSession`) remain for network-level lessons. Their MAC parameters are now
optional and resolved with ARP when omitted.

## Persistent and volatile state

Persistent object configuration, such as boot time, MAC address, static IP,
DHCP pool, and a DNS server's static records, survives a power cycle. DHCP
client state and acquired network configuration, DHCP server offers and leases,
dynamic DNS records, ARP caches, resolver caches, application state, and
operational link state are cleared at power-off.

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

Besides the subnet mask and router, servers offer a name server (option 6), and
clients send a host name (option 12) that the server records with the lease.

Renewal, rebinding, lease expiry, DECLINE, conflict detection, RELEASE, INFORM,
relays, INIT-REBOOT, client identifier option 61, and other DHCP options are
deferred. This is deliberately not full RFC 2131 compliance. Without RELEASE, a
host that powers off keeps its lease (and its DNS name) until the lease expires.
