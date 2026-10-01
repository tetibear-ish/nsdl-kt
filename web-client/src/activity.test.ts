import { describe, expect, it } from "vitest";
import {
  applyPulse,
  eventToPulse,
  MAX_TRACKED_EVENTS,
  pruneExpired,
  PULSE_DURATION_MS,
  pushEvent,
  type PacketEvent,
} from "./activity";
import type { SimulationEvent } from "./types";

describe("eventToPulse: endpoint mapping and TX/RX/drop direction", () => {
  it("maps FrameSent at a port to a tx pulse for that port", () => {
    const event: SimulationEvent = { type: "event", eventType: "FrameSent", source: "printer1.eth0" };
    expect(eventToPulse(event)).toEqual({ portId: "printer1.eth0", kind: "tx" });
  });

  it("maps FrameReceived at a port to an rx pulse for that port", () => {
    const event: SimulationEvent = { type: "event", eventType: "FrameReceived", source: "switch1.port1" };
    expect(eventToPulse(event)).toEqual({ portId: "switch1.port1", kind: "rx" });
  });

  it("maps FrameDropped to a drop pulse, carrying the reason through", () => {
    const event: SimulationEvent = {
      type: "event", eventType: "FrameDropped", source: "printer1.eth0", data: { reason: "LINK_DOWN" },
    };
    expect(eventToPulse(event)).toEqual({ portId: "printer1.eth0", kind: "drop", reason: "LINK_DOWN" });
  });

  it("returns null for event types that aren't frame activity", () => {
    const event: SimulationEvent = { type: "event", eventType: "BootCompleted", source: "printer1" };
    expect(eventToPulse(event)).toBeNull();
  });

  it("returns null when the event has no source", () => {
    const event: SimulationEvent = { type: "event", eventType: "FrameSent" };
    expect(eventToPulse(event)).toBeNull();
  });

  it("returns null for a gap message", () => {
    expect(eventToPulse({ type: "gap", resync: true })).toBeNull();
  });
});

describe("applyPulse", () => {
  it("sets a port's pulse to expire pulseDurationMs after now", () => {
    const pulses = applyPulse({}, "printer1.eth0", "tx", 1000, 400);
    expect(pulses).toEqual({ "printer1.eth0": { kind: "tx", expiresAt: 1400, count: 1 } });
  });

  it("a later event at the same port overwrites (coalesces) the earlier pulse rather than accumulating", () => {
    let pulses = applyPulse({}, "printer1.eth0", "tx", 1000, 400);
    pulses = applyPulse(pulses, "printer1.eth0", "rx", 1100, 400);
    expect(Object.keys(pulses)).toHaveLength(1);
    expect(pulses["printer1.eth0"]).toEqual({ kind: "rx", expiresAt: 1500, count: 2 });
  });

  it("leaves other ports' pulses untouched", () => {
    let pulses = applyPulse({}, "a.eth0", "tx", 1000, 400);
    pulses = applyPulse(pulses, "b.eth0", "rx", 1000, 400);
    expect(Object.keys(pulses).sort()).toEqual(["a.eth0", "b.eth0"]);
  });
});

describe("pruneExpired", () => {
  it("removes only pulses whose expiry has passed, keeping still-active ones", () => {
    const pulses = {
      expired: { kind: "tx" as const, expiresAt: 1000, count: 1 },
      active: { kind: "rx" as const, expiresAt: 2000, count: 1 },
    };
    expect(pruneExpired(pulses, 1500)).toEqual({ active: { kind: "rx", expiresAt: 2000, count: 1 } });
  });

  it("a pulse expiring exactly now is removed", () => {
    const pulses = { p: { kind: "tx" as const, expiresAt: 1000, count: 1 } };
    expect(pruneExpired(pulses, 1000)).toEqual({});
  });
});

describe("pushEvent: burst throttling", () => {
  const event = (seq: number): PacketEvent => ({ seq, portId: "printer1.eth0", kind: "tx" });

  it("keeps every event while under the cap", () => {
    let events: PacketEvent[] = [];
    events = pushEvent(events, event(1), 5);
    events = pushEvent(events, event(2), 5);
    expect(events).toEqual([event(1), event(2)]);
  });

  it("drops the oldest events once the bounded capacity is exceeded, keeping the most recent", () => {
    let events: PacketEvent[] = [];
    for (let seq = 1; seq <= 10; seq++) events = pushEvent(events, event(seq), 5);
    expect(events.map((e) => e.seq)).toEqual([6, 7, 8, 9, 10]);
  });

  it("default constants are sane (positive, and events don't expire instantly)", () => {
    expect(PULSE_DURATION_MS).toBeGreaterThan(0);
    expect(MAX_TRACKED_EVENTS).toBeGreaterThan(0);
  });
});
