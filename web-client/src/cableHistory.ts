import type { DhcpSummary } from "./packetInspector";
import type { ObjectSnapshot } from "./types";

export type TransitOutcome = "DELIVERED" | "DROPPED";

/** One entry of a cable's own bounded frame-layer history (snapshot.state.frames). */
export type CableFrameEntry = {
  id: string;
  sentAtMs: number;
  from: string;
  to: string;
  sourceMac: string;
  destMac: string;
  etherType: string;
  outcome: TransitOutcome;
  dropReason?: string;
};

/** One entry of a cable's own bounded decoded-packet-layer history (snapshot.state.packets).
 * Shares its [id] with the corresponding [CableFrameEntry] for the same transit. */
export type CablePacketEntry = {
  id: string;
  sentAtMs: number;
  from: string;
  to: string;
  outcome: TransitOutcome;
  dropReason?: string;
  protocol?: string;
  sourceIp?: string;
  destIp?: string;
  sourcePort?: number;
  destPort?: number;
  dhcp?: DhcpSummary;
};

function asString(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}

function asNumber(value: unknown): number | undefined {
  return typeof value === "number" ? value : undefined;
}

function asOutcome(value: unknown): TransitOutcome {
  return value === "DROPPED" ? "DROPPED" : "DELIVERED";
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

function asRecordList(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value) ? value.filter((item): item is Record<string, unknown> => typeof item === "object" && item !== null) : [];
}

export function parseCableFrames(snapshot: ObjectSnapshot): CableFrameEntry[] {
  return asRecordList(snapshot.state.frames).map((f) => ({
    id: asString(f.id) ?? "",
    sentAtMs: asNumber(f.sentAtMs) ?? 0,
    from: asString(f.from) ?? "",
    to: asString(f.to) ?? "",
    sourceMac: asString(f.sourceMac) ?? "",
    destMac: asString(f.destMac) ?? "",
    etherType: asString(f.etherType) ?? "",
    outcome: asOutcome(f.outcome),
    dropReason: asString(f.dropReason),
  }));
}

export function parseCablePackets(snapshot: ObjectSnapshot): CablePacketEntry[] {
  return asRecordList(snapshot.state.packets).map((p) => ({
    id: asString(p.id) ?? "",
    sentAtMs: asNumber(p.sentAtMs) ?? 0,
    from: asString(p.from) ?? "",
    to: asString(p.to) ?? "",
    outcome: asOutcome(p.outcome),
    dropReason: asString(p.dropReason),
    protocol: asString(p.protocol),
    sourceIp: asString(p.sourceIp),
    destIp: asString(p.destIp),
    sourcePort: asNumber(p.sourcePort),
    destPort: asNumber(p.destPort),
    dhcp: asDhcp(p.dhcp),
  }));
}
