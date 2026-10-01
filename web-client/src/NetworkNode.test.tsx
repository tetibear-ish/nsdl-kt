import { render, screen } from "@testing-library/react";
import { ReactFlowProvider } from "@xyflow/react";
import type { ComponentType } from "react";
import { describe, expect, it } from "vitest";
import { NetworkNode } from "./NetworkNode";

const gatewayData = {
  snapshot: {
    id: "gateway1",
    type: "gateway",
    kind: "DEVICE" as const,
    state: { power: "ON" },
    relations: { interfaces: ["gateway1.eth0"] },
  },
  ports: [{ id: "gateway1.eth0", name: "eth0", occupied: true }],
  dhcpServer: {
    id: "gateway1.dhcp-server",
    type: "dhcp-server",
    kind: "PROTOCOL" as const,
    state: { leases: [] },
    relations: {},
  },
};

describe("NetworkNode gateway lease table", () => {
  it("renders stable headers and a spanning empty-state cell before the first lease", () => {
    const TestNetworkNode = NetworkNode as unknown as ComponentType<{ data: typeof gatewayData }>;
    render(
      <ReactFlowProvider>
        <TestNetworkNode data={gatewayData} />
      </ReactFlowProvider>,
    );

    expect(screen.getByRole("columnheader", { name: "Address" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "MAC address" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Lease remaining" })).toBeInTheDocument();
    const waiting = screen.getByText("Waiting for DHCP Broadcast...");
    expect(waiting).toHaveAttribute("colspan", "3");
  });
});
