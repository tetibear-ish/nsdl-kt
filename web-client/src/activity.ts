import type { SimulationEvent } from "./types";

export type PulseKind = "tx" | "rx" | "drop";
export type PortPulse = { kind: PulseKind; expiresAt: number };
/** Port id (e.g. "printer1.eth0") -> its current pulse, if any. */
export type PulseMap = Record<string, PortPulse>;
export type PacketEvent = { seq: number; portId: string; kind: PulseKind; reason?: string };

export const PULSE_DURATION_MS = 400;
export const MAX_TRACKED_EVENTS = 200;

/** Maps a pushed wire event to the port it lights up, or null if it isn't frame activity. */
export function eventToPulse(event: SimulationEvent): { portId: string; kind: PulseKind; reason?: string } | null {
  if (!event.source) return null;
  switch (event.eventType) {
    case "FrameSent": return { portId: event.source, kind: "tx" };
    case "FrameReceived": return { portId: event.source, kind: "rx" };
    case "FrameDropped": return { portId: event.source, kind: "drop", reason: event.data?.reason as string | undefined };
    default: return null;
  }
}

/** Sets (or coalesces, if already pulsing) a port's pulse -- always exactly one entry per port. */
export function applyPulse(pulses: PulseMap, portId: string, kind: PulseKind, now: number, durationMs: number): PulseMap {
  return { ...pulses, [portId]: { kind, expiresAt: now + durationMs } };
}

/** Drops pulses whose expiry has passed; still-active ones are kept untouched. */
export function pruneExpired(pulses: PulseMap, now: number): PulseMap {
  const next: PulseMap = {};
  for (const [portId, pulse] of Object.entries(pulses)) {
    if (pulse.expiresAt > now) next[portId] = pulse;
  }
  return next;
}

/** Appends to a bounded event log, dropping the oldest once over capacity -- burst traffic can't grow it unbounded. */
export function pushEvent(events: PacketEvent[], event: PacketEvent, maxEvents: number): PacketEvent[] {
  const next = [...events, event];
  return next.length > maxEvents ? next.slice(next.length - maxEvents) : next;
}
