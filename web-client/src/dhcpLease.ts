import type { ObjectSnapshot } from "./types";

export type DhcpLeaseView = {
  clientState: string;
  address: string | null;
  subnetMask: string | null;
  router: string | null;
  server: string | null;
  leaseDurationMs: number | null;
  /** Virtual time remaining until expiry, clamped at zero; null if there is no active lease. */
  remainingMs: number | null;
  expired: boolean;
};

function asString(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

function asNumber(value: unknown): number | null {
  return typeof value === "number" ? value : null;
}

/** Interprets a raw dhcp-client ObjectSnapshot against the current virtual time. */
export function parseDhcpLease(snapshot: ObjectSnapshot, nowMs: number): DhcpLeaseView {
  const state = snapshot.state;
  const leaseStartMs = asNumber(state.leaseStartMs);
  const leaseDurationMs = asNumber(state.leaseDurationMs);
  const expiresAtMs = leaseStartMs !== null && leaseDurationMs !== null ? leaseStartMs + leaseDurationMs : null;
  return {
    clientState: asString(state.state) ?? "STOPPED",
    address: asString(state.offeredAddress),
    subnetMask: asString(state.subnetMask),
    router: asString(state.router),
    server: asString(state.selectedServer),
    leaseDurationMs,
    remainingMs: expiresAtMs !== null ? Math.max(0, expiresAtMs - nowMs) : null,
    expired: expiresAtMs !== null && nowMs >= expiresAtMs,
  };
}

/** A CSS-class-friendly highlight for the lease's current status. */
export function dhcpStatusClass(view: DhcpLeaseView): string {
  if (view.expired) return "dhcp-expired";
  if (!view.address) return "dhcp-unconfigured";
  switch (view.clientState) {
    case "BOUND": return "dhcp-bound";
    case "RENEWING": return "dhcp-renewing";
    case "REBINDING": return "dhcp-rebinding";
    default: return "dhcp-unconfigured";
  }
}
