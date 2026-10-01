import { create } from "zustand";
import type { PortGuideState } from "./connectionGuide";

type ConnectionDragState = {
  /** Port id -> guide state, populated for the duration of a connection drag. Empty when idle. */
  guide: Record<string, PortGuideState>;
  begin: (guide: Record<string, PortGuideState>) => void;
  end: () => void;
};

/** Tracks the in-progress connection-drag guide outside of node data, the same way
 *  activityStore tracks port pulses -- so highlighting a drag doesn't get clobbered by
 *  the next topology refresh() replacing the whole nodes array. */
export const useConnectionDragStore = create<ConnectionDragState>((set) => ({
  guide: {},
  begin: (guide) => set({ guide }),
  end: () => set({ guide: {} }),
}));
