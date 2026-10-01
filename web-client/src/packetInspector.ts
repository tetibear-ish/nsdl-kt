import type { SimulationEvent } from "./types";

export type TransitOutcome = "DELIVERED" | "DROPPED";

export type DhcpSummary = {
  messageType: string;
  transactionId: string;
  clientAddress: string | null;
  serverIdentifier: string | null;
};

/** One decoded PacketObserved event, flattened for the packet-inspection pane and per-cable history. */
export type ObservedPacket = {
  seq: number;
  transitId: string;
  cableId: string;
  sentAtMs: number;
  from: string;
  to: string;
  sourceMac: string;
  destMac: string;
  protocol: string;
  sourceIp?: string;
  destIp?: string;
  sourcePort?: number;
  destPort?: number;
  outcome: TransitOutcome;
  dropReason?: string;
  dhcp?: DhcpSummary;
};

export const MAX_PACKET_EVENTS = 500;

function asString(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}

function asNumber(value: unknown): number | undefined {
  return typeof value === "number" ? value : undefined;
}

function asDhcp(value: unknown): DhcpSummary | undefined {
  if (!value || typeof value !== "object") return undefined;
  const v = value as Record<string, unknown>;
  return {
    messageType: asString(v.messageType) ?? "",
    transactionId: asString(v.transactionId) ?? "",
    clientAddress: asString(v.clientAddress) ?? null,
    serverIdentifier: asString(v.serverIdentifier) ?? null,
  };
}

/** Maps a pushed wire event to a decoded packet record, or null if it isn't a PacketObserved event. */
export function eventToPacket(event: SimulationEvent): ObservedPacket | null {
  if (event.type !== "event" || event.eventType !== "PacketObserved" || !event.source || !event.data) return null;
  const data = event.data;
  return {
    seq: event.seq ?? 0,
    transitId: asString(data.transitId) ?? "",
    cableId: event.source,
    sentAtMs: asNumber(data.sentAtMs) ?? 0,
    from: asString(data.from) ?? "",
    to: asString(data.to) ?? "",
    sourceMac: asString(data.sourceMac) ?? "",
    destMac: asString(data.destMac) ?? "",
    protocol: asString(data.protocol) ?? "ETHERNET",
    sourceIp: asString(data.sourceIp),
    destIp: asString(data.destIp),
    sourcePort: asNumber(data.sourcePort),
    destPort: asNumber(data.destPort),
    outcome: data.outcome === "DROPPED" ? "DROPPED" : "DELIVERED",
    dropReason: asString(data.dropReason),
    dhcp: asDhcp(data.dhcp),
  };
}

/** Appends to a bounded packet log, dropping the oldest once over capacity. Mirrors activity.ts's pushEvent. */
export function pushPacket(events: ObservedPacket[], event: ObservedPacket, maxEvents: number): ObservedPacket[] {
  const next = [...events, event];
  return next.length > maxEvents ? next.slice(next.length - maxEvents) : next;
}

export type PacketFilter = {
  protocol?: string;
  /** Matches either endpoint exactly, or as a parent of a child port id (e.g. "printer1" matches "printer1.eth0"). */
  nodeId?: string;
  onlyDropped?: boolean;
  text?: string;
};

function endpointMatches(endpoint: string, nodeId: string): boolean {
  return endpoint === nodeId || endpoint.startsWith(`${nodeId}.`);
}

export function matchesFilter(event: ObservedPacket, filter: PacketFilter): boolean {
  if (filter.protocol && event.protocol !== filter.protocol) return false;
  if (filter.onlyDropped && event.outcome !== "DROPPED") return false;
  if (filter.nodeId && !endpointMatches(event.from, filter.nodeId) && !endpointMatches(event.to, filter.nodeId)) return false;
  if (filter.text) {
    const haystack = [
      event.sourceMac, event.destMac, event.sourceIp, event.destIp, event.protocol,
      event.dhcp?.messageType, event.dhcp?.clientAddress, event.dhcp?.serverIdentifier,
    ].filter(Boolean).join(" ").toLowerCase();
    if (!haystack.includes(filter.text.toLowerCase())) return false;
  }
  return true;
}

export function filterPackets(events: ObservedPacket[], filter: PacketFilter): ObservedPacket[] {
  return events.filter((event) => matchesFilter(event, filter));
}

/**
 * Derived protocol-exchange grouping key for presentation only: DHCP messages group by transaction
 * id (a true protocol-exchange identifier already in the typed message); other UDP traffic falls
 * back to its sorted address:port pair so both directions of a flow land in one group. This is NOT
 * the backend intention/process/session causal hierarchy from PLAN.md's cross-cutting observability
 * model -- nothing populates real intentionId/processId/sessionId values yet, so this grouping is
 * the practical stand-in for "protocol exchange" until that hierarchy exists.
 */
export function exchangeKey(event: ObservedPacket): string {
  if (event.dhcp) return `dhcp:${event.dhcp.transactionId}`;
  if (event.sourceIp && event.destIp && event.sourcePort !== undefined && event.destPort !== undefined) {
    const endpoints = [`${event.sourceIp}:${event.sourcePort}`, `${event.destIp}:${event.destPort}`].sort();
    return `${event.protocol}:${endpoints.join("<->")}`;
  }
  return `${event.protocol}:${[event.from, event.to].sort().join("<->")}`;
}

/** The owning node of a port/interface/service id (e.g. "printer1.eth0" -> "printer1"), for
 * navigating from a packet's endpoint to the node whose decision log explains it. */
export function rootNodeId(endpointId: string): string {
  const dot = endpointId.indexOf(".");
  return dot === -1 ? endpointId : endpointId.slice(0, dot);
}

export type ExchangeGroup = { key: string; events: ObservedPacket[] };

/** Groups packets by [exchangeKey], preserving first-seen order of both groups and events within a group. */
export function groupByExchange(events: ObservedPacket[]): ExchangeGroup[] {
  const order: string[] = [];
  const groups = new Map<string, ObservedPacket[]>();
  for (const event of events) {
    const key = exchangeKey(event);
    const bucket = groups.get(key);
    if (bucket) bucket.push(event);
    else { groups.set(key, [event]); order.push(key); }
  }
  return order.map((key) => ({ key, events: groups.get(key)! }));
}
