import type { Edge, Node, XYPosition } from "@xyflow/react";
import type { ObjectSnapshot, ObjectTypeSchema } from "./types";
import type { SoftwarePrinter } from "./softwareLayer";

/** Media reported for a port whose owning device's type schema isn't known yet (e.g. before
 *  listTypes has resolved), or whose schema has no matching interface. Never a real MediaType. */
export const UNKNOWN_MEDIA = "UNKNOWN";

export type PortView = {
  id: string;
  name: string;
  occupied: boolean;
  cableId?: string;
  reconnectable: boolean;
  /** The port's physical media (e.g. "TWISTED_PAIR"), from its object type's interface spec. */
  media: string;
  /** Whether the owning device is powered on. A disabled port can still accept a cable --
   *  a device can be plugged in while off -- but should look visually distinct. */
  enabled: boolean;
};

export type InspectionMode = "hover" | "pinned";

export type NetworkNodeData = Record<string, unknown> & {
  snapshot: ObjectSnapshot;
  ports: PortView[];
  onPowerToggle?: (snapshot: ObjectSnapshot) => void;
  onCableDelete?: (cableId: string) => void;
  dhcpLease?: ObjectSnapshot;
  dhcpServer?: ObjectSnapshot;
  inspection?: InspectionMode;
  softwarePrinters?: SoftwarePrinter[];
  printerCandidates?: SoftwarePrinter[];
  onAddPrinter?: (printer: SoftwarePrinter) => void;
  onRenamePrinter?: (printer: SoftwarePrinter) => void;
  onDeletePrinter?: (printer: SoftwarePrinter) => void;
  onTestPage?: (printer: SoftwarePrinter) => void;
};

export type NetworkNode = Node<NetworkNodeData, "network">;

export type TopologyProjection = {
  nodes: NetworkNode[];
  edges: Edge[];
};

export type SoftwareProjection = {
  inventories?: Record<string, SoftwarePrinter[]>;
  onAddPrinter?: (workstationId: string, printer: SoftwarePrinter) => void;
  onRenamePrinter?: (workstationId: string, printer: SoftwarePrinter) => void;
  onDeletePrinter?: (workstationId: string, printer: SoftwarePrinter) => void;
  onTestPage?: (workstationId: string, printer: SoftwarePrinter) => void;
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
  reconnectingPortId?: string | null,
  schemas: ObjectTypeSchema[] = [],
  inspection?: { kind: "node" | "edge"; id: string; pinned: boolean } | null,
  software: SoftwareProjection = {},
): TopologyProjection {
  const cables = snapshots.filter((snapshot) => snapshot.kind === "CABLE");
  const occupied = new Set(cables.flatMap((cable) => cable.relations.endpoints ?? []));
  const cableByEndpoint = new Map(cables.flatMap((cable) => (cable.relations.endpoints ?? []).map((endpoint) => [endpoint, cable.id] as const)));
  const devices = snapshots.filter((snapshot) => snapshot.kind === "DEVICE");
  const interfaceMacs = new Map(snapshots.filter((snapshot) => snapshot.kind === "INTERFACE").map((snapshot) => [snapshot.id, String(snapshot.state.mac ?? "")]));
  const printerCandidates = devices.filter((snapshot) => snapshot.type === "printer").flatMap((printer) => {
    const address = typeof dhcpLeases[printer.id]?.state.offeredAddress === "string" ? dhcpLeases[printer.id].state.offeredAddress as string : null;
    const interfaceId = printer.relations.interfaces?.[0];
    const mac = interfaceId ? interfaceMacs.get(interfaceId) : "";
    return address && mac ? [{ id: printer.id, name: printer.id, address, mac }] : [];
  });
  const schemaByType = new Map(schemas.map((schema) => [schema.name, schema]));
  const mediaOf = (deviceType: string, portName: string) =>
    schemaByType.get(deviceType)?.interfaces.find((iface) => iface.name === portName)?.media ?? UNKNOWN_MEDIA;

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
      inspection: inspection?.kind === "node" && inspection.id === snapshot.id
        ? (inspection.pinned ? "pinned" : "hover")
        : undefined,
      softwarePrinters: software.inventories?.[snapshot.id] ?? [],
      printerCandidates,
      onAddPrinter: (printer) => software.onAddPrinter?.(snapshot.id, printer),
      onRenamePrinter: (printer) => software.onRenamePrinter?.(snapshot.id, printer),
      onDeletePrinter: (printer) => software.onDeletePrinter?.(snapshot.id, printer),
      onTestPage: (printer) => software.onTestPage?.(snapshot.id, printer),
      onCableDelete,
      ports: (snapshot.relations.interfaces ?? []).map((id) => {
        const name = id.slice(snapshot.id.length + 1);
        return {
          id,
          name,
          occupied: occupied.has(id),
          cableId: cableByEndpoint.get(id),
          reconnectable: id === reconnectingPortId,
          media: mediaOf(snapshot.type, name),
          enabled: snapshot.state.power === "ON",
        };
      }),
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
      type: "inspectable",
      selectable: false,
      zIndex: 10,
      className: `topology-edge ${cable.state.connected === false ? "link-unused" : cable.state.linkUp === true ? "link-up" : "link-down"}`,
      data: {
        snapshot: cable,
        inspection: inspection?.kind === "edge" && inspection.id === cable.id
          ? (inspection.pinned ? "pinned" : "hover")
          : undefined,
      },
    }];
  });

  return { nodes, edges };
}
