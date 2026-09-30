import type { CommandResult, ObjectSnapshot, ObjectTypeSchema, SimulationEvent } from "./types";

export interface SimulationTransport {
  execute<T = unknown>(op: string, params?: Record<string, unknown>): Promise<CommandResult<T>>;
  listTypes(): Promise<CommandResult<ObjectTypeSchema[]>>;
  listObjects(): Promise<CommandResult<ObjectSnapshot[]>>;
  subscribe(from: number, listener: (event: SimulationEvent) => void): () => void;
}

type WireResult<T> = {
  type: "result";
  revision: number;
  changed: boolean;
  data: T;
};

type WireError = {
  type: "error";
  error: { code: string; message: string; details?: Record<string, unknown> };
};

export class RemoteTransport implements SimulationTransport {
  constructor(private readonly baseUrl = "") {}

  async execute<T = unknown>(op: string, params: Record<string, unknown> = {}): Promise<CommandResult<T>> {
    const response = await fetch(`${this.baseUrl}/api/command`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        v: 1,
        id: `web-${crypto.randomUUID()}`,
        op,
        params,
      }),
    });
    if (!response.ok) throw new Error(`command transport failed with HTTP ${response.status}`);
    const wire = (await response.json()) as WireResult<T> | WireError;
    return wire.type === "result"
      ? { ok: true, revision: wire.revision, changed: wire.changed, data: wire.data }
      : { ok: false, error: wire.error };
  }

  listTypes() {
    return this.execute<ObjectTypeSchema[]>("listTypes");
  }

  listObjects() {
    return this.execute<ObjectSnapshot[]>("listObjects");
  }

  subscribe(from: number, listener: (event: SimulationEvent) => void): () => void {
    const source = new EventSource(`${this.baseUrl}/api/events?from=${from}`);
    source.onmessage = (message) => listener(JSON.parse(message.data) as SimulationEvent);
    source.onerror = () => listener({ type: "gap", resync: true });
    return () => source.close();
  }
}

export type WasmBridge = { command(request: string): string };
const MUTATING_OPERATIONS = new Set([
  "create", "applyTopology", "connect", "disconnect", "configure", "powerOn", "powerOff", "advance",
]);

declare global {
  interface Window { nsdlWasmReady?: Promise<WasmBridge> }
}

export class BrowserWasmTransport implements SimulationTransport {
  private readonly listeners = new Set<(event: SimulationEvent) => void>();
  private readonly ready: Promise<WasmBridge>;
  private timer: ReturnType<typeof setInterval> | undefined;
  private lastTick = Date.now();
  private lastRevision = 0;

  constructor(ready?: Promise<WasmBridge>, private readonly tickMs = 250) {
    this.ready = ready ?? window.nsdlWasmReady ?? new Promise(() => {});
  }

  async execute<T = unknown>(op: string, params: Record<string, unknown> = {}): Promise<CommandResult<T>> {
    const bridge = await this.ready;
    const result = JSON.parse(bridge.command(JSON.stringify({ v: 1, op, params }))) as CommandResult<T>;
    const previousRevision = this.lastRevision;
    if (result.ok) this.lastRevision = Math.max(this.lastRevision, result.revision);
    const emittedEvents = result.ok && result.revision > previousRevision;
    if (result.ok && result.changed && MUTATING_OPERATIONS.has(op) && (op !== "advance" || emittedEvents)) {
      this.listeners.forEach((listener) => listener({ type: "event" }));
    }
    return result;
  }

  listTypes() { return this.execute<ObjectTypeSchema[]>("listTypes"); }
  listObjects() { return this.execute<ObjectSnapshot[]>("listObjects"); }

  subscribe(_from: number, listener: (event: SimulationEvent) => void): () => void {
    this.lastRevision = Math.max(this.lastRevision, _from);
    this.listeners.add(listener);
    if (!this.timer) {
      this.lastTick = Date.now();
      this.timer = setInterval(() => {
        const now = Date.now();
        const durationMs = Math.max(1, now - this.lastTick);
        this.lastTick = now;
        void this.execute("advance", { durationMs });
      }, this.tickMs);
    }
    return () => {
      this.listeners.delete(listener);
      if (this.listeners.size === 0 && this.timer) {
        clearInterval(this.timer);
        this.timer = undefined;
      }
    };
  }
}

export function selectTransport(search = window.location.search): SimulationTransport {
  return new URLSearchParams(search).get("runtime") === "server" ? new RemoteTransport() : new BrowserWasmTransport();
}
