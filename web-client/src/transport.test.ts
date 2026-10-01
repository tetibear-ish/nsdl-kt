import { afterEach, describe, expect, it, vi } from "vitest";
import { BrowserWasmTransport, RemoteTransport, selectTransport } from "./transport";

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  onmessage: ((event: { data: string }) => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;

  constructor(public url: string | URL) {
    FakeEventSource.instances.push(this);
  }

  close() {
    this.closed = true;
  }

  emit(data: unknown) {
    this.onmessage?.({ data: JSON.stringify(data) });
  }
}

/** Routes the fetch mock by URL so a single test can exercise /api/command, /api/move and
 * /api/session/snapshot without each needing its own spy. */
function routeFetch(handlers: {
  command?: (body: Record<string, unknown>) => unknown;
  move?: (body: Record<string, unknown>) => unknown;
  snapshot?: () => unknown;
}) {
  return vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = String(input);
    if (url.includes("/api/session/snapshot")) {
      return new Response(JSON.stringify(handlers.snapshot ? handlers.snapshot() : { revision: 0, positions: {} }));
    }
    if (url.includes("/api/move")) {
      const body = JSON.parse(init?.body as string);
      return new Response(JSON.stringify(handlers.move ? handlers.move(body) : { type: "applied", revision: 1 }));
    }
    const body = init?.body ? JSON.parse(init.body as string) : {};
    return new Response(JSON.stringify(handlers.command ? handlers.command(body) : { type: "result", revision: 0, changed: true, data: null }));
  });
}

afterEach(() => {
  FakeEventSource.instances = [];
});

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

  it("forwards typed frame events embedded by the Wasm runtime", async () => {
    const frameEvent = { type: "event" as const, seq: 8, source: "printer1.eth0", eventType: "FrameSent" };
    const command = vi.fn((_request: string) => JSON.stringify({ ok: true, revision: 8, changed: true, data: null, events: [frameEvent] }));
    const transport = new BrowserWasmTransport(Promise.resolve({ command }));
    const listener = vi.fn();
    const unsubscribe = transport.subscribe(7, listener);

    await transport.execute("advance", { durationMs: 250 });
    unsubscribe();

    expect(listener).toHaveBeenCalledWith(frameEvent);
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

describe("RemoteTransport collaborative positions", () => {
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it("loads the initial snapshot and notifies position listeners", async () => {
    vi.stubGlobal("EventSource", FakeEventSource);
    routeFetch({ snapshot: () => ({ revision: 3, positions: { n1: { x: 1, y: 2 } } }) });
    const transport = new RemoteTransport();
    const listener = vi.fn();

    transport.onPositionsChange(listener);

    await vi.waitFor(() => expect(transport.getPositions()).toEqual({ n1: { x: 1, y: 2 } }));
    expect(listener).toHaveBeenCalledWith({ n1: { x: 1, y: 2 } });
  });

  it("applies a patch pushed over the session stream to an already-loaded snapshot", async () => {
    vi.stubGlobal("EventSource", FakeEventSource);
    routeFetch({ snapshot: () => ({ revision: 1, positions: { n1: { x: 0, y: 0 } } }) });
    const transport = new RemoteTransport();
    transport.onPositionsChange(vi.fn());
    await vi.waitFor(() => expect(FakeEventSource.instances.some((s) => String(s.url).includes("/api/session/stream"))).toBe(true));
    const stream = FakeEventSource.instances.find((s) => String(s.url).includes("/api/session/stream"))!;

    stream.emit({ type: "patch", revision: 2, changed: { n2: { x: 5, y: 5 } }, removed: [] });

    expect(transport.getPositions()).toEqual({ n1: { x: 0, y: 0 }, n2: { x: 5, y: 5 } });
  });

  it("a removal patch clears the node from the position map", async () => {
    vi.stubGlobal("EventSource", FakeEventSource);
    routeFetch({ snapshot: () => ({ revision: 1, positions: { n1: { x: 0, y: 0 } } }) });
    const transport = new RemoteTransport();
    transport.onPositionsChange(vi.fn());
    await vi.waitFor(() => expect(FakeEventSource.instances.length).toBeGreaterThan(0));
    const stream = FakeEventSource.instances.find((s) => String(s.url).includes("/api/session/stream"))!;

    stream.emit({ type: "patch", revision: 2, changed: {}, removed: ["n1"] });

    expect(transport.getPositions()).toEqual({});
  });

  it("a gap on the session stream triggers a resync via a fresh snapshot fetch", async () => {
    vi.stubGlobal("EventSource", FakeEventSource);
    routeFetch({ snapshot: () => ({ revision: 7, positions: { n9: { x: 9, y: 9 } } }) });
    const transport = new RemoteTransport();
    transport.onPositionsChange(vi.fn());
    await vi.waitFor(() => expect(FakeEventSource.instances.length).toBe(1));

    FakeEventSource.instances[0].emit({ type: "gap" });

    await vi.waitFor(() => expect(FakeEventSource.instances.length).toBe(2));
    await vi.waitFor(() => expect(transport.getPositions()).toEqual({ n9: { x: 9, y: 9 } }));
  });

  it("movePosition applies the position optimistically before the server confirms it", async () => {
    vi.stubGlobal("EventSource", FakeEventSource);
    let resolveMove: (value: Response) => void = () => {};
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url.includes("/api/session/snapshot")) return new Response(JSON.stringify({ revision: 0, positions: {} }));
      if (url.includes("/api/move")) return new Promise((resolve) => { resolveMove = resolve; });
      return new Response(JSON.stringify({ type: "result", revision: 0, changed: true, data: null }));
    });
    const transport = new RemoteTransport();

    transport.movePosition("n1", { x: 10, y: 20 });

    expect(transport.getPositions()).toEqual({ n1: { x: 10, y: 20 } });
    resolveMove(new Response(JSON.stringify({ type: "applied", revision: 1 })));
  });

  it("reconciles to the server's authoritative position when a move is rejected as a conflict", async () => {
    vi.stubGlobal("EventSource", FakeEventSource);
    routeFetch({
      snapshot: () => ({ revision: 0, positions: {} }),
      move: () => ({ type: "conflict", revision: 5, current: { x: 99, y: 99 } }),
    });
    const transport = new RemoteTransport();

    transport.movePosition("n1", { x: 1, y: 1 });

    await vi.waitFor(() => expect(transport.getPositions()).toEqual({ n1: { x: 99, y: 99 } }));
  });

  it("sends a stable clientId and a fresh requestId with every move", async () => {
    vi.stubGlobal("EventSource", FakeEventSource);
    const fetchMock = routeFetch({});
    const transport = new RemoteTransport();

    transport.movePosition("n1", { x: 1, y: 1 });
    transport.movePosition("n1", { x: 2, y: 2 });

    await vi.waitFor(() => expect(fetchMock.mock.calls.filter((c) => String(c[0]).includes("/api/move"))).toHaveLength(2));
    const moveCalls = fetchMock.mock.calls.filter((c) => String(c[0]).includes("/api/move"));
    const first = JSON.parse(moveCalls[0][1]?.body as string);
    const second = JSON.parse(moveCalls[1][1]?.body as string);
    expect(first.clientId).toBe(second.clientId);
    expect(first.clientId).toBe(transport.clientId);
    expect(first.requestId).not.toBe(second.requestId);
  });

  it("removePosition clears a node locally without waiting on the network", () => {
    vi.stubGlobal("EventSource", FakeEventSource);
    routeFetch({ snapshot: () => ({ revision: 1, positions: { n1: { x: 0, y: 0 } } }) });
    const transport = new RemoteTransport();

    transport.movePosition("n1", { x: 0, y: 0 });
    transport.removePosition("n1");

    expect(transport.getPositions()).toEqual({});
  });
});

describe("RemoteTransport presence", () => {
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it("reports the roster pushed by the server and notifies listeners", async () => {
    vi.stubGlobal("EventSource", FakeEventSource);
    routeFetch({});
    const transport = new RemoteTransport();
    const listener = vi.fn();

    transport.onPresenceChange(listener);

    await vi.waitFor(() => expect(FakeEventSource.instances.some((s) => String(s.url).includes("/api/presence"))).toBe(true));
    const stream = FakeEventSource.instances.find((s) => String(s.url).includes("/api/presence"))!;

    stream.emit({ type: "roster", clients: ["a", "b"] });

    expect(transport.getPresence()).toEqual(["a", "b"]);
    expect(listener).toHaveBeenCalledWith(["a", "b"]);
  });

  it("includes clientId in the presence stream URL", async () => {
    vi.stubGlobal("EventSource", FakeEventSource);
    routeFetch({});
    const transport = new RemoteTransport();

    transport.onPresenceChange(vi.fn());

    await vi.waitFor(() => expect(FakeEventSource.instances.length).toBeGreaterThan(0));
    const stream = FakeEventSource.instances.find((s) => String(s.url).includes("/api/presence"))!;
    expect(String(stream.url)).toContain(encodeURIComponent(transport.clientId));
  });
});

describe("BrowserWasmTransport local positions and presence", () => {
  it("movePosition/getPositions work entirely locally and notify listeners synchronously", () => {
    const transport = new BrowserWasmTransport(Promise.resolve({ command: vi.fn() }));
    const listener = vi.fn();
    transport.onPositionsChange(listener);

    transport.movePosition("n1", { x: 1, y: 2 });

    expect(transport.getPositions()).toEqual({ n1: { x: 1, y: 2 } });
    expect(listener).toHaveBeenCalledWith({ n1: { x: 1, y: 2 } });
  });

  it("removePosition clears a node from the local position map", () => {
    const transport = new BrowserWasmTransport(Promise.resolve({ command: vi.fn() }));
    transport.movePosition("n1", { x: 1, y: 2 });

    transport.removePosition("n1");

    expect(transport.getPositions()).toEqual({});
  });

  it("reports only itself as present", () => {
    const transport = new BrowserWasmTransport(Promise.resolve({ command: vi.fn() }));
    expect(transport.getPresence()).toEqual([transport.clientId]);
  });
});
