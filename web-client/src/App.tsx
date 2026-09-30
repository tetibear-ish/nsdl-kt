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
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AddObjectDialog } from "./AddObjectDialog";
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
  const importInput = useRef<HTMLInputElement | null>(null);

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

  const refresh = useCallback(async () => {
    const result = await transport.listObjects();
    if (!result.ok) {
      setStatus(`${result.error.code}: ${result.error.message}`);
      return 0;
    }
    const projected = projectTopology(result.data, useEditorStore.getState().positions, (snapshot) => { void togglePower(snapshot); });
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
  }, [loadNodeInspection, setEdges, setNodes, togglePower, transport]);

  useEffect(() => {
    let unsubscribe = () => {};
    let disposed = false;
    Promise.all([transport.listTypes(), refresh()]).then(([typeResult, revision]) => {
      if (disposed) return;
      if (typeResult.ok) setTypes(typeResult.data);
      unsubscribe = transport.subscribe(revision, () => { void refresh(); });
    }).catch((error: unknown) => setStatus(error instanceof Error ? error.message : String(error)));
    return () => { disposed = true; unsubscribe(); };
  }, [refresh, transport]);

  const onConnect = useCallback(async (connection: Connection) => {
    if (!connection.sourceHandle || !connection.targetHandle) return;
    const cableId = `cable-${crypto.randomUUID().slice(0, 8)}`;
    const result = await transport.execute("applyTopology", {
      objects: [{ id: cableId, type: "cat5-cable", props: {} }],
      connections: [{ cableId, a: connection.sourceHandle, b: connection.targetHandle }],
    });
    if (!result.ok) setStatus(`${result.error.code}: ${result.error.message}`);
    if (result.ok) setRecord(cableId, { type: "cat5-cable", props: {} });
    setLog((entries) => [...entries.slice(-99), result.ok
      ? `connected ${connection.sourceHandle} ↔ ${connection.targetHandle}`
      : `connect failed: ${result.error.message}`]);
    await refresh();
  }, [refresh, setRecord, transport]);

  const disconnecting = useRef(new Set<string>());
  const disconnect = useCallback(async (edge: Edge) => {
    if (disconnecting.current.has(edge.id)) return;
    disconnecting.current.add(edge.id);
    const result = await transport.execute("disconnect", { cableId: edge.id });
    disconnecting.current.delete(edge.id);
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
    await refresh();
    return null;
  }, [refresh, setPosition, setRecord, transport]);

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
          <button onClick={() => openAddAt()}>Add…</button>
          <button className="secondary" onClick={saveLab}>Save</button>
          <button className="secondary" onClick={loadLab}>Load</button>
          <button className="secondary" onClick={exportLab}>Export…</button>
          <button className="secondary" onClick={() => importInput.current?.click()}>Import…</button>
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
          onNodeDragStop={(_event, node) => setPosition(node.id, node.position)}
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
              <pre>{JSON.stringify({ type: snapshot.type, kind: snapshot.kind, state: snapshot.state, relations: snapshot.relations }, null, 2)}</pre>
            </section>
          ))}
        {inspected[0]?.kind === "CABLE" && <button onClick={() => {
          const edge = edges.find((candidate) => candidate.id === inspected[0].id);
          if (edge) void disconnect(edge);
        }}>Disconnect</button>}
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
