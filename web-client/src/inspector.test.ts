import { describe, expect, it } from "vitest";
import { inspectionIds } from "./inspector";
import type { ObjectSnapshot } from "./types";

describe("inspectionIds", () => {
  it("refreshes a device together with its interfaces and services", () => {
    const snapshot: ObjectSnapshot = {
      id: "printer1",
      type: "printer",
      kind: "DEVICE",
      state: { power: "ON" },
      relations: { interfaces: ["printer1.eth0"], services: ["printer1.dhcp"] },
    };
    expect(inspectionIds(snapshot)).toEqual(["printer1", "printer1.eth0", "printer1.dhcp"]);
  });
});
