import { describe, expect, it } from "vitest";
import { acquireLease, isOwner } from "./clockOwnership";

describe("acquireLease", () => {
  it("an unclaimed lease (null) is granted to the requester", () => {
    const lease = acquireLease(null, "tab-a", 1000, 5000);
    expect(lease).toEqual({ ownerId: "tab-a", expiresAt: 6000 });
  });

  it("the current owner can renew its own lease, extending expiry from now", () => {
    const lease = acquireLease({ ownerId: "tab-a", expiresAt: 2000 }, "tab-a", 1900, 5000);
    expect(lease).toEqual({ ownerId: "tab-a", expiresAt: 6900 });
  });

  it("a different client cannot take over a lease that has not expired", () => {
    const current = { ownerId: "tab-a", expiresAt: 5000 };
    expect(acquireLease(current, "tab-b", 1000, 5000)).toBe(current);
  });

  it("a different client can claim a lease once it has expired", () => {
    const current = { ownerId: "tab-a", expiresAt: 1000 };
    const lease = acquireLease(current, "tab-b", 1000, 5000);
    expect(lease).toEqual({ ownerId: "tab-b", expiresAt: 6000 });
  });

  it("a lease expiring exactly now is treated as expired", () => {
    const current = { ownerId: "tab-a", expiresAt: 1000 };
    const lease = acquireLease(current, "tab-b", 1000, 5000);
    expect(lease.ownerId).toBe("tab-b");
  });
});

describe("isOwner: only the current lease-holder may drive the clock", () => {
  it("is false when there is no lease", () => {
    expect(isOwner(null, "tab-a", 1000)).toBe(false);
  });

  it("is true for the current holder before expiry", () => {
    expect(isOwner({ ownerId: "tab-a", expiresAt: 2000 }, "tab-a", 1000)).toBe(true);
  });

  it("is false for a different client even before expiry (no competing timers)", () => {
    expect(isOwner({ ownerId: "tab-a", expiresAt: 2000 }, "tab-b", 1000)).toBe(false);
  });

  it("is false for the holder once its lease has expired", () => {
    expect(isOwner({ ownerId: "tab-a", expiresAt: 1000 }, "tab-a", 1000)).toBe(false);
  });
});
