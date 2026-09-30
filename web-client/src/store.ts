import type { XYPosition } from "@xyflow/react";
import { create } from "zustand";

type EditorState = {
  positions: Record<string, XYPosition>;
  setPosition: (id: string, position: XYPosition) => void;
};

export const useEditorStore = create<EditorState>((set) => ({
  positions: {},
  setPosition: (id, position) => set((state) => ({
    positions: { ...state.positions, [id]: position },
  })),
}));
