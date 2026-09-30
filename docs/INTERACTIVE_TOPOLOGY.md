# Interactive topology visualizer recommendation

## Requested experience

The topology editor should support these interactions:

- Right-click the canvas or an existing object to open a context menu.
- Provide an **Add...** button that opens a menu of available network object
  types.
- Add a new network object to the graph as an isolated node with no neighbors.
- New powerable objects start powered off.
- Clicking a node toggles it between powered on and powered off.
- Users create connections by dragging between connection points on nodes.
- An eight-port switch visibly exposes eight independent connection points.
- Users disconnect a cable by grabbing its endpoint and dragging it away from
  the node.
- Each connected endpoint has an activity light that flashes when its interface
  emits or receives a frame or packet.
- Users can inspect every object and see its configuration, power state,
  interfaces, connections, and relevant protocol state.
- Multiple users can open the same server-hosted topology, modify it
  concurrently, and see accepted changes appear in every connected client.
- The visualizer should work with both the shared JVM simulation and the
  standalone Kotlin/Wasm simulation.

## Recommendation

Use a React and TypeScript frontend built with Vite and React Flow:

- **React + TypeScript** for the UI and typed simulation projections.
- **Vite** for development and production builds.
- **`@xyflow/react` (React Flow)** for nodes, port handles, connections,
  selection, panning, zooming, and edge reconnection.
- **Radix UI** for accessible context menus, dropdown menus, dialogs, and
  tooltips.
- **Zustand** for transient per-client editor state such as viewport, selection,
  menus, and pending gestures. Shared node positions belong to the server
  workspace rather than this local store.
- The existing **Kotlin JVM runtime with HTTP commands and SSE events** for
  shared simulations.
- A **Kotlin/Wasm transport adapter** for standalone browser-local simulations.
- **Vitest and React Testing Library** for component tests.
- **Playwright** for pointer-driven topology tests such as creating,
  connecting, reconnecting, and disconnecting nodes.

React Flow is a better fit than a general graph-analysis library because its
custom nodes can expose multiple uniquely identified handles. It also provides
node and edge context-menu events and an edge reconnection lifecycle. Relevant
documentation:

- [React Flow handles](https://reactflow.dev/learn/customization/handles)
- [React Flow context-menu example](https://reactflow.dev/examples/interaction/context-menu)
- [React Flow component and reconnection API](https://reactflow.dev/api-reference/react-flow)
- [Radix Context Menu](https://www.radix-ui.com/primitives/docs/components/context-menu)

## Architecture

The editor should depend on a transport interface rather than directly on
HTTP, SSE, or Wasm:

```text
React topology editor
        |
        +-- RemoteTransport -- HTTP commands + SSE -- JVM SimulationRuntime
        |
        +-- WasmTransport ---- direct calls -------- browser-local simulation
```

```ts
interface SimulationTransport {
  listTypes(): Promise<ObjectTypeSchema[]>;
  listObjects(): Promise<ObjectSnapshot[]>;
  execute(command: Command): Promise<CommandResult>;
  subscribe(from: number, listener: EventListener): Unsubscribe;
}
```

Components should receive this interface through application context. They
must not contain transport-specific branching. Both implementations should
produce the same snapshots, structured errors, revisions, and event records.

The JVM runtime remains authoritative in server mode and must support
concurrent clients. Transport handler threads may submit simultaneously, but
the runtime orders and executes every accepted mutation on its single
simulation thread. Graph mutations are displayed only after the command is
accepted. SSE events update all connected browsers. In standalone mode, the
Wasm adapter applies commands to its local simulation and publishes equivalent
updates to the editor.

## Multi-user collaboration

The server-hosted editor is a shared workspace. Every browser connected to the
same workspace observes and modifies the same simulation and topology layout.
This is distinct from standalone Wasm mode, where each browser owns an isolated
local simulation.

### Current implementation status

The existing server mode already provides part of this behavior:

- Multiple TCP or HTTP client threads can submit concurrently to one
  authoritative `SimulationRuntime`; its executor serializes commands into a
  deterministic accepted order.
- Runtime subscriptions are also established through that execution boundary,
  preventing a snapshot-to-subscription race.
- Accepted simulation mutations produce globally sequenced events.
- The HTTP server broadcasts those events through SSE subscriptions.
- The current browser client refreshes its graph when an SSE event arrives.
- Snapshot revisions and replay cursors allow a reconnecting client to catch up
  without missing retained simulation events.

This means object creation, power changes, configuration, connections,
disconnections, and virtual-time advancement can already update multiple open
clients. However, client request IDs are not yet safely scoped: independent
clients can both generate values such as `c1` or `web-1`, while the runtime's
idempotency cache is global. Before treating the service as fully multi-client,
request identity must be globally unique or keyed by `(clientId, requestId)`.

Shared node coordinates, user presence, selections, and drag previews are also
**not** currently synchronized. The present graph computes its layout in each
browser independently.

### Runtime concurrency contract

`SimulationRuntime` is the concurrency boundary for all authoritative changes.
It should explicitly guarantee:

- `submit` is safe to call concurrently from any number of transport threads.
- Each command executes to completion before the next command begins.
- Results, journal entries, randomness draws, events, and revisions reflect one
  deterministic total accepted order.
- Validation and mutation occur together on the runtime thread, so two clients
  cannot both reserve the same endpoint or object ID.
- `subscribe` is ordered with commands on the runtime thread, preserving the
  snapshot-plus-subscribe-from-revision guarantee.
- One client's malformed request, rejection, disconnect, or slow event consumer
  cannot block or terminate the runtime or another client.
- Idempotency is scoped by a server-issued `clientId` plus caller request ID, or
  callers use collision-resistant request IDs such as UUIDs.

The preferred protocol identity is:

```text
RequestIdentity(clientId, requestId)
```

Reconnects may retain the same `clientId` when they intend to retry earlier
requests. Two different clients using the same local `requestId` must never
share a cached result or cause `IDEMPOTENCY_CONFLICT`.

### Shared workspace state

Keep behavioral simulation state inside `SimulationRuntime`, and add a small
server-owned workspace state for visual topology metadata. Its mutations must
also pass through the runtime execution boundary (or an equivalently ordered
workspace command queue); it must not be an independently mutated map beside
the runtime:

```ts
type SharedWorkspace = {
  workspaceId: string;
  layoutRevision: number;
  positions: Record<string, { x: number; y: number }>;
};
```

Node coordinates must be stored centrally so moving a node in one browser
moves it in every other browser. Viewport, zoom, current selection, open menus,
and unfinished connection gestures remain local because they describe an
individual user's view rather than the topology itself.

The server should expose explicit layout operations rather than treating
coordinates as simulation configuration:

```text
MoveObject(objectId, x, y, baseLayoutRevision)
MoveObjects([{ objectId, x, y }], baseLayoutRevision)
```

Accepted moves increment `layoutRevision` and publish a `LayoutChanged` event.
Drag updates should be throttled, with one final unthrottled update on pointer
release. A multi-object move should be atomic.

### Command and conflict rules

Clients may optimistically show a pending gesture, but authoritative graph
state changes only after server acceptance. Each request needs a unique
`(clientId, requestId)` identity so retries remain idempotent without colliding
with another client.

Concurrent simulation commands use the runtime's serial accepted order and
existing validation rules:

- Two users creating the same ID: one succeeds; the other receives
  `DUPLICATE_ID`.
- Two users connecting the same free port: the first accepted command wins;
  the other receives `ENDPOINT_OCCUPIED`.
- Power controls send explicit `PowerOn` or `PowerOff`, never a server-side
  `TogglePower`, so a stale client cannot accidentally invert newer state.
- Disconnecting an already disconnected cable remains an idempotent no-op.

Layout updates should use `baseLayoutRevision`. A stale move can either be
rejected with a dedicated conflict result or use documented last-accepted-write
semantics. Rejection is preferred for the final pointer-up update because it
lets the client resnapshot instead of silently overwriting a newer placement.

### Client synchronization

On initial load or reconnect, a remote client should:

1. Fetch the simulation snapshot, shared layout, and their revisions.
2. Render that combined state.
3. Subscribe from the returned event cursor.
4. Apply simulation and layout events in sequence.
5. If it receives `CURSOR_EXPIRED` or a gap, discard optimistic state and take
   a fresh combined snapshot.

Every accepted add, power, configure, connect, disconnect, move, or delete
operation must therefore be visible in every subscribed client without a page
reload. Events should carry an optional `actorId` so the initiating client can
match its pending gesture while other clients can identify remote activity.

### Presence

Presence is useful but not required for correct shared editing. A later
ephemeral channel may publish user name/color, cursor, selected objects, and
the object currently being dragged. Presence must not enter the deterministic
simulation journal and should expire automatically when a client disconnects.
It should never lock an object indefinitely; at most it provides short-lived
advisory drag ownership.

## Graph projection

Devices are visual nodes. Cables are visual edges. Interfaces are handles on
their owning device node.

```ts
type TopologyNode = {
  id: string;
  type: string;
  power: "OFF" | "BOOTING" | "ON";
  position: { x: number; y: number };
  ports: Array<{
    id: string;       // for example, "switch1.port1"
    media: string;
    occupied: boolean;
    linkUp: boolean;
  }>;
};

type TopologyEdge = {
  id: string;           // cable object ID
  source: string;       // source device ID
  sourceHandle: string; // full source endpoint ID
  target: string;       // target device ID
  targetHandle: string; // full target endpoint ID
};
```

Simulation state and editor layout state should remain separate. Power,
configuration, endpoints, and cable attachment come from NSDL snapshots and
events. In server mode, node coordinates come from the shared workspace and
its layout events. Viewport, selection, open menus, and pending gestures remain
local to each editor. Standalone Wasm mode may persist positions locally.

## Adding objects

The toolbar and blank-canvas context menu should expose the same **Add...**
action. Its entries should be generated from `listTypes`, not hard-coded.

```text
Add...
|-- Printer
|-- DHCP gateway
`-- Ethernet switch
    |-- 4 ports
    |-- 8 ports
    `-- 16 ports
```

Selecting a type opens a property form generated from its schema. After the
user confirms it, the editor should:

1. Submit `Create` with the chosen type, ID, and properties.
2. Wait for an accepted result.
3. Place the returned object at the context-menu pointer or viewport center.
4. Render it as an isolated, powered-off node.
5. Expose its interfaces as connection handles.

A rejected creation remains absent and its structured error is shown in the
form.

## Power interaction

Clicking the body of a powerable node sends `PowerOn` or `PowerOff`. The click
handler must ignore gestures that started on a connection handle, interactive
control, or node drag. An explicit power icon and context-menu action should
also be present so the behavior is discoverable and keyboard accessible.

Suggested visual states:

- Gray: powered off.
- Amber or pulsing: booting.
- Cyan/green: powered on.
- Red status badge: most recent command was rejected or the object has a
  reported problem.

Power command acceptance and boot completion are distinct. The UI should enter
the booting state from `PowerOnStarted` and enter the on state only after
`BootCompleted`.

## Creating connections

Each interface is one React Flow handle whose ID is the complete endpoint ID.
Use loose connection mode because Ethernet endpoints are peers rather than
directed source/target ports.

When a user drags from one free compatible handle to another, the editor
should validate obvious media and occupancy constraints locally for immediate
feedback. The runtime remains authoritative. If cables are created implicitly,
submit cable creation and attachment as one atomic topology command:

```text
ApplyTopology(
  Create("cable-12", "cat5-cable"),
  Connect("cable-12", "printer1.eth0", "switch1.port3")
)
```

If several compatible cable types or profiles exist, show a small chooser
after the drop. Do not add the visual edge until the command succeeds.

Port handles should distinguish free, occupied, link-down, link-up, and invalid
drag-target states.

## Port activity lights

Every connected endpoint should render a small activity light adjacent to its
handle. The light represents activity at that specific simulated interface,
not generic animation along the whole cable.

The existing event stream already provides the necessary source information:

- `FrameSent` flashes the transmitting endpoint.
- `FrameReceived` flashes the receiving endpoint.
- `PacketAccepted` may optionally produce a second, visually distinct protocol
  pulse, but it must not be counted as another physical frame.
- `FrameDropped` may briefly flash an error color at the interface or cable
  that reported the drop.

For a successful exchange, users should therefore see the source-side light
flash when the frame is emitted and the destination-side light flash when the
frame arrives after the simulated propagation delay. Both server-backed and
Wasm transports must deliver the same event-to-port behavior.

Activity indication is presentation state and must not be written back into
the deterministic simulation. The React client should maintain a transient map
such as:

```ts
type PortActivity = Record<string, {
  sentAt?: number;
  receivedAt?: number;
  droppedAt?: number;
}>;
```

Event `source` IDs map directly to React Flow handle IDs, for example
`switch1.port3`. An incoming event updates that handle's transient activity
state and starts a short CSS animation.

Recommended visual behavior:

- Sent frame: bright amber pulse.
- Received frame: bright cyan pulse.
- Dropped frame: short red pulse.
- Operational but idle link: dim green or cyan.
- Link down: unlit gray.
- Disconnected port: no activity light or an empty socket treatment.

Use a 120–250 ms pulse measured in browser wall-clock time. Simulation time may
advance by several seconds in one command, so tying animation duration directly
to virtual time would make activity invisible. Preserve event order, but queue
or coalesce bursts per animation frame so a large virtual-time advance cannot
create thousands of DOM animations or freeze the editor. A sustained burst may
hold the light bright and decay after the last event.

The visualizer must continue consuming events even when animation is disabled.
Respect `prefers-reduced-motion` by replacing pulses with a brief color/intensity
change, and never rely on color alone: inspection details should expose sent,
received, and dropped counters per port.

For multi-user server mode, these activity events already come from the shared
sequenced SSE stream, so every connected client observes the same simulated
traffic. Animation start time can differ slightly by browser delivery latency;
event sequence and counters remain authoritative.

## Disconnecting and reconnecting

Use React Flow's edge reconnection gesture. A user grabs an existing edge
endpoint and drags it:

- Dropping it on another valid free port requests a reconnect operation.
- Dropping it over empty canvas requests `Disconnect(cableId)`.
- Dropping it on an invalid or occupied port leaves the original connection in
  place and displays the rejection.

The present command model has `Disconnect` but no atomic reconnect command.
Initially, reconnect can be expressed as disconnect followed by connect, but a
dedicated atomic `Reconnect` command is preferable so a rejected destination
cannot leave the original cable detached.

Disconnecting does not delete the cable object. The UI may retain disconnected
cables in an inventory panel. Permanently removing objects will require a
future validated `Delete` command.

## Switch ports and schema changes

The current registered `ethernet-switch` type is fixed at four ports. It should
be extended before implementing the eight-port node:

```text
ports: LONG
default: 8
minimum: 2
maximum: 48
mutable: false
```

The object-type schema currently describes a fixed interface list. Variable
port counts require a dynamic interface-group declaration, for example:

```kotlin
data class InterfaceGroupSpec(
    val prefix: String,
    val countProperty: String,
    val media: MediaType,
)
```

An eight-port switch created with `ports=8` would then expose `switch1.port1`
through `switch1.port8` in both its schema-derived creation preview and its
runtime snapshot.

## Context menus

Use Radix Context Menu around the canvas and custom node content. Recommended
actions are:

```text
Canvas                              Node
------                              ----
Add...                              Inspect
Fit topology                       Power on/off
Auto-layout                        Configure...
Paste                              Disconnect all
                                    Delete (when supported)
```

Edge context menus should offer inspect cable, disconnect, and delete when
deletion becomes part of the command model. All actions must have equivalent
keyboard-accessible controls outside the context menu.

## Why not Cytoscape.js

Cytoscape.js is a strong choice for large graph analysis and automatic graph
layouts. It offers extensions for context menus and interactive edge creation.
For this application, however, the primary requirement is a node editor with
explicit device ports, custom device controls, and editable cable endpoints.
React Flow models those concepts directly; Cytoscape.js would require several
extensions and more custom coordination. See the
[Cytoscape.js extension catalog](https://js.cytoscape.org/index.html) for the
alternative ecosystem.

## Suggested implementation order

1. Add dynamic switch port counts and create an eight-port switch test.
2. Establish a Vite React/TypeScript application and `SimulationTransport`.
3. Implement server and Wasm transport adapters.
4. Namespace idempotency by client and add concurrent-client runtime tests.
5. Add server-owned workspace positions, layout revisions, and
   `LayoutChanged` streaming through the runtime ordering boundary.
6. Render snapshot-derived custom nodes and port handles.
7. Add schema-driven **Add...** dialogs and isolated-node placement.
8. Add power toggling and event-driven state styling.
9. Add atomic cable creation through handle dragging.
10. Add reconnect/disconnect-on-empty-drop behavior.
11. Add event-driven per-port transmit, receive, and drop activity lights with
    burst coalescing and reduced-motion behavior.
12. Add context menus, inspection panels, keyboard paths, and accessibility
   checks.
13. Add a two-browser Playwright test proving that creation, power, movement,
    connection, and disconnection in one client update the other client.
14. Add Playwright coverage for the complete printer-switch-gateway DHCP flow,
    including activity at both ends of each traversed connection.
