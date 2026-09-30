import { afterEach, describe, expect, it, vi } from "vitest";
import { BrowserWasmTransport, RemoteTransport, selectTransport } from "./transport";

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

describe("BrowserWasmTransport", () => {
  it("passes versioned commands to the browser-local bridge and decodes results", async () => {
    const command = vi.fn((_request: string) => JSON.stringify({ ok: true, revision: 2, changed: false, data: [] }));
    const transport = new BrowserWasmTransport(Promise.resolve({ command }));

    await expect(transport.listObjects()).resolves.toEqual({ ok: true, revision: 2, changed: false, data: [] });
    expect(JSON.parse(command.mock.calls[0][0])).toMatchObject({ v: 1, op: "listObjects", params: {} });
  });

  it("selects local Wasm for static hosting and the server for explicit remote mode", () => {
    expect(selectTransport("?runtime=wasm")).toBeInstanceOf(BrowserWasmTransport);
    expect(selectTransport("?runtime=server")).toBeInstanceOf(RemoteTransport);
  });

  it("does not publish a local change notification for read commands", async () => {
    const command = vi.fn((_request: string) => JSON.stringify({ ok: true, revision: 2, changed: true, data: [] }));
    const transport = new BrowserWasmTransport(Promise.resolve({ command }));
    const listener = vi.fn();
    transport.subscribe(0, listener);

    await transport.listObjects();

    expect(listener).not.toHaveBeenCalled();
  });
});
