import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PacketInspectorPanel } from "./PacketInspectorPanel";
import type { ObservedPacket } from "./packetInspector";

// This file renders more than once per test file (unlike the project's other component tests), so
// it needs its own explicit unmount between tests; there is no global afterEach(cleanup) configured.
afterEach(cleanup);

const discover: ObservedPacket = {
  seq: 1, transitId: "cable1:t1", cableId: "cable1", sentAtMs: 2000, from: "printer1.eth0", to: "gateway1.eth0",
  sourceMac: "02:00:00:00:00:01", destMac: "ff:ff:ff:ff:ff:ff", protocol: "DHCP",
  sourceIp: "0.0.0.0", destIp: "255.255.255.255", sourcePort: 68, destPort: 67, outcome: "DELIVERED",
  dhcp: { messageType: "DISCOVER", transactionId: "0x1", clientAddress: null, serverIdentifier: null },
};
const dropped: ObservedPacket = {
  seq: 2, transitId: "cable2:t1", cableId: "cable2", sentAtMs: 3000, from: "a.eth0", to: "b.eth0",
  sourceMac: "x", destMac: "y", protocol: "UDP", sourceIp: "10.0.0.5", destIp: "10.0.0.6",
  sourcePort: 9, destPort: 10, outcome: "DROPPED", dropReason: "LINK_DOWN",
};

function renderPanel(packets: ObservedPacket[], overrides: Partial<React.ComponentProps<typeof PacketInspectorPanel>> = {}) {
  const props = {
    packets, filter: {}, paused: false,
    onSetFilter: vi.fn(), onTogglePause: vi.fn(), onClear: vi.fn(), onNavigate: vi.fn(),
    ...overrides,
  };
  render(<PacketInspectorPanel {...props} />);
  return props;
}

describe("PacketInspectorPanel", () => {
  it("shows each packet's time, endpoints, protocol and outcome", () => {
    renderPanel([discover]);
    expect(screen.getByText("printer1.eth0")).toBeInTheDocument();
    expect(screen.getByText("gateway1.eth0")).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "DHCP" })).toBeInTheDocument();
    expect(screen.getByText("DISCOVER")).toBeInTheDocument();
  });

  it("presents the drop reason for a dropped packet", () => {
    renderPanel([dropped]);
    expect(screen.getByText("LINK_DOWN")).toBeInTheDocument();
  });

  it("groups packets sharing a DHCP transaction id under one exchange heading", () => {
    const offer: ObservedPacket = { ...discover, seq: 2, transitId: "cable1:t2", from: "gateway1.eth0", to: "printer1.eth0", dhcp: { messageType: "OFFER", transactionId: "0x1", clientAddress: "10.0.0.100", serverIdentifier: "10.0.0.1" } };
    renderPanel([discover, offer]);
    expect(screen.getAllByRole("group")).toHaveLength(1);
    expect(screen.getByText("DISCOVER")).toBeInTheDocument();
    expect(screen.getByText("OFFER")).toBeInTheDocument();
  });

  it("clicking an endpoint navigates to its owning node", () => {
    const props = renderPanel([discover]);
    fireEvent.click(screen.getByText("printer1.eth0"));
    expect(props.onNavigate).toHaveBeenCalledWith("printer1");
  });

  it("toggling pause and clicking clear call back to the parent", () => {
    const props = renderPanel([discover]);
    fireEvent.click(screen.getByRole("button", { name: "Pause" }));
    expect(props.onTogglePause).toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Clear" }));
    expect(props.onClear).toHaveBeenCalled();
  });

  it("shows Resume once paused", () => {
    renderPanel([discover], { paused: true });
    expect(screen.getByRole("button", { name: "Resume" })).toBeInTheDocument();
  });

  it("changing the protocol filter calls back with the new filter", () => {
    const props = renderPanel([discover, dropped]);
    fireEvent.change(screen.getByLabelText("Protocol"), { target: { value: "DHCP" } });
    expect(props.onSetFilter).toHaveBeenCalledWith({ protocol: "DHCP" });
  });

  it("checking dropped-only calls back with the new filter", () => {
    const props = renderPanel([discover, dropped]);
    fireEvent.click(screen.getByLabelText("Dropped only"));
    expect(props.onSetFilter).toHaveBeenCalledWith({ onlyDropped: true });
  });

  it("applies the active filter so only matching packets render", () => {
    renderPanel([discover, dropped], { filter: { onlyDropped: true } });
    expect(screen.queryByText("DISCOVER")).not.toBeInTheDocument();
    expect(screen.getByText("LINK_DOWN")).toBeInTheDocument();
  });

  it("shows an empty state with no packets", () => {
    renderPanel([]);
    expect(screen.getByText(/no packets/i)).toBeInTheDocument();
  });
});
