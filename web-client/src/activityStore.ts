import { create } from "zustand";
import {
  applyPulse,
  MAX_TRACKED_EVENTS,
  pruneExpired,
  PULSE_DURATION_MS,
  pushEvent,
  type PacketEvent,
  type PulseKind,
  type PulseMap,
} from "./activity";

type ActivityState = {
  pulses: PulseMap;
  events: PacketEvent[];
  record: (seq: number, portId: string, kind: PulseKind, reason?: string, now?: number) => void;
  prune: (now?: number) => void;
};

export const useActivityStore = create<ActivityState>((set) => ({
  pulses: {},
  events: [],
  record: (seq, portId, kind, reason, now = Date.now()) => set((state) => ({
    pulses: applyPulse(state.pulses, portId, kind, now, PULSE_DURATION_MS),
    events: pushEvent(state.events, { seq, portId, kind, reason }, MAX_TRACKED_EVENTS),
  })),
  prune: (now = Date.now()) => set((state) => ({ pulses: pruneExpired(state.pulses, now) })),
}));
