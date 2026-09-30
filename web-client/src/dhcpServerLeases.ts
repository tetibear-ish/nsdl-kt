import type { ObjectSnapshot } from "./types";

export type DhcpServerLeaseRow = {
  mac: string;
  address: string;
  state: string;
  /** Virtual time remaining until reclamation, clamped at zero; null for an offer with no lease yet. */
  remainingMs: number | null;
};

/** Interprets a dhcp-server ObjectSnapshot's raw "leases" list against the current virtual time. */
export function parseDhcpServerLeases(snapshot: ObjectSnapshot, nowMs: number): DhcpServerLeaseRow[] {
  const raw = snapshot.state.leases;
  if (!Array.isArray(raw)) return [];
  return raw.map((entry) => {
    const row = entry as Record<string, unknown>;
    const expiresAtMs = typeof row.expiresAtMs === "number" ? row.expiresAtMs : null;
    return {
      mac: typeof row.mac === "string" ? row.mac : "",
      address: typeof row.address === "string" ? row.address : "",
      state: typeof row.state === "string" ? row.state : "",
      remainingMs: expiresAtMs !== null ? Math.max(0, expiresAtMs - nowMs) : null,
    };
  });
}
