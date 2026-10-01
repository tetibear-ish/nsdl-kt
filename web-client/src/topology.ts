import type { Edge, Node, XYPosition } from "@xyflow/react";
import type { ObjectSnapshot } from "./types";

export type PortView = {
  id: string;
  name: string;
  occupied: boolean;
  cableId?: string;
};

export type NetworkNodeData = Record<string, unknown> & {
  snapshot: ObjectSnapshot;
  ports: PortView[];
  onPowerToggle?: (snapshot: ObjectSnapshot) => void;
  onCableDelete?: (cableId: string) => void;
  dhcpLease?: ObjectSnapshot;
  dhcpServer?: ObjectSnapshot;
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
  onPowerToggle?: (snapshot: ObjectSnapshot) => void,
  dhcpLeases: Record<string, ObjectSnapshot> = {},
  dhcpServers: Record<string, ObjectSnapshot> = {},
  onCableDelete?: (cableId: string) => void,
): TopologyProjection {
  const cables = snapshots.filter((snapshot) => snapshot.kind === "CABLE");
  const occupied = new Set(cables.flatMap((cable) => cable.relations.endpoints ?? []));
  const cableByEndpoint = new Map(cables.flatMap((cable) => (cable.relations.endpoints ?? []).map((endpoint) => [endpoint, cable.id] as const)));
  const devices = snapshots.filter((snapshot) => snapshot.kind === "DEVICE");

  const nodes: NetworkNode[] = devices.map((snapshot, index) => ({
    id: snapshot.id,
    deletable: false,
    type: "network",
    position: positions[snapshot.id] ?? {
      x: 80 + (index % 4) * 250,
      y: 80 + Math.floor(index / 4) * 210,
    },
    data: {
      snapshot,
      onPowerToggle,
      dhcpLease: dhcpLeases[snapshot.id],
      dhcpServer: dhcpServers[snapshot.id],
      onCableDelete,
      ports: (snapshot.relations.interfaces ?? []).map((id) => ({
        id,
        name: id.slice(snapshot.id.length + 1),
        occupied: occupied.has(id),
        cableId: cableByEndpoint.get(id),
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
      className: `topology-edge ${cable.state.connected === false ? "link-unused" : cable.state.linkUp === true ? "link-up" : "link-down"}`,
      data: { snapshot: cable },
    }];
  });

  return { nodes, edges };
}
