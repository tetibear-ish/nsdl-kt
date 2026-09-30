import { describe, expect, it } from "vitest";
import { projectTopology } from "./topology";
import type { ObjectSnapshot } from "./types";

describe("projectTopology", () => {
  it("projects device interfaces to handles and a connected cable to an edge", () => {
    const snapshots: ObjectSnapshot[] = [
      {
        id: "printer1",
        type: "printer",
        kind: "DEVICE",
        state: { power: "OFF" },
        relations: { interfaces: ["printer1.eth0"] },
      },
      {
        id: "switch1",
        type: "ethernet-switch",
        kind: "DEVICE",
        state: { power: "ON" },
        relations: {
          interfaces: Array.from({ length: 8 }, (_, index) => `switch1.port${index + 1}`),
        },
      },
      {
        id: "cable1",
        type: "cat5-cable",
        kind: "CABLE",
        state: { connected: true },
        relations: { endpoints: ["printer1.eth0", "switch1.port1"] },
      },
    ];

    const projected = projectTopology(snapshots);

    expect(projected.nodes).toHaveLength(2);
    expect(projected.nodes.find((node) => node.id === "switch1")?.data.ports).toHaveLength(8);
    expect(projected.edges).toEqual([
      expect.objectContaining({
        id: "cable1",
        source: "printer1",
        sourceHandle: "printer1.eth0",
        target: "switch1",
        targetHandle: "switch1.port1",
      }),
    ]);
  });
});
