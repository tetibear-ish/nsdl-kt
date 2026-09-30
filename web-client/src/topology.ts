import type { Edge, Node, XYPosition } from "@xyflow/react";
import type { ObjectSnapshot } from "./types";

export type PortView = {
  id: string;
  name: string;
  occupied: boolean;
};

export type NetworkNodeData = Record<string, unknown> & {
  snapshot: ObjectSnapshot;
  ports: PortView[];
};

export type NetworkNode = Node<NetworkNodeData, "network">;

export type TopologyProjection = {
  nodes: NetworkNode[];
  edges: Edge[];
};

const endpointOwner = (endpoint: string) => endpoint.includes(".")
  ? endpoint.slice(0, endpoint.lastIndexOf("."))
  : endpoint;

export function projectTopology(
  snapshots: ObjectSnapshot[],
  positions: Record<string, XYPosition> = {},
): TopologyProjection {
  const cables = snapshots.filter((snapshot) => snapshot.kind === "CABLE");
  const occupied = new Set(cables.flatMap((cable) => cable.relations.endpoints ?? []));
  const devices = snapshots.filter((snapshot) => snapshot.kind === "DEVICE");

  const nodes: NetworkNode[] = devices.map((snapshot, index) => ({
    id: snapshot.id,
    type: "network",
    position: positions[snapshot.id] ?? {
      x: 80 + (index % 4) * 250,
      y: 80 + Math.floor(index / 4) * 210,
    },
    data: {
      snapshot,
      ports: (snapshot.relations.interfaces ?? []).map((id) => ({
        id,
        name: id.slice(snapshot.id.length + 1),
        occupied: occupied.has(id),
      })),
    },
  }));

  const edges: Edge[] = cables.flatMap((cable) => {
    const endpoints = cable.relations.endpoints ?? [];
    if (endpoints.length !== 2) return [];
    return [{
      id: cable.id,
      source: endpointOwner(endpoints[0]),
      sourceHandle: endpoints[0],
      target: endpointOwner(endpoints[1]),
      targetHandle: endpoints[1],
      type: "smoothstep",
      className: "topology-edge",
    }];
  });

  return { nodes, edges };
}
