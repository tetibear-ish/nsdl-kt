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
import { defaultObjectId } from "./AddObjectDialog";
import { eventToPulse } from "./activity";
import { useActivityStore } from "./activityStore";
import { CLOCK_SPEEDS, type ClockState, formatVirtualTime, INITIAL_CLOCK_STATE } from "./clock";
import { useClockTimeStore } from "./clockStore";
import { moveCable, type CableEndpoints } from "./cableMove";
import { describeConnectionRejection, guidePortStates, type GuidePort } from "./connectionGuide";
import { useConnectionDragStore } from "./connectionDragStore";
import { DhcpLeasePanel } from "./DhcpLeasePanel";
import { DhcpServerPanel } from "./DhcpServerPanel";
import { ConfigureObjectForm } from "./ConfigureObjectForm";
import { HistoryDropdown } from "./HistoryDropdown";
import { NetworkNode } from "./NetworkNode";
import type { GraphAnalysis } from "./graphAnalysis";
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
  // Read by deleteCableAtPort instead of a reactive `edges` dependency -- otherwise its identity
  // (and refresh's, which depends on it) would change on every topology refresh, re-triggering
  // the subscribe effect below on every update instead of once.
  const edgesRef = useRef<Edge[]>(edges);
  edgesRef.current = edges;
  const [types, setTypes] = useState<ObjectTypeSchema[]>([]);
  const [quickAdd, setQuickAdd] = useState<{ x: number; y: number } | null>(null);
  const [status, setStatus] = useState("Connecting…");
  const [log, setLog] = useState<string[]>(["Simulation ready; virtual clock runs at 1× in offline mode."]);
  const [inspected, setInspected] = useState<ObjectSnapshot[]>([]);
  const [graphAnalysis, setGraphAnalysis] = useState<GraphAnalysis | null>(null);
  const [connectionRejection, setConnectionRejection] = useState<{ message: string; x: number; y: number } | null>(null);
  const placement = useRef({ x: 120, y: 120 });
  const flow = useRef<ReactFlowInstance<NetworkNodeType, Edge> | null>(null);
  const powerStates = useRef<Record<string, unknown>>({});
  const pinnedInspection = useRef<{ kind: "node" | "edge"; id: string } | null>(null);
  const reconnectingPort = useRef<string | null>(null);
  // Set synchronously once listTypes resolves, read by refresh() so port media is correct even
  // on the very first projection (avoiding a stale-closure race with the `types` state setter).
  const typesRef = useRef<ObjectTypeSchema[]>([]);
  // Lets deleteCableAtPort's undo call the current refresh() despite being declared above it.
  const refreshRef = useRef<() => Promise<number>>(async () => 0);
  // Screen position of the most recent connect attempt, for placing the rejection banner.
  const lastConnectPoint = useRef({ x: 120, y: 120 });
  const rejectionTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
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

  const deleteCableAtPort = useCallback(async (cableId: string) => {
    const edge = edgesRef.current.find((candidate) => candidate.id === cableId);
    const record = useEditorStore.getState().records[cableId];
    const result = await transport.execute<{ deleted: string[] }>("delete", { id: cableId });
    if (result.ok) {
      removeRecord(cableId);
      if (record && edge?.sourceHandle && edge.targetHandle) {
        const { sourceHandle, targetHandle } = edge;
        undoStack.current.push({
          description: `delete ${cableId}`,
          undo: async () => {
            await transport.execute("applyTopology", {
              objects: [{ id: cableId, type: record.type, props: record.props }],
              connections: [{ cableId, a: sourceHandle, b: targetHandle }],
            });
            setRecord(cableId, record);
            await refreshRef.current();
          },
        });
      }
      setLog((entries) => [...entries.slice(-99), `deleted ${cableId}`]);
    } else {
      setStatus(`${result.error.code}: ${result.error.message}`);
    }
  }, [removeRecord, setRecord, transport]);

  const loadNodeInspection = useCallback(async (snapshot: ObjectSnapshot) => {
    const results = await Promise.all(inspectionIds(snapshot).map((id) => transport.execute<ObjectSnapshot>("inspect", { id })));
    if (pinnedInspection.current?.kind === "node" && pinnedInspection.current.id !== snapshot.id) return;
    setInspected(results.flatMap((result) => result.ok ? [result.data] : []));
  }, [transport]);

  const loadGraphAnalysis = useCallback(async (id: string) => {
    const result = await transport.execute<GraphAnalysis>("analyzeGraph", { id });
    if (result.ok) setGraphAnalysis(result.data);
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
    const projected = projectTopology(
      result.data,
      useEditorStore.getState().positions,
      (snapshot) => { void togglePower(snapshot); },
      dhcpLeases,
      dhcpServers,
      (cableId) => { void deleteCableAtPort(cableId); },
      reconnectingPort.current,
      typesRef.current,
    );
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
      if (node) { void loadNodeInspection(node.data.snapshot); void loadGraphAnalysis(node.id); }
    } else if (pinned?.kind === "edge") {
      const edge = projected.edges.find((candidate) => candidate.id === pinned.id);
      setInspected(edge?.data?.snapshot ? [edge.data.snapshot as ObjectSnapshot] : []);
    }
    setStatus(`revision ${result.revision}`);
    return result.revision;
  }, [deleteCableAtPort, fetchDeviceServices, loadGraphAnalysis, loadNodeInspection, setEdges, setNodes, togglePower, transport]);
  refreshRef.current = refresh;

  useEffect(() => {
    let unsubscribe = () => {};
    let disposed = false;
    // listTypes is resolved before the first refresh() (rather than in parallel) so the very
    // first topology projection already has schemas to resolve each port's media from.
    transport.listTypes().then((typeResult) => {
      if (disposed) return;
      if (typeResult.ok) { setTypes(typeResult.data); typesRef.current = typeResult.data; }
      return refresh();
    }).then((revision) => {
      if (disposed || revision === undefined) return;
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

  const guidePorts = useMemo<GuidePort[]>(
    () => nodes.flatMap((node) => node.data.ports.map((port) => ({ id: port.id, media: port.media, occupied: port.occupied }))),
    [nodes],
  );

  const onConnectStart = useCallback((_event: unknown, params: { handleId: string | null }) => {
    setConnectionRejection(null);
    if (!params.handleId) return;
    useConnectionDragStore.getState().begin(guidePortStates(params.handleId, guidePorts));
  }, [guidePorts]);

  const onConnectEnd = useCallback((event: MouseEvent | TouchEvent) => {
    useConnectionDragStore.getState().end();
    const point = "changedTouches" in event ? event.changedTouches[0] : event;
    if (point) lastConnectPoint.current = { x: point.clientX, y: point.clientY };
  }, []);

  const isValidConnection = useCallback((connection: Connection | Edge) => {
    const { sourceHandle, targetHandle } = connection;
    if (!sourceHandle || !targetHandle) return false;
    if (sourceHandle === targetHandle) return false;
    const source = guidePorts.find((port) => port.id === sourceHandle);
    const target = guidePorts.find((port) => port.id === targetHandle);
    // Media mismatch is the one thing nothing else client-side already guards against --
    // an occupied target is already excluded via that Handle's isConnectable.
    if (!source || !target) return true;
    return source.media === target.media;
  }, [guidePorts]);

  const showConnectionRejection = useCallback((message: string) => {
    if (rejectionTimer.current) clearTimeout(rejectionTimer.current);
    setConnectionRejection({ message, ...lastConnectPoint.current });
    rejectionTimer.current = setTimeout(() => setConnectionRejection(null), 5000);
  }, []);

  const onConnect = useCallback(async (connection: Connection) => {
    if (!connection.sourceHandle || !connection.targetHandle) return;
    const cableId = `cable-${crypto.randomUUID().slice(0, 8)}`;
    const result = await transport.execute("applyTopology", {
      objects: [{ id: cableId, type: "cat5-cable", props: {} }],
      connections: [{ cableId, a: connection.sourceHandle, b: connection.targetHandle }],
    });
    if (!result.ok) {
      setStatus(`${result.error.code}: ${result.error.message}`);
      showConnectionRejection(describeConnectionRejection(result.error));
    }
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
  }, [refresh, removeRecord, setRecord, showConnectionRejection, transport]);

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

  const reconnectCompleted = useRef(false);
  const reconnectCable = useCallback(async (edge: Edge, connection: Connection) => {
    reconnectCompleted.current = true;
    if (!edge.sourceHandle || !edge.targetHandle || !connection.sourceHandle || !connection.targetHandle) return;
    const previous: CableEndpoints = { a: edge.sourceHandle, b: edge.targetHandle };
    const next: CableEndpoints = { a: connection.sourceHandle, b: connection.targetHandle };
    const result = await moveCable(transport.execute.bind(transport), edge.id, previous, next);
    if (!result.ok) {
      setStatus(result.message);
      setLog((entries) => [...entries.slice(-99), `reconnect ${edge.id} failed: ${result.message}`]);
    } else {
      setLog((entries) => [...entries.slice(-99), `reconnected ${edge.id}: ${next.a} ↔ ${next.b}`]);
      undoStack.current.push({
        description: `reconnect ${edge.id}`,
        undo: async () => {
          await moveCable(transport.execute.bind(transport), edge.id, next, previous);
          await refresh();
        },
      });
    }
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

  const configureObject = useCallback(async (snapshot: ObjectSnapshot, props: Record<string, string>) => {
    const result = await transport.execute("configure", { id: snapshot.id, props });
    if (!result.ok) {
      const message = `${result.error.code}: ${result.error.message}`;
      setStatus(message);
      setLog((entries) => [...entries.slice(-99), `configure ${snapshot.id} failed: ${message}`]);
      return message;
    }
    setRecord(snapshot.id, { type: snapshot.type, props });
    setLog((entries) => [...entries.slice(-99), `configured ${snapshot.id}; device replacement started`]);
    await refresh();
    return null;
  }, [refresh, setRecord, transport]);

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

  const quickCreate = useCallback(async (typeName: string) => {
    const type = types.find((candidate) => candidate.name === typeName);
    if (!type) return;
    const id = defaultObjectId(typeName, nodes.map((node) => node.id));
    setQuickAdd(null);
    await createObject(type, id, {});
  }, [createObject, nodes, types]);

  const proOptions = useMemo(() => ({ hideAttribution: true }), []);
  const inspectedDevice = inspected[0]?.kind === "DEVICE" ? inspected[0] : null;
  const inspectedSchema = inspectedDevice ? types.find((type) => type.name === inspectedDevice.type) : undefined;

  return (
    <main className="app-shell">
      <header className="toolbar">
        <div><span>NSDL</span><strong>Topology Lab</strong><HistoryDropdown /></div>
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
          deleteKeyCode={null}
          edges={edges}
          edgesReconnectable
          fitView
          nodes={nodes}
          nodeTypes={nodeTypes}
          isValidConnection={isValidConnection}
          onConnect={onConnect}
          onConnectStart={onConnectStart}
          onConnectEnd={onConnectEnd}
          onEdgesChange={onEdgesChange}
          onEdgeClick={(_event, edge) => { pinnedInspection.current = { kind: "edge", id: edge.id }; setInspected(edge.data?.snapshot ? [edge.data.snapshot as ObjectSnapshot] : []); }}
          onEdgeMouseEnter={(_event, edge) => { if (!pinnedInspection.current) setInspected(edge.data?.snapshot ? [edge.data.snapshot as ObjectSnapshot] : []); }}
          onEdgeMouseLeave={() => { if (!pinnedInspection.current) setInspected([]); }}
          onReconnect={(edge, connection) => { void reconnectCable(edge, connection); }}
          onReconnectStart={(_event, edge, handleType) => {
            reconnectCompleted.current = false;
            // React Flow reports the fixed opposite handle here, so the endpoint being moved is the other one.
            reconnectingPort.current = handleType === "target" ? edge.sourceHandle ?? null : edge.targetHandle ?? null;
            setNodes((current) => current.map((node) => ({
              ...node,
              data: {
                ...node.data,
                ports: node.data.ports.map((port) => ({ ...port, reconnectable: port.id === reconnectingPort.current })),
              },
            })));
          }}
          onReconnectEnd={(_event, edge) => {
            reconnectingPort.current = null;
            setNodes((current) => current.map((node) => ({
              ...node,
              data: { ...node.data, ports: node.data.ports.map((port) => ({ ...port, reconnectable: false })) },
            })));
            if (!reconnectCompleted.current) void disconnect(edge);
          }}
          onInit={(instance) => { flow.current = instance; }}
          onNodeClick={(_event, node) => { pinnedInspection.current = { kind: "node", id: node.id }; void loadNodeInspection(node.data.snapshot); void loadGraphAnalysis(node.id); }}
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
          onPaneContextMenu={(event) => {
            event.preventDefault();
            placement.current = flow.current?.screenToFlowPosition({ x: event.clientX, y: event.clientY }) ?? { x: 120, y: 120 };
            setQuickAdd({ x: event.clientX, y: event.clientY });
          }}
          onPaneClick={() => { pinnedInspection.current = null; setInspected([]); setGraphAnalysis(null); setQuickAdd(null); setConnectionRejection(null); }}
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
        {inspectedDevice && (
          <>
            {inspectedSchema && <ConfigureObjectForm
              id={inspectedDevice.id}
              schema={inspectedSchema}
              currentProps={records[inspectedDevice.id]?.props}
              onConfigure={(props) => configureObject(inspectedDevice, props)}
            />}
            <button className="secondary" onClick={() => { void deleteObject(inspectedDevice.id); }}>Delete</button>
          </>
        )}
        {graphAnalysis && <section className="graph-analysis">
          <h3>Graph analysis · {graphAnalysis.nodeId}</h3>
          <dl>
            <dt>Degree</dt><dd>{graphAnalysis.degree}</dd>
            <dt>Connected component</dt><dd>{graphAnalysis.connectedComponent.join(", ")}</dd>
            <dt>Articulation point</dt><dd>{graphAnalysis.articulationPoint ? "yes" : "no"}</dd>
          </dl>
          <details><summary>Shortest paths</summary><pre>{JSON.stringify(graphAnalysis.shortestPaths, null, 2)}</pre></details>
        </section>}
      </aside>
      </div>
      {quickAdd && <nav className="quick-add" style={{ left: quickAdd.x, top: quickAdd.y }} aria-label="Add network object">
        <button onClick={() => { void quickCreate("printer"); }}>+ Printer</button>
        <button onClick={() => { void quickCreate("gateway"); }}>+ Gateway</button>
        <button onClick={() => { void quickCreate("ethernet-switch"); }}>+ Switch</button>
      </nav>}
      {connectionRejection && (
        <div
          className="connection-rejection"
          role="alert"
          style={{ left: connectionRejection.x, top: connectionRejection.y }}
        >{connectionRejection.message}</div>
      )}
      <section className="activity-log" aria-label="Activity log">
        <header><strong>Activity</strong><button className="secondary" onClick={() => setLog([])}>Clear</button></header>
        <ol>{log.map((entry, index) => <li key={`${index}-${entry}`}>{entry}</li>)}</ol>
      </section>
    </main>
  );
}
