import { create } from "zustand";

type ClockTimeState = { nowMs: number; setNowMs: (nowMs: number) => void };

/** Mirrors the active transport's virtual-time reading so any component can render a live
 * countdown (e.g. a DHCP lease) without going through App's node/edge state. */
export const useClockTimeStore = create<ClockTimeState>((set) => ({
  nowMs: 0,
  setNowMs: (nowMs) => set({ nowMs }),
}));
