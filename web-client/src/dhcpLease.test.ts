import { describe, expect, it } from "vitest";
import { dhcpStatusClass, parseDhcpLease } from "./dhcpLease";
import type { ObjectSnapshot } from "./types";

function snapshot(state: Record<string, unknown>): ObjectSnapshot {
  return { id: "printer1.dhcp-client", type: "dhcp-client", kind: "PROTOCOL", state, relations: {} };
}

describe("parseDhcpLease: missing configuration", () => {
  it("has no address or lease timing before any offer (INIT)", () => {
    const view = parseDhcpLease(snapshot({ state: "INIT", selectedServer: null, offeredAddress: null, subnetMask: null, router: null, leaseStartMs: null, leaseDurationMs: null }), 0);
    expect(view).toEqual({
      clientState: "INIT", address: null, subnetMask: null, router: null, server: null,
      leaseDurationMs: null, remainingMs: null, expired: false,
    });
  });

  it("has no lease timing while selecting or requesting", () => {
    const view = parseDhcpLease(snapshot({ state: "REQUESTING", selectedServer: "10.0.0.1", offeredAddress: null, subnetMask: null, router: null, leaseStartMs: null, leaseDurationMs: null }), 5000);
    expect(view.address).toBeNull();
    expect(view.remainingMs).toBeNull();
    expect(view.expired).toBe(false);
  });
});

describe("parseDhcpLease: an active lease", () => {
  const bound = snapshot({
    state: "BOUND", selectedServer: "10.0.0.1", offeredAddress: "10.0.0.100",
    subnetMask: "255.255.255.0", router: "10.0.0.1", leaseStartMs: 0, leaseDurationMs: 3_600_000,
  });

  it("exposes address, subnet, router and server", () => {
    const view = parseDhcpLease(bound, 0);
    expect(view.clientState).toBe("BOUND");
    expect(view.address).toBe("10.0.0.100");
    expect(view.subnetMask).toBe("255.255.255.0");
    expect(view.router).toBe("10.0.0.1");
    expect(view.server).toBe("10.0.0.1");
    expect(view.leaseDurationMs).toBe(3_600_000);
  });

  it("computes remaining time as the lease deadline minus the current virtual time", () => {
    expect(parseDhcpLease(bound, 0).remainingMs).toBe(3_600_000);
    expect(parseDhcpLease(bound, 1_000_000).remainingMs).toBe(2_600_000);
  });

  it("is not expired while time remains", () => {
    expect(parseDhcpLease(bound, 3_599_999).expired).toBe(false);
  });
});

describe("parseDhcpLease: renewal transitions", () => {
  it("keeps address and lease timing while RENEWING", () => {
    const renewing = snapshot({
      state: "RENEWING", selectedServer: "10.0.0.1", offeredAddress: "10.0.0.100",
      subnetMask: "255.255.255.0", router: "10.0.0.1", leaseStartMs: 0, leaseDurationMs: 3_600_000,
    });
    const view = parseDhcpLease(renewing, 1_800_000);
    expect(view.clientState).toBe("RENEWING");
    expect(view.address).toBe("10.0.0.100");
    expect(view.remainingMs).toBe(1_800_000);
  });

  it("keeps address and lease timing while REBINDING", () => {
    const rebinding = snapshot({
      state: "REBINDING", selectedServer: "10.0.0.1", offeredAddress: "10.0.0.100",
      subnetMask: "255.255.255.0", router: "10.0.0.1", leaseStartMs: 0, leaseDurationMs: 3_600_000,
    });
    const view = parseDhcpLease(rebinding, 3_150_000);
    expect(view.clientState).toBe("REBINDING");
    expect(view.remainingMs).toBe(450_000);
  });
});

describe("parseDhcpLease: expiry", () => {
  const bound = snapshot({
    state: "BOUND", selectedServer: "10.0.0.1", offeredAddress: "10.0.0.100",
    subnetMask: "255.255.255.0", router: "10.0.0.1", leaseStartMs: 0, leaseDurationMs: 3_600_000,
  });

  it("is expired once virtual time reaches the deadline", () => {
    const view = parseDhcpLease(bound, 3_600_000);
    expect(view.expired).toBe(true);
    expect(view.remainingMs).toBe(0);
  });

  it("clamps remaining time at zero rather than going negative past expiry", () => {
    expect(parseDhcpLease(bound, 4_000_000).remainingMs).toBe(0);
  });
});

describe("dhcpStatusClass: highlighting BOUND/RENEWING/REBINDING and expired/unconfigured", () => {
  const leased = (clientState: string, remainingMs: number | null, expired: boolean) => ({
    clientState, address: "10.0.0.100", subnetMask: null, router: null, server: null,
    leaseDurationMs: null, remainingMs, expired,
  }) as ReturnType<typeof parseDhcpLease>;

  it("BOUND", () => expect(dhcpStatusClass(leased("BOUND", 1000, false))).toBe("dhcp-bound"));
  it("RENEWING", () => expect(dhcpStatusClass(leased("RENEWING", 1000, false))).toBe("dhcp-renewing"));
  it("REBINDING", () => expect(dhcpStatusClass(leased("REBINDING", 1000, false))).toBe("dhcp-rebinding"));
  it("expired takes priority over the reported state", () => expect(dhcpStatusClass(leased("BOUND", 0, true))).toBe("dhcp-expired"));
  it("unconfigured when there is no address", () => {
    const view = { clientState: "INIT", address: null, subnetMask: null, router: null, server: null, leaseDurationMs: null, remainingMs: null, expired: false } as ReturnType<typeof parseDhcpLease>;
    expect(dhcpStatusClass(view)).toBe("dhcp-unconfigured");
  });
});
