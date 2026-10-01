import { describe, expect, it, vi } from "vitest";
import { moveCable } from "./cableMove";

const success = { ok: true as const, revision: 1, changed: true, data: {} };

describe("moveCable", () => {
  it("disconnects and reconnects the same cable to the dragged endpoints", async () => {
    const execute = vi.fn().mockResolvedValue(success);

    const result = await moveCable(execute, "cable1", {
      a: "printer1.eth0", b: "switch1.port1",
    }, {
      a: "printer1.eth0", b: "switch1.port2",
    });

    expect(result.ok).toBe(true);
    expect(execute).toHaveBeenNthCalledWith(1, "disconnect", { cableId: "cable1" });
    expect(execute).toHaveBeenNthCalledWith(2, "connect", {
      cableId: "cable1", a: "printer1.eth0", b: "switch1.port2",
    });
  });

  it("restores the old endpoints when the new connection is rejected", async () => {
    const failure = { ok: false as const, error: { code: "CONFLICT", message: "port occupied" } };
    const execute = vi.fn().mockResolvedValueOnce(success).mockResolvedValueOnce(failure).mockResolvedValueOnce(success);

    const result = await moveCable(execute, "cable1", {
      a: "printer1.eth0", b: "switch1.port1",
    }, {
      a: "printer1.eth0", b: "switch1.port2",
    });

    expect(result).toEqual({ ok: false, message: "CONFLICT: port occupied" });
    expect(execute).toHaveBeenNthCalledWith(3, "connect", {
      cableId: "cable1", a: "printer1.eth0", b: "switch1.port1",
    });
  });
});
