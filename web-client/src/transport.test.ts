import { afterEach, describe, expect, it, vi } from "vitest";
import { RemoteTransport } from "./transport";

describe("RemoteTransport", () => {
  afterEach(() => vi.restoreAllMocks());

  it("uses collision-resistant request ids and decodes a successful wire result", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async () =>
      new Response(JSON.stringify({ type: "result", revision: 4, changed: true, data: { id: "printer1" } })),
    );
    const transport = new RemoteTransport();

    const first = await transport.execute("create", { id: "printer1", type: "printer" });
    await transport.execute("listObjects");

    expect(first).toEqual({ ok: true, revision: 4, changed: true, data: { id: "printer1" } });
    const firstBody = JSON.parse(fetchMock.mock.calls[0][1]?.body as string);
    const secondBody = JSON.parse(fetchMock.mock.calls[1][1]?.body as string);
    expect(firstBody.id).toMatch(/^web-[0-9a-f-]{36}$/);
    expect(secondBody.id).not.toBe(firstBody.id);
  });
});
