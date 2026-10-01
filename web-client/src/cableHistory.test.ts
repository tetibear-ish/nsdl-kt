import { describe, expect, it } from "vitest";
import { parseCableFrames, parseCablePackets } from "./cableHistory";
import type { ObjectSnapshot } from "./types";

function cableSnapshot(state: Record<string, unknown>): ObjectSnapshot {
  return { id: "cable1", type: "cat5-cable", kind: "CABLE", state, relations: {} };
}

describe("parseCableFrames", () => {
  it("decodes a delivered frame entry", () => {
    const snapshot = cableSnapshot({
      frames: [{ id: "cable1:t1", sentAtMs: 10, from: "a.eth0", to: "b.eth0", sourceMac: "x", destMac: "y", etherType: "0x0800", outcome: "DELIVERED", dropReason: null }],
      packets: [],
    });
    expect(parseCableFrames(snapshot)).toEqual([
      { id: "cable1:t1", sentAtMs: 10, from: "a.eth0", to: "b.eth0", sourceMac: "x", destMac: "y", etherType: "0x0800", outcome: "DELIVERED", dropReason: undefined },
    ]);
  });

  it("decodes a dropped frame's reason", () => {
    const snapshot = cableSnapshot({
      frames: [{ id: "cable1:t2", sentAtMs: 20, from: "a.eth0", to: "b.eth0", sourceMac: "x", destMac: "y", etherType: "0x0800", outcome: "DROPPED", dropReason: "DISCONNECTED_IN_FLIGHT" }],
      packets: [],
    });
    expect(parseCableFrames(snapshot)[0].dropReason).toBe("DISCONNECTED_IN_FLIGHT");
  });

  it("is empty when the snapshot has no frames field", () => {
    expect(parseCableFrames(cableSnapshot({}))).toEqual([]);
  });
});

describe("parseCablePackets", () => {
  it("decodes a UDP packet entry's envelope fields", () => {
    const snapshot = cableSnapshot({
      frames: [],
      packets: [{ id: "cable1:t1", sentAtMs: 10, from: "a.eth0", to: "b.eth0", outcome: "DELIVERED", dropReason: null, protocol: "UDP", sourceIp: "10.0.0.1", destIp: "10.0.0.2", sourcePort: 68, destPort: 67 }],
    });
    expect(parseCablePackets(snapshot)).toEqual([
      { id: "cable1:t1", sentAtMs: 10, from: "a.eth0", to: "b.eth0", outcome: "DELIVERED", dropReason: undefined, protocol: "UDP", sourceIp: "10.0.0.1", destIp: "10.0.0.2", sourcePort: 68, destPort: 67, dhcp: undefined },
    ]);
  });

  it("decodes a nested DHCP summary", () => {
    const snapshot = cableSnapshot({
      frames: [],
      packets: [{
        id: "cable1:t1", sentAtMs: 10, from: "a.eth0", to: "b.eth0", outcome: "DELIVERED", protocol: "DHCP",
        dhcp: { messageType: "DISCOVER", transactionId: "0x1", clientAddress: null, serverIdentifier: null },
      }],
    });
    expect(parseCablePackets(snapshot)[0].dhcp).toEqual({ messageType: "DISCOVER", transactionId: "0x1", clientAddress: null, serverIdentifier: null });
  });

  it("is empty when the snapshot has no packets field", () => {
    expect(parseCablePackets(cableSnapshot({}))).toEqual([]);
  });

  it("shares ids with the frame view for the same transit, so a student can correlate them", () => {
    const snapshot = cableSnapshot({
      frames: [{ id: "cable1:t1", sentAtMs: 10, from: "a.eth0", to: "b.eth0", sourceMac: "x", destMac: "y", etherType: "0x0800", outcome: "DELIVERED" }],
      packets: [{ id: "cable1:t1", sentAtMs: 10, from: "a.eth0", to: "b.eth0", outcome: "DELIVERED", protocol: "UDP" }],
    });
    expect(parseCableFrames(snapshot)[0].id).toBe(parseCablePackets(snapshot)[0].id);
  });
});
