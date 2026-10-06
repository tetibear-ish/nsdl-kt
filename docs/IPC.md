# IPC protocol version 1

The server listens only on `127.0.0.1` and exchanges UTF-8, newline-delimited
JSON. Each line is one complete message. Integers are JSON integers and decode
as 64-bit values. The default maximum request line is 1 MiB.

## Requests and replies

Every request has this envelope:

```json
{"v":1,"id":"request-1","op":"listObjects","params":{}}
```

`id` correlates a reply with a request. A successful reply contains the event
revision after the command, whether state changed, and operation-specific data:

```json
{"type":"result","id":"request-1","revision":12,"changed":false,"data":[]}
```

An error reply is structured and does not mutate simulation state:

```json
{"type":"error","id":"request-1","error":{"code":"UNKNOWN_OBJECT","message":"no object with id 'missing'","details":{}}}
```

## Operations

| Operation | Parameters |
|---|---|
| `listTypes` | none |
| `listObjects` | none |
| `inspect` | `id` |
| `create` | `id`, `type`, optional `props` object |
| `applyTopology` | `objects` array and `connections` array |
| `connect` | `cableId`, endpoint strings `a` and `b` |
| `disconnect` | `cableId` |
| `configure` | `id`, `props` object |
| `powerOn`, `powerOff` | `id` |
| `invoke` | `id` of an actionable component, `action`, optional `params` object |
| `advance` | `durationMs` |
| `subscribe` | optional `objectId`, optional `types`, optional `from`; required `capacity` |
| `unsubscribe` | `subscriptionId` |

Endpoint strings use component ids such as `printer.eth0`. `applyTopology`
objects have the same fields as `create`; connections have the same fields as
`connect`. The whole topology is validated against shadow state before any
object is created, so rejection emits no events and leaves no partial objects.

Power replies report command acceptance; `BootCompleted` is a later event.
Connecting the same cable to the same endpoint pair, disconnecting an already
disconnected cable, and repeating a power state return `changed:false` without
emitting an event. `configure` accepts only mutable properties, emits
`ConfigurationChanged`, and applies lifecycle settings such as `bootMs` on the
next power-on. `advance` data contains `nowMs`, `eventsProcessed`, and
`truncated`.

### Example topology

```json
{"v":1,"id":"1","op":"create","params":{"id":"printer","type":"printer"}}
{"v":1,"id":"2","op":"create","params":{"id":"server","type":"gateway","props":{"address":"10.0.0.1","poolStart":"10.0.0.100","poolEnd":"10.0.0.110"}}}
{"v":1,"id":"3","op":"create","params":{"id":"cable","type":"cat5-cable"}}
{"v":1,"id":"4","op":"connect","params":{"cableId":"cable","a":"printer.eth0","b":"server.eth0"}}
```

## Events and subscriptions

`subscribe` returns a generated `subscriptionId`. Matching retained events with
sequence numbers greater than `from` are replayed, followed by live events.
`objectId` includes events from that object's child components; `types` is a
list of event type names. An event push has this shape:

```json
{"type":"event","subscriptionId":"sub-1","seq":13,"timeMs":3000,"source":"printer","eventType":"BootCompleted","correlationId":"request-8","data":{"generation":1}}
```

Event types are `ObjectCreated`, `PowerOnStarted`, `BootCompleted`, `PoweredOff`,
`Connected`, `Disconnected`, `LinkStateChanged`, `FrameSent`, `FrameReceived`,
`FrameDropped`, `PacketAccepted`, `ProtocolStateChanged`,
`NetworkConfigChanged`, `ConfigurationChanged`, `DecisionRecorded`,
`ActionPerformed`, `PacketObserved`, and `ApplicationEvent`.

`ApplicationEvent` comes from the software layer (source = an application such
as `pc1.browser`) with `application`, `activity` (e.g. `navigate`, `loaded`,
`failed`, `queued`, `accepted`), and `detail`. Network-level activity stays in
the other event types; for example ARP resolution is a `ProtocolStateChanged`
with protocol `arp` on the interface, and ARP frames appear in `PacketObserved`
with `protocol` `ARP`.

Each subscription has a bounded queue. A slow consumer cannot block the
simulation thread. If its queue overflows, the server sends a terminal gap and
closes that subscription:

```json
{"type":"gap","subscriptionId":"sub-1","resync":true}
```

## Snapshot, reconnect, and revision

The `revision` on a result is the latest global event sequence. To establish a
consistent view without missing events:

1. Obtain snapshots with `listObjects` or `inspect` and retain the result's
   `revision`.
2. Subscribe with `from` set to that revision.
3. Apply replayed events and then live events in sequence order.

On reconnect, subscribe from the last fully applied sequence. If `from` is
older than retained history, the server returns `CURSOR_EXPIRED`; take fresh
snapshots and subscribe from their revision. Treat a `gap` the same way.

## Stable error codes

The protocol can return:

```text
INVALID_REQUEST, INVALID_ID, DUPLICATE_ID, UNKNOWN_TYPE, UNKNOWN_OBJECT,
UNKNOWN_ENDPOINT, MISSING_PROPERTY, INVALID_PROPERTY, UNKNOWN_PROPERTY,
IMMUTABLE_PROPERTY, NOT_A_CABLE, NOT_POWERABLE, INCOMPATIBLE_MEDIA,
SELF_CONNECTION, CABLE_OCCUPIED, ENDPOINT_OCCUPIED, LIMIT_EXCEEDED,
IDEMPOTENCY_CONFLICT, CURSOR_EXPIRED, UNSUPPORTED_VERSION, UNKNOWN_OP, INTERNAL
```

Malformed JSON and invalid parameter shapes use `INVALID_REQUEST`. Unknown
operations use `UNKNOWN_OP`, and any version other than `1` uses
`UNSUPPORTED_VERSION`. Unexpected runtime exceptions are contained and returned
as `INTERNAL`; the simulation thread remains available.

## HTTP and browser event transport

Running `web --port 8080` serves the browser client and two loopback HTTP
endpoints. `POST /api/command` accepts one version-1 request object using the
same envelope, operations, results, and errors documented above. Subscription
operations are not accepted on this endpoint.

`GET /api/events?from=REVISION` opens a `text/event-stream`. Each SSE `data`
field contains the same event or gap JSON object used by the NDJSON transport.
A stale revision returns HTTP 409 with a `CURSOR_EXPIRED` error. Browser clients
should reconnect using the last applied sequence, and re-snapshot if they
receive a gap or expired cursor.

The HTTP listener, like the TCP listener, binds only to `127.0.0.1`. It has no
authentication and is not intended to be exposed directly to an untrusted
network.
