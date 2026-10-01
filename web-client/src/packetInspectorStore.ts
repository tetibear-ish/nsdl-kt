import { create } from "zustand";
import { MAX_PACKET_EVENTS, pushPacket, type ObservedPacket, type PacketFilter } from "./packetInspector";

type PacketInspectorState = {
  events: ObservedPacket[];
  paused: boolean;
  filter: PacketFilter;
  /** No-op while paused, so a capture freeze costs nothing and never blocks the simulation thread
   * that published the event -- it already ran, this just declines to keep it. */
  record: (event: ObservedPacket) => void;
  setPaused: (paused: boolean) => void;
  clear: () => void;
  setFilter: (filter: PacketFilter) => void;
};

export const usePacketInspectorStore = create<PacketInspectorState>((set, get) => ({
  events: [],
  paused: false,
  filter: {},
  record: (event) => {
    if (get().paused) return;
    set((state) => ({ events: pushPacket(state.events, event, MAX_PACKET_EVENTS) }));
  },
  setPaused: (paused) => set({ paused }),
  clear: () => set({ events: [] }),
  setFilter: (filter) => set({ filter }),
}));
