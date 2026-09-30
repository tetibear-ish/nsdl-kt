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
import { useEditorStore } from "./store";
import { projectTopology, type NetworkNode as NetworkNodeType } from "./topology";
import { selectTransport, type SimulationTransport } from "./transport";
import type { ObjectTypeSchema } from "./types";

const nodeTypes = { network: NetworkNode };
const defaultTransport = selectTransport();

export function App({ transport = defaultTransport }: { transport?: SimulationTransport }) {
  const [nodes, setNodes, onNodesChange] = useNodesState<NetworkNodeType>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [types, setTypes] = useState<ObjectTypeSchema[]>([]);
  const [addOpen, setAddOpen] = useState(false);
  const [status, setStatus] = useState("Connecting…");
  const placement = useRef({ x: 120, y: 120 });
  const flow = useRef<ReactFlowInstance<NetworkNodeType, Edge> | null>(null);
  const positions = useEditorStore((state) => state.positions);
  const setPosition = useEditorStore((state) => state.setPosition);

  const refresh = useCallback(async () => {
    const result = await transport.listObjects();
    if (!result.ok) {
      setStatus(`${result.error.code}: ${result.error.message}`);
      return 0;
    }
    const projected = projectTopology(result.data, useEditorStore.getState().positions);
    setNodes(projected.nodes);
    setEdges(projected.edges);
    setStatus(`revision ${result.revision}`);
    return result.revision;
  }, [setEdges, setNodes, transport]);

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
    await refresh();
  }, [refresh, transport]);

  const togglePower = useCallback(async (_event: React.MouseEvent, node: NetworkNodeType) => {
    const op = node.data.snapshot.state.power === "OFF" ? "powerOn" : "powerOff";
    const result = await transport.execute(op, { id: node.id });
    if (!result.ok) setStatus(`${result.error.code}: ${result.error.message}`);
    await refresh();
  }, [refresh, transport]);

  const createObject = useCallback(async (type: ObjectTypeSchema, id: string, props: Record<string, string>) => {
    const cleaned = Object.fromEntries(Object.entries(props).filter(([, value]) => value !== ""));
    const result = await transport.execute("create", { id, type: type.name, props: cleaned });
    if (!result.ok) return `${result.error.code}: ${result.error.message}`;
    setPosition(id, placement.current);
    await refresh();
    return null;
  }, [refresh, setPosition, transport]);

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
        </div>
      </header>
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
          onInit={(instance) => { flow.current = instance; }}
          onNodeClick={togglePower}
          onNodeDragStop={(_event, node) => setPosition(node.id, node.position)}
          onNodesChange={onNodesChange}
          onPaneContextMenu={(event) => { event.preventDefault(); openAddAt(event.clientX, event.clientY); }}
          proOptions={proOptions}
        >
          <Background color="#29445f" gap={24} variant={BackgroundVariant.Dots} />
          <Controls />
          <MiniMap<NetworkNodeType> nodeColor={(node) => node.data.snapshot.state.power === "ON" ? "#5de4d1" : "#587087"} />
        </ReactFlow>
      </section>
      <AddObjectDialog open={addOpen} types={types} onCreate={createObject} onOpenChange={setAddOpen} />
    </main>
  );
}
