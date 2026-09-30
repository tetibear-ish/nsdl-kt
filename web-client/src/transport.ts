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

declare global {
  interface Window { nsdlWasmReady?: Promise<WasmBridge> }
}

export class BrowserWasmTransport implements SimulationTransport {
  private readonly listeners = new Set<(event: SimulationEvent) => void>();
  private readonly ready: Promise<WasmBridge>;

  constructor(ready?: Promise<WasmBridge>) {
    this.ready = ready ?? window.nsdlWasmReady ?? new Promise(() => {});
  }

  async execute<T = unknown>(op: string, params: Record<string, unknown> = {}): Promise<CommandResult<T>> {
    const bridge = await this.ready;
    const result = JSON.parse(bridge.command(JSON.stringify({ v: 1, op, params }))) as CommandResult<T>;
    if (result.ok && result.changed) this.listeners.forEach((listener) => listener({ type: "event" }));
    return result;
  }

  listTypes() { return this.execute<ObjectTypeSchema[]>("listTypes"); }
  listObjects() { return this.execute<ObjectSnapshot[]>("listObjects"); }

  subscribe(_from: number, listener: (event: SimulationEvent) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }
}

export function selectTransport(search = window.location.search): SimulationTransport {
  return new URLSearchParams(search).get("runtime") === "server" ? new RemoteTransport() : new BrowserWasmTransport();
}
