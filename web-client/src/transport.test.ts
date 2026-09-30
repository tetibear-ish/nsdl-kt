import { afterEach, describe, expect, it, vi } from "vitest";
import { BrowserWasmTransport, RemoteTransport, selectTransport } from "./transport";

class FakeEventSource {
  onmessage: ((event: { data: string }) => void) | null = null;
  onerror: (() => void) | null = null;
  close() {}
}

function memoryStorage(): Storage {
  const data = new Map<string, string>();
  return {
    getItem: (key) => data.get(key) ?? null,
    setItem: (key, value) => void data.set(key, value),
    removeItem: (key) => void data.delete(key),
    clear: () => data.clear(),
    key: () => null,
    length: 0,
  } as Storage;
}

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

  it("advances virtual time while a browser subscription is active", async () => {
    vi.useFakeTimers();
    const command = vi.fn((_request: string) => JSON.stringify({ ok: true, revision: 0, changed: true, data: null }));
    const transport = new BrowserWasmTransport(Promise.resolve({ command }), 250);

    const unsubscribe = transport.subscribe(0, vi.fn());
    await vi.advanceTimersByTimeAsync(250);
    unsubscribe();

    expect(JSON.parse(command.mock.calls[0][0])).toMatchObject({ op: "advance", params: { durationMs: 250 } });
    vi.useRealTimers();
  });

  it("does not redraw subscribers when a clock tick emits no events", async () => {
    const command = vi.fn((_request: string) => JSON.stringify({ ok: true, revision: 7, changed: true, data: null }));
    const transport = new BrowserWasmTransport(Promise.resolve({ command }));
    const listener = vi.fn();
    transport.subscribe(7, listener);

    await transport.execute("advance", { durationMs: 250 });

    expect(listener).not.toHaveBeenCalled();
  });

  it("reports itself as browser-driven", () => {
    const transport = new BrowserWasmTransport(Promise.resolve({ command: vi.fn() }));
    expect(transport.driveMode).toBe("browser");
  });

  describe("clock controls", () => {
    it("pauseClock stops the ticker from requesting further advances", async () => {
      vi.useFakeTimers();
      const command = vi.fn((_request: string) => JSON.stringify({ ok: true, revision: 0, changed: true, data: { nowMs: 0 } }));
      const transport = new BrowserWasmTransport(Promise.resolve({ command }), 250);
      transport.subscribe(0, vi.fn());
      await vi.advanceTimersByTimeAsync(250);
      command.mockClear();

      transport.pauseClock();
      await vi.advanceTimersByTimeAsync(1000);

      expect(command).not.toHaveBeenCalled();
      vi.useRealTimers();
    });

    it("resumeClock lets the ticker request advances again", async () => {
      vi.useFakeTimers();
      const command = vi.fn((_request: string) => JSON.stringify({ ok: true, revision: 0, changed: true, data: { nowMs: 0 } }));
      const transport = new BrowserWasmTransport(Promise.resolve({ command }), 250);
      transport.subscribe(0, vi.fn());
      transport.pauseClock();
      await vi.advanceTimersByTimeAsync(500);
      command.mockClear();

      transport.resumeClock();
      await vi.advanceTimersByTimeAsync(250);

      expect(JSON.parse(command.mock.calls[0][0])).toMatchObject({ op: "advance" });
      vi.useRealTimers();
    });

    it("setClockSpeed scales the virtual duration requested per real-time tick", async () => {
      vi.useFakeTimers();
      const command = vi.fn((_request: string) => JSON.stringify({ ok: true, revision: 0, changed: true, data: { nowMs: 0 } }));
      const transport = new BrowserWasmTransport(Promise.resolve({ command }), 250);
      transport.setClockSpeed(10);
      transport.subscribe(0, vi.fn());

      await vi.advanceTimersByTimeAsync(250);

      expect(JSON.parse(command.mock.calls[0][0])).toMatchObject({ op: "advance", params: { durationMs: 2500 } });
      vi.useRealTimers();
    });

    it("step() advances by a fixed amount even while paused, and updates getClock().nowMs", async () => {
      const command = vi.fn((_request: string) => JSON.stringify({ ok: true, revision: 1, changed: true, data: { nowMs: 100 } }));
      const transport = new BrowserWasmTransport(Promise.resolve({ command }));
      transport.pauseClock();

      await transport.step(100);

      expect(JSON.parse(command.mock.calls[0][0])).toMatchObject({ op: "advance", params: { durationMs: 100 } });
      expect(transport.getClock().nowMs).toBe(100);
    });

    it("onClockChange notifies listeners when mode, speed or virtual time change", async () => {
      const command = vi.fn((_request: string) => JSON.stringify({ ok: true, revision: 1, changed: true, data: { nowMs: 50 } }));
      const transport = new BrowserWasmTransport(Promise.resolve({ command }));
      const listener = vi.fn();
      transport.onClockChange(listener);

      transport.pauseClock();
      transport.resumeClock();
      transport.setClockSpeed(100);
      await transport.step(50);

      expect(listener).toHaveBeenCalledTimes(4);
      expect(listener.mock.calls.at(-1)?.[0]).toMatchObject({ nowMs: 50 });
    });
  });
});

describe("RemoteTransport clock ownership", () => {
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it("reports itself as server-driven", () => {
    expect(new RemoteTransport().driveMode).toBe("server");
  });

  it("the lease owner ticks the shared clock; a non-owner sharing the same storage does not", async () => {
    vi.stubGlobal("EventSource", FakeEventSource);
    vi.useFakeTimers();
    const storage = memoryStorage();
    vi.spyOn(globalThis, "fetch").mockImplementation(async () =>
      new Response(JSON.stringify({ type: "result", revision: 1, changed: true, data: { nowMs: 250 } })),
    );
    const owner = new RemoteTransport("", { tickMs: 250, leaseDurationMs: 2000, storage, ownerId: "tab-a" });
    const other = new RemoteTransport("", { tickMs: 250, leaseDurationMs: 2000, storage, ownerId: "tab-b" });

    owner.subscribe(0, vi.fn());
    other.subscribe(0, vi.fn());
    await vi.advanceTimersByTimeAsync(250);

    const fetchMock = globalThis.fetch as unknown as ReturnType<typeof vi.fn>;
    const advanceCalls = fetchMock.mock.calls.filter((call) => JSON.parse(call[1]?.body as string).op === "advance");
    expect(advanceCalls).toHaveLength(1);
  });
});
