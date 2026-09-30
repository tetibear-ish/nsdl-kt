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
- Users can inspect every object and see its configuration, power state,
  interfaces, connections, and relevant protocol state.
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
- **Zustand** for transient editor state such as viewport, selection, menus,
  pending gestures, and locally persisted node positions.
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

The JVM runtime remains authoritative in server mode. Graph mutations are
displayed only after the command is accepted. SSE events update all connected
browsers. In standalone mode, the Wasm adapter applies commands to its local
simulation and publishes equivalent updates to the editor.

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
events. Node coordinates, viewport, selection, and open menus belong to the
editor. Positions may be stored locally at first and added to a topology
workspace format later.

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
4. Render snapshot-derived custom nodes and port handles.
5. Add schema-driven **Add...** dialogs and isolated-node placement.
6. Add power toggling and event-driven state styling.
7. Add atomic cable creation through handle dragging.
8. Add reconnect/disconnect-on-empty-drop behavior.
9. Add context menus, inspection panels, keyboard paths, and accessibility
   checks.
10. Add Playwright coverage for the complete printer-switch-gateway DHCP flow.
