import { describe, expect, it, vi } from "vitest";
import { UndoStack } from "./undoStack";

describe("UndoStack", () => {
  it("undoes the most recently pushed entry first (LIFO ordering)", async () => {
    const stack = new UndoStack(10);
    const order: string[] = [];
    stack.push({ description: "first", undo: () => { order.push("first"); } });
    stack.push({ description: "second", undo: () => { order.push("second"); } });

    await stack.undo();
    await stack.undo();

    expect(order).toEqual(["second", "first"]);
  });

  it("calling undo on an empty stack is a no-op that returns null", async () => {
    const stack = new UndoStack(10);
    await expect(stack.undo()).resolves.toBeNull();
  });

  it("returns the entry's description on a successful undo", async () => {
    const stack = new UndoStack(10);
    stack.push({ description: "created printer1", undo: () => {} });
    await expect(stack.undo()).resolves.toBe("created printer1");
  });

  it("awaits an async undo function before resolving", async () => {
    const stack = new UndoStack(10);
    const undo = vi.fn().mockResolvedValue(undefined);
    stack.push({ description: "x", undo });
    await stack.undo();
    expect(undo).toHaveBeenCalledTimes(1);
  });

  it("drops the oldest entry once the bounded capacity is exceeded", async () => {
    const stack = new UndoStack(2);
    const order: string[] = [];
    stack.push({ description: "a", undo: () => { order.push("a"); } });
    stack.push({ description: "b", undo: () => { order.push("b"); } });
    stack.push({ description: "c", undo: () => { order.push("c"); } });

    await stack.undo();
    await stack.undo();
    const thirdUndo = await stack.undo();

    expect(order).toEqual(["c", "b"]);
    expect(thirdUndo).toBeNull();
  });

  it("canUndo reflects whether anything is available", () => {
    const stack = new UndoStack(10);
    expect(stack.canUndo).toBe(false);
    stack.push({ description: "x", undo: () => {} });
    expect(stack.canUndo).toBe(true);
  });

  it("clear empties the stack", () => {
    const stack = new UndoStack(10);
    stack.push({ description: "x", undo: () => {} });
    stack.clear();
    expect(stack.canUndo).toBe(false);
  });
});
