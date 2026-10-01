import { describe, expect, it } from "vitest";
import { analyzeGraph } from "./graphAnalysis";

describe("analyzeGraph", () => {
  it("reports shortest paths, component, degree and articulation status", () => {
    const result = analyzeGraph(["a", "switch", "b"], [["a", "switch"], ["switch", "b"]], "switch");
    expect(result.degree).toBe(2);
    expect(result.component).toEqual(["a", "b", "switch"]);
    expect(result.shortestPaths.b).toEqual(["switch", "b"]);
    expect(result.articulationPoint).toBe(true);
  });
});
