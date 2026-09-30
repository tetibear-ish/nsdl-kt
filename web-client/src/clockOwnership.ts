/** A time-bounded lease on driving the shared simulation clock. */
export type ClockLease = { ownerId: string; expiresAt: number };

/**
 * Grants or renews the lease for [ownerId] at [now], unless a different client currently
 * holds an unexpired lease -- then that lease is returned unchanged. This is how several
 * browser tabs against the same server agree on exactly one clock driver: each tab calls
 * this on its own heartbeat, and only the winner's [isOwner] check passes.
 */
export function acquireLease(current: ClockLease | null, ownerId: string, now: number, leaseDurationMs: number): ClockLease {
  if (current && current.ownerId !== ownerId && current.expiresAt > now) return current;
  return { ownerId, expiresAt: now + leaseDurationMs };
}

/** True only for the current, unexpired lease-holder -- the sole client allowed to tick the clock. */
export function isOwner(lease: ClockLease | null, ownerId: string, now: number): boolean {
  return lease !== null && lease.ownerId === ownerId && lease.expiresAt > now;
}
