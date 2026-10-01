import { describe, expect, it } from "vitest";
import {
  eventToPacket,
  exchangeKey,
  filterPackets,
  groupByExchange,
  MAX_PACKET_EVENTS,
  matchesFilter,
  pushPacket,
  rootNodeId,
  type ObservedPacket,
} from "./packetInspector";
import type { SimulationEvent } from "./types";

function wireEvent(data: Record<string, unknown>, overrides: Partial<SimulationEvent> = {}): SimulationEvent {
  return { type: "event", eventType: "PacketObserved", source: "cable1", seq: 9, data, ...overrides };
}

describe("eventToPacket: event decoding", () => {
  it("decodes a delivered UDP transit into a flat packet record", () => {
    const event = wireEvent({
      transitId: "cable1:t1", sentAtMs: 1200, from: "a.eth0", to: "b.eth0",
      sourceMac: "02:00:00:00:00:01", destMac: "02:00:00:00:00:02",
      protocol: "UDP", sourceIp: "10.0.0.1", destIp: "10.0.0.2", sourcePort: 68, destPort: 67,
      outcome: "DELIVERED",
    });
    expect(eventToPacket(event)).toEqual({
      seq: 9, transitId: "cable1:t1", cableId: "cable1", sentAtMs: 1200, from: "a.eth0", to: "b.eth0",
      sourceMac: "02:00:00:00:00:01", destMac: "02:00:00:00:00:02", protocol: "UDP",
      sourceIp: "10.0.0.1", destIp: "10.0.0.2", sourcePort: 68, destPort: 67,
      outcome: "DELIVERED", dropReason: undefined, dhcp: undefined,
    });
  });

  it("decodes a dropped transit's reason and a nested DHCP summary", () => {
    const event = wireEvent({
      transitId: "cable1:t2", sentAtMs: 500, from: "a.eth0", to: "b.eth0",
      sourceMac: "x", destMac: "y", protocol: "DHCP", outcome: "DROPPED", dropReason: "DISCONNECTED_IN_FLIGHT",
      dhcp: { messageType: "DISCOVER", transactionId: "0x1", clientAddress: null, serverIdentifier: null },
    });
    const packet = eventToPacket(event)!;
    expect(packet.outcome).toBe("DROPPED");
    expect(packet.dropReason).toBe("DISCONNECTED_IN_FLIGHT");
    expect(packet.dhcp).toEqual({ messageType: "DISCOVER", transactionId: "0x1", clientAddress: null, serverIdentifier: null });
  });

  it("returns null for events that aren't PacketObserved", () => {
    expect(eventToPacket(wireEvent({}, { eventType: "FrameSent" }))).toBeNull();
  });

  it("returns null for a gap message", () => {
    expect(eventToPacket({ type: "gap", resync: true })).toBeNull();
  });
});

describe("pushPacket: bounded retention", () => {
  const packet = (seq: number): ObservedPacket => ({
    seq, transitId: `t${seq}`, cableId: "cable1", sentAtMs: seq, from: "a", to: "b",
    sourceMac: "x", destMac: "y", protocol: "UDP", outcome: "DELIVERED",
  });

  it("keeps everything under the cap", () => {
    let events: ObservedPacket[] = [];
    events = pushPacket(events, packet(1), 5);
    events = pushPacket(events, packet(2), 5);
    expect(events.map((e) => e.seq)).toEqual([1, 2]);
  });

  it("drops the oldest once over the bounded capacity", () => {
    let events: ObservedPacket[] = [];
    for (let seq = 1; seq <= 10; seq++) events = pushPacket(events, packet(seq), 5);
    expect(events.map((e) => e.seq)).toEqual([6, 7, 8, 9, 10]);
  });

  it("the default capacity is a sane positive bound", () => {
    expect(MAX_PACKET_EVENTS).toBeGreaterThan(0);
  });
});

describe("matchesFilter / filterPackets", () => {
  const dhcpDiscover: ObservedPacket = {
    seq: 1, transitId: "t1", cableId: "cable1", sentAtMs: 0, from: "printer1.eth0", to: "gateway1.eth0",
    sourceMac: "x", destMac: "y", protocol: "DHCP", sourceIp: "0.0.0.0", destIp: "255.255.255.255",
    sourcePort: 68, destPort: 67, outcome: "DELIVERED",
    dhcp: { messageType: "DISCOVER", transactionId: "0x1", clientAddress: null, serverIdentifier: null },
  };
  const droppedUdp: ObservedPacket = {
    seq: 2, transitId: "t2", cableId: "cable2", sentAtMs: 10, from: "x.eth0", to: "y.eth0",
    sourceMac: "x", destMac: "y", protocol: "UDP", sourceIp: "10.0.0.5", destIp: "10.0.0.6",
    sourcePort: 9, destPort: 10, outcome: "DROPPED", dropReason: "LINK_DOWN",
  };

  it("filters by protocol", () => {
    expect(matchesFilter(dhcpDiscover, { protocol: "DHCP" })).toBe(true);
    expect(matchesFilter(droppedUdp, { protocol: "DHCP" })).toBe(false);
  });

  it("filters to only dropped packets", () => {
    expect(filterPackets([dhcpDiscover, droppedUdp], { onlyDropped: true })).toEqual([droppedUdp]);
  });

  it("filters by a node id matching either endpoint, including child port ids", () => {
    expect(matchesFilter(dhcpDiscover, { nodeId: "printer1" })).toBe(true);
    expect(matchesFilter(dhcpDiscover, { nodeId: "gateway1" })).toBe(true);
    expect(matchesFilter(dhcpDiscover, { nodeId: "switch1" })).toBe(false);
  });

  it("filters by free text across addresses and the DHCP message type", () => {
    expect(matchesFilter(dhcpDiscover, { text: "discover" })).toBe(true);
    expect(matchesFilter(dhcpDiscover, { text: "nope" })).toBe(false);
    expect(matchesFilter(droppedUdp, { text: "10.0.0.5" })).toBe(true);
  });

  it("an empty filter matches everything", () => {
    expect(filterPackets([dhcpDiscover, droppedUdp], {})).toEqual([dhcpDiscover, droppedUdp]);
  });
});

describe("rootNodeId: navigation back to the owning node", () => {
  it("strips a port/service suffix", () => expect(rootNodeId("printer1.eth0")).toBe("printer1"));
  it("strips a nested service suffix", () => expect(rootNodeId("gateway1.dhcp-server")).toBe("gateway1"));
  it("is the id itself when there is no suffix", () => expect(rootNodeId("cable1")).toBe("cable1"));
});

describe("exchangeKey / groupByExchange: protocol-exchange grouping", () => {
  const discover: ObservedPacket = {
    seq: 1, transitId: "t1", cableId: "cable1", sentAtMs: 0, from: "printer1.eth0", to: "gateway1.eth0",
    sourceMac: "x", destMac: "y", protocol: "DHCP", outcome: "DELIVERED",
    dhcp: { messageType: "DISCOVER", transactionId: "0x1", clientAddress: null, serverIdentifier: null },
  };
  const offer: ObservedPacket = {
    ...discover, seq: 2, transitId: "t2", from: "gateway1.eth0", to: "printer1.eth0",
    dhcp: { messageType: "OFFER", transactionId: "0x1", clientAddress: "10.0.0.100", serverIdentifier: "10.0.0.1" },
  };
  const otherExchange: ObservedPacket = {
    ...discover, seq: 3, transitId: "t3",
    dhcp: { messageType: "DISCOVER", transactionId: "0x2", clientAddress: null, serverIdentifier: null },
  };
  const plainUdp: ObservedPacket = {
    seq: 4, transitId: "t4", cableId: "cable1", sentAtMs: 0, from: "a.eth0", to: "b.eth0",
    sourceMac: "x", destMac: "y", protocol: "UDP", sourceIp: "10.0.0.5", destIp: "10.0.0.6",
    sourcePort: 9, destPort: 10, outcome: "DELIVERED",
  };

  it("groups DHCP messages that share a transaction id into one exchange regardless of direction", () => {
    expect(exchangeKey(discover)).toBe(exchangeKey(offer));
  });

  it("keeps a different transaction id as a separate exchange", () => {
    expect(exchangeKey(discover)).not.toBe(exchangeKey(otherExchange));
  });

  it("groupByExchange preserves first-seen order and buckets correctly", () => {
    const groups = groupByExchange([discover, offer, otherExchange, plainUdp]);
    expect(groups.map((g) => g.events.length)).toEqual([2, 1, 1]);
    expect(groups[0].events).toEqual([discover, offer]);
  });
});
