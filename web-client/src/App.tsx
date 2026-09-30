import {
  Background,
  BackgroundVariant,
  ConnectionMode,
  Controls,
  type Edge,
  MiniMap,
  ReactFlow,
  type Connection,
  type ReactFlowInstance,
  useEdgesState,
  useNodesState,
} from "@xyflow/react";
import { DropdownMenu } from "radix-ui";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AddObjectDialog } from "./AddObjectDialog";
import { eventToPulse } from "./activity";
import { useActivityStore } from "./activityStore";
import { CLOCK_SPEEDS, type ClockState, formatVirtualTime, INITIAL_CLOCK_STATE } from "./clock";
import { useClockTimeStore } from "./clockStore";
import { DhcpLeasePanel } from "./DhcpLeasePanel";
import { DhcpServerPanel } from "./DhcpServerPanel";
import { NetworkNode } from "./NetworkNode";
import { inspectionIds } from "./inspector";
import {
  buildDocument,
  exportDocumentAsFile,
  importDocumentFromFile,
  loadLabFromLocalStorage,
  saveLabToLocalStorage,
} from "./persistence";
import { useEditorStore } from "./store";
import { projectTopology, type NetworkNode as NetworkNodeType } from "./topology";
import { selectTransport, type SimulationTransport } from "./transport";
import type { ObjectSnapshot, ObjectTypeSchema } from "./types";
import { UndoStack } from "./undoStack";

const UNDO_CAPACITY = 50;
const STEP_DURATION_MS = 100;

const nodeTypes = { network: NetworkNode };
const defaultTransport = selectTransport();

export function App({ transport = defaultTransport }: { transport?: SimulationTransport }) {
  const [nodes, setNodes, onNodesChange] = useNodesState<NetworkNodeType>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [types, setTypes] = useState<ObjectTypeSchema[]>([]);
  const [addOpen, setAddOpen] = useState(false);
  const [status, setStatus] = useState("Connecting…");
  const [log, setLog] = useState<string[]>(["Simulation ready; virtual clock runs at 1× in offline mode."]);
  const [inspected, setInspected] = useState<ObjectSnapshot[]>([]);
  const placement = useRef({ x: 120, y: 120 });
  const flow = useRef<ReactFlowInstance<NetworkNodeType, Edge> | null>(null);
  const powerStates = useRef<Record<string, unknown>>({});
  const pinnedInspection = useRef<{ kind: "node" | "edge"; id: string } | null>(null);
  const positions = useEditorStore((state) => state.positions);
  const setPosition = useEditorStore((state) => state.setPosition);
  const records = useEditorStore((state) => state.records);
  const setRecord = useEditorStore((state) => state.setRecord);
  const removeRecord = useEditorStore((state) => state.removeRecord);
  const importInput = useRef<HTMLInputElement | null>(null);
  const undoStack = useRef(new UndoStack(UNDO_CAPACITY));
  const [clock, setClock] = useState<ClockState>(INITIAL_CLOCK_STATE);

  const togglePower = useCallback(async (snapshot: ObjectSnapshot) => {
    const op = snapshot.state.power === "OFF" ? "powerOn" : "powerOff";
    const result = await transport.execute(op, { id: snapshot.id });
    if (!result.ok) setStatus(`${result.error.code}: ${result.error.message}`);
    setLog((entries) => [...entries.slice(-99), result.ok ? `${op} ${snapshot.id}` : `${op} ${snapshot.id}: ${result.error.message}`]);
  }, [transport]);

  const loadNodeInspection = useCallback(async (snapshot: ObjectSnapshot) => {
    const results = await Promise.all(inspectionIds(snapshot).map((id) => transport.execute<ObjectSnapshot>("inspect", { id })));
    if (pinnedInspection.current?.kind === "node" && pinnedInspection.current.id !== snapshot.id) return;
    setInspected(results.flatMap((result) => result.ok ? [result.data] : []));
  }, [transport]);

  const fetchDeviceServices = useCallback(async (devices: ObjectSnapshot[], suffix: string) => {
    const services = devices.flatMap((snapshot) =>
      (snapshot.relations.services ?? [])
        .filter((serviceId) => serviceId.endsWith(suffix))
        .map((serviceId) => ({ deviceId: snapshot.id, serviceId })),
    );
    const results = await Promise.all(services.map(({ serviceId }) => transport.execute<ObjectSnapshot>("inspect", { id: serviceId })));
    return Object.fromEntries(
      services.flatMap(({ deviceId }, index) => {
        const result = results[index];
        return result.ok ? [[deviceId, result.data] as const] : [];
      }),
    );
  }, [transport]);

  const refresh = useCallback(async () => {
    const result = await transport.listObjects();
    if (!result.ok) {
      setStatus(`${result.error.code}: ${result.error.message}`);
      return 0;
    }
    const [dhcpLeases, dhcpServers] = await Promise.all([
      fetchDeviceServices(result.data, ".dhcp-client"),
      fetchDeviceServices(result.data, ".dhcp-server"),
    ]);
    const projected = projectTopology(result.data, useEditorStore.getState().positions, (snapshot) => { void togglePower(snapshot); }, dhcpLeases, dhcpServers);
    const nextPower = Object.fromEntries(projected.nodes.map((node) => [node.id, node.data.snapshot.state.power]));
    const transitions = projected.nodes.flatMap((node) => {
      const before = powerStates.current[node.id];
      const after = node.data.snapshot.state.power;
      return before !== undefined && before !== after ? [`${node.id}: ${String(before)} → ${String(after)}`] : [];
    });
    powerStates.current = nextPower;
    if (transitions.length) setLog((entries) => [...entries.slice(-99), ...transitions]);
    setNodes(projected.nodes);
    setEdges(projected.edges);
    const pinned = pinnedInspection.current;
    if (pinned?.kind === "node") {
      const node = projected.nodes.find((candidate) => candidate.id === pinned.id);
      if (node) void loadNodeInspection(node.data.snapshot);
    } else if (pinned?.kind === "edge") {
      const edge = projected.edges.find((candidate) => candidate.id === pinned.id);
      setInspected(edge?.data?.snapshot ? [edge.data.snapshot as ObjectSnapshot] : []);
    }
    setStatus(`revision ${result.revision}`);
    return result.revision;
  }, [fetchDeviceServices, loadNodeInspection, setEdges, setNodes, togglePower, transport]);

  useEffect(() => {
    let unsubscribe = () => {};
    let disposed = false;
    Promise.all([transport.listTypes(), refresh()]).then(([typeResult, revision]) => {
      if (disposed) return;
      if (typeResult.ok) setTypes(typeResult.data);
      unsubscribe = transport.subscribe(revision, (event) => {
        const pulse = eventToPulse(event);
        if (pulse) {
          // Frame activity alone never changes topology structure, so skip the full refresh --
          // otherwise a burst of traffic would trigger an unbounded listObjects()/re-project per frame.
          useActivityStore.getState().record(event.seq ?? 0, pulse.portId, pulse.kind, pulse.reason);
          return;
        }
        void refresh();
      });
    }).catch((error: unknown) => setStatus(error instanceof Error ? error.message : String(error)));
    return () => { disposed = true; unsubscribe(); };
  }, [refresh, transport]);

  useEffect(() => {
    const interval = setInterval(() => useActivityStore.getState().prune(), 100);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    const apply = (state: ClockState) => { setClock(state); useClockTimeStore.getState().setNowMs(state.nowMs); };
    apply(transport.getClock());
    return transport.onClockChange(apply);
  }, [transport]);

  const toggleClock = useCallback(() => {
    if (clock.mode === "running") transport.pauseClock(); else transport.resumeClock();
  }, [clock.mode, transport]);

  const step = useCallback(() => { void transport.step(STEP_DURATION_MS); }, [transport]);

  const onConnect = useCallback(async (connection: Connection) => {
    if (!connection.sourceHandle || !connection.targetHandle) return;
    const cableId = `cable-${crypto.randomUUID().slice(0, 8)}`;
    const result = await transport.execute("applyTopology", {
      objects: [{ id: cableId, type: "cat5-cable", props: {} }],
      connections: [{ cableId, a: connection.sourceHandle, b: connection.targetHandle }],
    });
    if (!result.ok) setStatus(`${result.error.code}: ${result.error.message}`);
    if (result.ok) {
      setRecord(cableId, { type: "cat5-cable", props: {} });
      undoStack.current.push({
        description: `connect ${connection.sourceHandle} ↔ ${connection.targetHandle}`,
        undo: async () => {
          await transport.execute("delete", { id: cableId });
          removeRecord(cableId);
          await refresh();
        },
      });
    }
    setLog((entries) => [...entries.slice(-99), result.ok
      ? `connected ${connection.sourceHandle} ↔ ${connection.targetHandle}`
      : `connect failed: ${result.error.message}`]);
    await refresh();
  }, [refresh, removeRecord, setRecord, transport]);

  const disconnecting = useRef(new Set<string>());
  const disconnect = useCallback(async (edge: Edge) => {
    if (disconnecting.current.has(edge.id)) return;
    disconnecting.current.add(edge.id);
    const result = await transport.execute("disconnect", { cableId: edge.id });
    disconnecting.current.delete(edge.id);
    if (result.ok && edge.sourceHandle && edge.targetHandle) {
      const { sourceHandle, targetHandle } = edge;
      undoStack.current.push({
        description: `disconnect ${edge.id}`,
        undo: async () => {
          await transport.execute("connect", { cableId: edge.id, a: sourceHandle, b: targetHandle });
          await refresh();
        },
      });
    }
    setLog((entries) => [...entries.slice(-99), result.ok ? `disconnected ${edge.id}` : `disconnect failed: ${result.error.message}`]);
    await refresh();
  }, [refresh, transport]);

  const createObject = useCallback(async (type: ObjectTypeSchema, id: string, props: Record<string, string>) => {
    const cleaned = Object.fromEntries(Object.entries(props).filter(([, value]) => value !== ""));
    const result = await transport.execute("create", { id, type: type.name, props: cleaned });
    if (!result.ok) return `${result.error.code}: ${result.error.message}`;
    setLog((entries) => [...entries.slice(-99), `created ${type.name} ${id}`]);
    setPosition(id, placement.current);
    setRecord(id, { type: type.name, props: cleaned });
    undoStack.current.push({
      description: `create ${id}`,
      undo: async () => {
        await transport.execute("delete", { id });
        removeRecord(id);
        await refresh();
      },
    });
    await refresh();
    return null;
  }, [refresh, removeRecord, setPosition, setRecord, transport]);

  const deleteObject = useCallback(async (id: string) => {
    const cascadedEdges = edges.filter((edge) => edge.source === id || edge.target === id);
    const preview = cascadedEdges.length > 0
      ? `Delete ${id}? This will also remove ${cascadedEdges.length} attached cable(s): ${cascadedEdges.map((edge) => edge.id).join(", ")}`
      : `Delete ${id}?`;
    if (!window.confirm(preview)) return;

    const result = await transport.execute<{ deleted: string[] }>("delete", { id });
    if (!result.ok) {
      setStatus(`${result.error.code}: ${result.error.message}`);
      setLog((entries) => [...entries.slice(-99), `delete ${id} failed: ${result.error.message}`]);
      return;
    }

    const removed = result.data.deleted.map((removedId) => ({ id: removedId, record: records[removedId] }));
    const removedConnections = cascadedEdges.map((edge) => ({ cableId: edge.id, a: edge.sourceHandle ?? "", b: edge.targetHandle ?? "" }));
    undoStack.current.push({
      description: `delete ${id}`,
      undo: async () => {
        const objectsToRecreate = removed.flatMap((entry) => entry.record ? [{ id: entry.id, type: entry.record.type, props: entry.record.props }] : []);
        if (objectsToRecreate.length === 0) return;
        await transport.execute("applyTopology", { objects: objectsToRecreate, connections: removedConnections });
        objectsToRecreate.forEach((object) => setRecord(object.id, { type: object.type, props: object.props }));
        await refresh();
      },
    });
    removed.forEach((entry) => removeRecord(entry.id));
    pinnedInspection.current = null;
    setInspected([]);
    setLog((entries) => [...entries.slice(-99), `deleted ${result.data.deleted.join(", ")}`]);
    await refresh();
  }, [edges, records, refresh, removeRecord, setRecord, transport]);

  const undo = useCallback(async () => {
    const description = await undoStack.current.undo();
    setLog((entries) => [...entries.slice(-99), description ? `undid: ${description}` : "nothing to undo"]);
  }, []);

  const saveLab = useCallback(() => {
    const doc = buildDocument(
      Object.entries(records).map(([id, record]) => ({ id, type: record.type, props: record.props })),
      edges.map((edge) => ({ cableId: edge.id, a: edge.sourceHandle ?? "", b: edge.targetHandle ?? "" })),
      positions,
    );
    saveLabToLocalStorage(doc);
    setLog((entries) => [...entries.slice(-99), `saved lab (${doc.objects.length} objects, ${doc.connections.length} cables)`]);
  }, [edges, positions, records]);

  const applyLoadedDocument = useCallback(async (
    result: ReturnType<typeof loadLabFromLocalStorage> | Awaited<ReturnType<typeof importDocumentFromFile>>,
    sourceLabel: string,
  ) => {
    if (result === null) {
      setLog((entries) => [...entries.slice(-99), "no saved lab found"]);
      return;
    }
    if (!result.ok) {
      setLog((entries) => [...entries.slice(-99), ...result.errors.map((error) => `${sourceLabel}: ${error.path || "(document)"}: ${error.message}`)]);
      return;
    }
    const { document } = result;
    const applied = await transport.execute("applyTopology", { objects: document.objects, connections: document.connections });
    if (!applied.ok) {
      setLog((entries) => [...entries.slice(-99), `${sourceLabel} failed: ${applied.error.code}: ${applied.error.message}`]);
      return;
    }
    document.objects.forEach((object) => setRecord(object.id, { type: object.type, props: object.props }));
    Object.entries(document.positions).forEach(([id, position]) => setPosition(id, position));
    setLog((entries) => [...entries.slice(-99), `${sourceLabel}: applied ${document.objects.length} objects, ${document.connections.length} cables`]);
    await refresh();
  }, [refresh, setPosition, setRecord, transport]);

  const loadLab = useCallback(() => {
    void applyLoadedDocument(loadLabFromLocalStorage(), "loaded saved lab");
  }, [applyLoadedDocument]);

  const exportLab = useCallback(() => {
    const doc = buildDocument(
      Object.entries(records).map(([id, record]) => ({ id, type: record.type, props: record.props })),
      edges.map((edge) => ({ cableId: edge.id, a: edge.sourceHandle ?? "", b: edge.targetHandle ?? "" })),
      positions,
    );
    exportDocumentAsFile(doc, "nsdl-topology.json");
  }, [edges, positions, records]);

  const importLab = useCallback((file: File) => {
    void importDocumentFromFile(file).then((result) => applyLoadedDocument(result, `imported ${file.name}`));
  }, [applyLoadedDocument]);

  const openAddAt = useCallback((clientX?: number, clientY?: number) => {
    if (clientX != null && clientY != null && flow.current) {
      placement.current = flow.current.screenToFlowPosition({ x: clientX, y: clientY });
    } else {
      placement.current = { x: 120, y: 120 };
    }
    setAddOpen(true);
  }, []);

  const proOptions = useMemo(() => ({ hideAttribution: true }), []);

  return (
    <main className="app-shell">
      <header className="toolbar">
        <div><span>NSDL</span><strong>Topology Lab</strong></div>
        <div className="toolbar-actions">
          <output>{status}</output>
          <div className="clock-controls" aria-label="Simulation clock">
            <span className="clock-time">{formatVirtualTime(clock.nowMs)}</span>
            <span className="clock-drive-mode">{transport.driveMode === "browser" ? "browser-driven" : "server-driven"}</span>
            <button className="secondary" onClick={toggleClock}>{clock.mode === "running" ? "Pause" : "Resume"}</button>
            <button className="secondary" disabled={clock.mode === "running"} onClick={step}>Step</button>
            {CLOCK_SPEEDS.map((speed) => (
              <button
                key={speed}
                className={clock.speed === speed ? "secondary active" : "secondary"}
                onClick={() => transport.setClockSpeed(speed)}
              >{speed}×</button>
            ))}
          </div>
          <button onClick={() => openAddAt()}>Add…</button>
          <button className="secondary" onClick={() => { void undo(); }}>Undo</button>
          <DropdownMenu.Root>
            <DropdownMenu.Trigger asChild>
              <button className="secondary icon-button" aria-label="Lab options">⋯</button>
            </DropdownMenu.Trigger>
            <DropdownMenu.Portal>
              <DropdownMenu.Content className="dropdown-content" align="end" sideOffset={6}>
                <DropdownMenu.Item className="dropdown-item" onSelect={saveLab}>Save</DropdownMenu.Item>
                <DropdownMenu.Item className="dropdown-item" onSelect={loadLab}>Load</DropdownMenu.Item>
                <DropdownMenu.Separator className="dropdown-separator" />
                <DropdownMenu.Item className="dropdown-item" onSelect={exportLab}>Export…</DropdownMenu.Item>
                <DropdownMenu.Item className="dropdown-item" onSelect={() => importInput.current?.click()}>Import…</DropdownMenu.Item>
              </DropdownMenu.Content>
            </DropdownMenu.Portal>
          </DropdownMenu.Root>
          <input
            ref={importInput}
            type="file"
            accept="application/json"
            hidden
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) importLab(file);
              event.target.value = "";
            }}
          />
        </div>
      </header>
      <div className="workspace">
      <section className="canvas">
        <ReactFlow<NetworkNodeType, Edge>
          connectionMode={ConnectionMode.Loose}
          edges={edges}
          edgesReconnectable
          fitView
          nodes={nodes}
          nodeTypes={nodeTypes}
          onConnect={onConnect}
          onEdgesChange={onEdgesChange}
          onEdgesDelete={(deleted) => { deleted.forEach((edge) => { void disconnect(edge); }); }}
          onEdgeDoubleClick={(_event, edge) => { void disconnect(edge); }}
          onEdgeClick={(_event, edge) => { pinnedInspection.current = { kind: "edge", id: edge.id }; setInspected(edge.data?.snapshot ? [edge.data.snapshot as ObjectSnapshot] : []); }}
          onEdgeMouseEnter={(_event, edge) => { if (!pinnedInspection.current) setInspected(edge.data?.snapshot ? [edge.data.snapshot as ObjectSnapshot] : []); }}
          onEdgeMouseLeave={() => { if (!pinnedInspection.current) setInspected([]); }}
          onReconnectEnd={(_event, edge, _handle, connectionState) => { if (!connectionState.isValid) void disconnect(edge); }}
          onInit={(instance) => { flow.current = instance; }}
          onNodeClick={(_event, node) => { pinnedInspection.current = { kind: "node", id: node.id }; void loadNodeInspection(node.data.snapshot); }}
          onNodeMouseEnter={(_event, node) => { if (!pinnedInspection.current) void loadNodeInspection(node.data.snapshot); }}
          onNodeMouseLeave={() => { if (!pinnedInspection.current) setInspected([]); }}
          onNodeDragStop={(_event, node) => {
            const from = positions[node.id];
            setPosition(node.id, node.position);
            if (from && (from.x !== node.position.x || from.y !== node.position.y)) {
              undoStack.current.push({ description: `move ${node.id}`, undo: () => setPosition(node.id, from) });
            }
          }}
          onNodesChange={onNodesChange}
          onPaneContextMenu={(event) => { event.preventDefault(); openAddAt(event.clientX, event.clientY); }}
          onPaneClick={() => { pinnedInspection.current = null; setInspected([]); }}
          proOptions={proOptions}
        >
          <Background color="#29445f" gap={24} variant={BackgroundVariant.Dots} />
          <Controls />
          <MiniMap<NetworkNodeType> nodeColor={(node) => node.data.snapshot.state.power === "ON" ? "#5de4d1" : "#587087"} />
        </ReactFlow>
      </section>
      <aside className="inspector" aria-label="Element state">
        <h2>Element state</h2>
        {inspected.length === 0
          ? <p>Hover over a device or cable.</p>
          : inspected.map((snapshot) => (
            <section key={snapshot.id}>
              <h3>{snapshot.id}</h3>
              {snapshot.type === "dhcp-client" && <DhcpLeasePanel snapshot={snapshot} nowMs={clock.nowMs} />}
              {snapshot.type === "dhcp-server" && <DhcpServerPanel snapshot={snapshot} nowMs={clock.nowMs} />}
              {snapshot.type !== "dhcp-client" && snapshot.type !== "dhcp-server" &&
                <pre>{JSON.stringify({ type: snapshot.type, kind: snapshot.kind, state: snapshot.state, relations: snapshot.relations }, null, 2)}</pre>}
            </section>
          ))}
        {inspected[0]?.kind === "CABLE" && <>
          <button onClick={() => {
            const edge = edges.find((candidate) => candidate.id === inspected[0].id);
            if (edge) void disconnect(edge);
          }}>Disconnect</button>
          <button className="secondary" onClick={() => { void deleteObject(inspected[0].id); }}>Delete</button>
        </>}
        {inspected[0]?.kind === "DEVICE" && (
          <button className="secondary" onClick={() => { void deleteObject(inspected[0].id); }}>Delete</button>
        )}
      </aside>
      </div>
      <section className="activity-log" aria-label="Activity log">
        <header><strong>Activity</strong><button className="secondary" onClick={() => setLog([])}>Clear</button></header>
        <ol>{log.map((entry, index) => <li key={`${index}-${entry}`}>{entry}</li>)}</ol>
      </section>
      <AddObjectDialog
        open={addOpen}
        types={types}
        existingIds={nodes.map((node) => node.id)}
        onCreate={createObject}
        onOpenChange={setAddOpen}
      />
    </main>
  );
}
