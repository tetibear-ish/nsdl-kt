import { render, screen } from "@testing-library/react";
import { ReactFlowProvider } from "@xyflow/react";
import type { ComponentType } from "react";
import { describe, expect, it } from "vitest";
import { useConnectionDragStore } from "./connectionDragStore";
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
    expect(screen.getByRole("columnheader", { name: "Lease" })).toBeInTheDocument();
    const waiting = screen.getByText("Waiting for DHCP Broadcast...");
    expect(waiting).toHaveAttribute("colspan", "3");
  });
});

const switchData = {
  snapshot: {
    id: "switch1",
    type: "ethernet-switch",
    kind: "DEVICE" as const,
    state: { power: "OFF" },
    relations: { interfaces: ["switch1.port1", "switch1.port2"] },
  },
  ports: [
    { id: "switch1.port1", name: "port1", occupied: true, media: "TWISTED_PAIR", enabled: false },
    { id: "switch1.port2", name: "port2", occupied: false, media: "TWISTED_PAIR", enabled: false },
  ],
};

describe("NetworkNode port labels and guidance", () => {
  it("labels every port with its name", () => {
    const TestNetworkNode = NetworkNode as unknown as ComponentType<{ data: typeof switchData }>;
    render(
      <ReactFlowProvider>
        <TestNetworkNode data={switchData} />
      </ReactFlowProvider>,
    );

    expect(screen.getByText("port1")).toBeInTheDocument();
    expect(screen.getByText("port2")).toBeInTheDocument();
  });

  it("marks ports on a powered-off device as disabled", () => {
    const TestNetworkNode = NetworkNode as unknown as ComponentType<{ data: typeof switchData }>;
    const { container } = render(
      <ReactFlowProvider>
        <TestNetworkNode data={switchData} />
      </ReactFlowProvider>,
    );

    const handles = container.querySelectorAll(".port");
    handles.forEach((handle) => expect(handle).toHaveClass("disabled"));
  });

  it("applies the active connection-drag guide as a port class", () => {
    useConnectionDragStore.getState().begin({ "switch1.port1": "occupied", "switch1.port2": "compatible" });
    try {
      const TestNetworkNode = NetworkNode as unknown as ComponentType<{ data: typeof switchData }>;
      const { container } = render(
        <ReactFlowProvider>
          <TestNetworkNode data={switchData} />
        </ReactFlowProvider>,
      );

      expect(container.querySelector('[data-handleid="switch1.port1"]')).toHaveClass("guide-occupied");
      expect(container.querySelector('[data-handleid="switch1.port2"]')).toHaveClass("guide-compatible");
    } finally {
      useConnectionDragStore.getState().end();
    }
  });
});
