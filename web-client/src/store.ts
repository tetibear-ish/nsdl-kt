import { create } from "zustand";

/** What an object was created with -- a live ObjectSnapshot mixes this with derived runtime state,
 * so it can't be reliably reconstructed after the fact. Tracked here so the lab can be saved/exported.
 *
 * Node canvas positions are deliberately not kept here: they are server-owned collaborative session
 * state synchronized through the transport (see transport.ts's getPositions/movePosition), not local
 * client-only state, so two clients editing the same lab see each other's moves. */
export type ObjectRecord = { type: string; props: Record<string, unknown> };

type EditorState = {
  records: Record<string, ObjectRecord>;
  setRecord: (id: string, record: ObjectRecord) => void;
  removeRecord: (id: string) => void;
};

export const useEditorStore = create<EditorState>((set) => ({
  records: {},
  setRecord: (id, record) => set((state) => ({
    records: { ...state.records, [id]: record },
  })),
  removeRecord: (id) => set((state) => {
    const { [id]: _removed, ...rest } = state.records;
    return { records: rest };
  }),
}));
