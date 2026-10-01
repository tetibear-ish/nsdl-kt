import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CableHistoryPanel } from "./CableHistoryPanel";
import type { ObjectSnapshot } from "./types";

afterEach(cleanup);

function snapshot(state: Record<string, unknown>): ObjectSnapshot {
  return { id: "cable1", type: "cat5-cable", kind: "CABLE", state, relations: {} };
}

const withHistory = snapshot({
  connected: true, linkUp: true,
  frames: [{ id: "cable1:t1", sentAtMs: 10, from: "a.eth0", to: "b.eth0", sourceMac: "x", destMac: "y", etherType: "0x0800", outcome: "DELIVERED" }],
  packets: [{
    id: "cable1:t1", sentAtMs: 10, from: "a.eth0", to: "b.eth0", outcome: "DELIVERED", protocol: "DHCP",
    dhcp: { messageType: "DISCOVER", transactionId: "0x1", clientAddress: null, serverIdentifier: null },
  }],
});

describe("CableHistoryPanel", () => {
  it("defaults to the decoded-packet view", () => {
    render(<CableHistoryPanel snapshot={withHistory} onNavigate={vi.fn()} />);
    expect(screen.getByText("DISCOVER")).toBeInTheDocument();
  });

  it("switches to the frame view and shows MAC addresses instead", () => {
    render(<CableHistoryPanel snapshot={withHistory} onNavigate={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Frames" }));
    expect(screen.getByText("x")).toBeInTheDocument();
    expect(screen.getByText("y")).toBeInTheDocument();
  });

  it("navigates to the owning node when an endpoint is clicked", () => {
    const onNavigate = vi.fn();
    render(<CableHistoryPanel snapshot={withHistory} onNavigate={onNavigate} />);
    fireEvent.click(screen.getByText("a.eth0"));
    expect(onNavigate).toHaveBeenCalledWith("a");
  });

  it("shows the drop reason for a dropped transit", () => {
    const dropped = snapshot({
      frames: [{ id: "cable1:t2", sentAtMs: 20, from: "a.eth0", to: "b.eth0", sourceMac: "x", destMac: "y", etherType: "0x0800", outcome: "DROPPED", dropReason: "DISCONNECTED_IN_FLIGHT" }],
      packets: [],
    });
    render(<CableHistoryPanel snapshot={dropped} onNavigate={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Frames" }));
    expect(screen.getByText("DISCONNECTED_IN_FLIGHT")).toBeInTheDocument();
  });

  it("shows an empty state when nothing has transited yet", () => {
    render(<CableHistoryPanel snapshot={snapshot({})} onNavigate={vi.fn()} />);
    expect(screen.getByText(/no packets/i)).toBeInTheDocument();
  });
});
