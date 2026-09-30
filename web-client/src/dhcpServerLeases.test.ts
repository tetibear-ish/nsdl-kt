import { describe, expect, it } from "vitest";
import { parseDhcpServerLeases } from "./dhcpServerLeases";
import type { ObjectSnapshot } from "./types";

function snapshot(leases: unknown[]): ObjectSnapshot {
  return { id: "gateway1.dhcp-server", type: "dhcp-server", kind: "PROTOCOL", state: { leases }, relations: {} };
}

describe("parseDhcpServerLeases", () => {
  it("is empty when there are no leases", () => {
    expect(parseDhcpServerLeases(snapshot([]), 0)).toEqual([]);
  });

  it("maps a bound lease's mac, address, state and remaining time", () => {
    const rows = parseDhcpServerLeases(
      snapshot([{ mac: "02:00:00:00:00:01", address: "10.0.0.100", state: "BOUND", expiresAtMs: 600_000 }]),
      100_000,
    );
    expect(rows).toEqual([{ mac: "02:00:00:00:00:01", address: "10.0.0.100", state: "BOUND", remainingMs: 500_000 }]);
  });

  it("an offered (not yet requested) binding has no remaining time", () => {
    const rows = parseDhcpServerLeases(
      snapshot([{ mac: "02:00:00:00:00:01", address: "10.0.0.100", state: "OFFERED", expiresAtMs: null }]),
      0,
    );
    expect(rows[0].remainingMs).toBeNull();
  });

  it("clamps remaining time at zero past the deadline", () => {
    const rows = parseDhcpServerLeases(
      snapshot([{ mac: "02:00:00:00:00:01", address: "10.0.0.100", state: "BOUND", expiresAtMs: 1000 }]),
      5000,
    );
    expect(rows[0].remainingMs).toBe(0);
  });

  it("maps multiple leases independently, preserving order", () => {
    const rows = parseDhcpServerLeases(
      snapshot([
        { mac: "aa", address: "10.0.0.100", state: "BOUND", expiresAtMs: 2000 },
        { mac: "bb", address: "10.0.0.101", state: "BOUND", expiresAtMs: 3000 },
      ]),
      1000,
    );
    expect(rows.map((row) => row.mac)).toEqual(["aa", "bb"]);
    expect(rows.map((row) => row.remainingMs)).toEqual([1000, 2000]);
  });
});
