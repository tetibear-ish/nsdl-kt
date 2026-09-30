import type { XYPosition } from "@xyflow/react";
import { create } from "zustand";

/** What an object was created with -- a live ObjectSnapshot mixes this with derived runtime state,
 * so it can't be reliably reconstructed after the fact. Tracked here so the lab can be saved/exported. */
export type ObjectRecord = { type: string; props: Record<string, unknown> };

type EditorState = {
  positions: Record<string, XYPosition>;
  records: Record<string, ObjectRecord>;
  setPosition: (id: string, position: XYPosition) => void;
  setRecord: (id: string, record: ObjectRecord) => void;
  removeRecord: (id: string) => void;
};

export const useEditorStore = create<EditorState>((set) => ({
  positions: {},
  records: {},
  setPosition: (id, position) => set((state) => ({
    positions: { ...state.positions, [id]: position },
  })),
  setRecord: (id, record) => set((state) => ({
    records: { ...state.records, [id]: record },
  })),
  removeRecord: (id) => set((state) => {
    const { [id]: _removed, ...rest } = state.records;
    return { records: rest };
  }),
}));
