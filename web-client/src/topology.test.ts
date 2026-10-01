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
        state: { connected: true, linkUp: false },
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
        data: { snapshot: snapshots[2] },
        className: "topology-edge link-down",
      }),
    ]);
  });

  it("colors operational links green and down links orange", () => {
    const devices: ObjectSnapshot[] = [
      { id: "a", type: "printer", kind: "DEVICE", state: {}, relations: {} },
      { id: "b", type: "ethernet-switch", kind: "DEVICE", state: {}, relations: {} },
    ];
    const cable = (linkUp: boolean): ObjectSnapshot => ({
      id: "c", type: "cat5-cable", kind: "CABLE", state: { connected: true, linkUp },
      relations: { endpoints: ["a.eth0", "b.port1"] },
    });
    expect(projectTopology([...devices, cable(false)]).edges[0].className).toContain("link-down");
    expect(projectTopology([...devices, cable(true)]).edges[0].className).toContain("link-up");
  });

  it("attaches a device's dhcp lease snapshot to its node data when provided", () => {
    const snapshots: ObjectSnapshot[] = [
      { id: "printer1", type: "printer", kind: "DEVICE", state: { power: "ON" }, relations: { interfaces: ["printer1.eth0"] } },
    ];
    const lease: ObjectSnapshot = {
      id: "printer1.dhcp-client", type: "dhcp-client", kind: "PROTOCOL",
      state: { state: "BOUND", offeredAddress: "10.0.0.100" }, relations: {},
    };

    const projected = projectTopology(snapshots, {}, undefined, { printer1: lease });

    expect(projected.nodes[0].data.dhcpLease).toBe(lease);
  });

  it("leaves dhcpLease unset for a device with no lease entry", () => {
    const snapshots: ObjectSnapshot[] = [
      { id: "printer1", type: "printer", kind: "DEVICE", state: { power: "ON" }, relations: {} },
    ];

    const projected = projectTopology(snapshots);

    expect(projected.nodes[0].data.dhcpLease).toBeUndefined();
  });

  it("attaches a device's dhcp server snapshot to its node data when provided", () => {
    const snapshots: ObjectSnapshot[] = [
      { id: "gateway1", type: "gateway", kind: "DEVICE", state: { power: "ON" }, relations: { interfaces: ["gateway1.eth0"] } },
    ];
    const server: ObjectSnapshot = {
      id: "gateway1.dhcp-server", type: "dhcp-server", kind: "PROTOCOL",
      state: { leases: [] }, relations: {},
    };

    const projected = projectTopology(snapshots, {}, undefined, {}, { gateway1: server });

    expect(projected.nodes[0].data.dhcpServer).toBe(server);
  });
});
