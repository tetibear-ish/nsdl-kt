export type UndoEntry = {
  description: string;
  undo: () => void | Promise<void>;
};

/** A bounded, client-side LIFO of reversible topology edits. Each entry knows how to reverse
 * itself -- for server-backed mode that means issuing the inverse runtime command, not just
 * rewriting client graph state (see PLAN.md S15). The stack itself doesn't know which. */
export class UndoStack {
  private entries: UndoEntry[] = [];

  constructor(private readonly capacity: number) {}

  push(entry: UndoEntry): void {
    this.entries.push(entry);
    if (this.entries.length > this.capacity) this.entries.shift();
  }

  get canUndo(): boolean {
    return this.entries.length > 0;
  }

  /** Pops and reverses the most recent entry, returning its description, or null if there is nothing to undo. */
  async undo(): Promise<string | null> {
    const entry = this.entries.pop();
    if (!entry) return null;
    await entry.undo();
    return entry.description;
  }

  clear(): void {
    this.entries = [];
  }
}
