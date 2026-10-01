import { type ClockSpeed, type ClockState, INITIAL_CLOCK_STATE, observeNow, pause, resume, setSpeed, tickDuration } from "./clock";
import { acquireLease, type ClockLease, isOwner } from "./clockOwnership";
import type { CommandResult, ObjectSnapshot, ObjectTypeSchema, SimulationEvent } from "./types";

export interface SimulationTransport {
  execute<T = unknown>(op: string, params?: Record<string, unknown>): Promise<CommandResult<T>>;
  listTypes(): Promise<CommandResult<ObjectTypeSchema[]>>;
  listObjects(): Promise<CommandResult<ObjectSnapshot[]>>;
  subscribe(from: number, listener: (event: SimulationEvent) => void): () => void;
  /** Whether this client's own timer drives virtual time ("browser"), or a shared server session does ("server"). */
  readonly driveMode: "browser" | "server";
  getClock(): ClockState;
  onClockChange(listener: (state: ClockState) => void): () => void;
  pauseClock(): void;
  resumeClock(): void;
  setClockSpeed(speed: ClockSpeed): void;
  /** Advances virtual time by a fixed amount immediately, regardless of running/paused mode. */
  step(amountMs: number): Promise<void>;
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

function nowMsFrom(data: unknown): number | null {
  const value = (data as { nowMs?: unknown } | null)?.nowMs;
  return typeof value === "number" ? value : null;
}

/** Shared clock-state bookkeeping used by both transports: mode/speed changes and tick-driven advances. */
class ClockController {
  private state: ClockState = INITIAL_CLOCK_STATE;
  private readonly listeners = new Set<(state: ClockState) => void>();

  get(): ClockState { return this.state; }

  onChange(listener: (state: ClockState) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private set(next: ClockState) {
    if (next === this.state) return;
    this.state = next;
    this.listeners.forEach((listener) => listener(this.state));
  }

  pause() { this.set(pause(this.state)); }
  resume() { this.set(resume(this.state)); }
  setSpeed(speed: ClockSpeed) { this.set(setSpeed(this.state, speed)); }
  observeResult(data: unknown) {
    const nowMs = nowMsFrom(data);
    if (nowMs !== null) this.set(observeNow(this.state, nowMs));
  }
  tickDuration(elapsedRealMs: number): number { return tickDuration(this.state, elapsedRealMs); }
}

export class RemoteTransport implements SimulationTransport {
  readonly driveMode = "server" as const;
  private readonly baseUrl: string;
  private readonly tickMs: number;
  private readonly leaseDurationMs: number;
  private readonly storage: Pick<Storage, "getItem" | "setItem">;
  private readonly ownerId: string;
  private readonly clock = new ClockController();
  private subscriberCount = 0;
  private timer: ReturnType<typeof setInterval> | undefined;
  private lastTick = Date.now();

  constructor(baseUrl = "", options: {
    tickMs?: number;
    leaseDurationMs?: number;
    storage?: Pick<Storage, "getItem" | "setItem">;
    ownerId?: string;
  } = {}) {
    this.baseUrl = baseUrl;
    this.tickMs = options.tickMs ?? 250;
    this.leaseDurationMs = options.leaseDurationMs ?? this.tickMs * 6;
    this.storage = options.storage ?? window.localStorage;
    this.ownerId = options.ownerId ?? crypto.randomUUID();
  }

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
    if (wire.type === "result") {
      if (op === "advance") this.clock.observeResult(wire.data);
      return { ok: true, revision: wire.revision, changed: wire.changed, data: wire.data };
    }
    return { ok: false, error: wire.error };
  }

  listTypes() {
    return this.execute<ObjectTypeSchema[]>("listTypes");
  }

  listObjects() {
    return this.execute<ObjectSnapshot[]>("listObjects");
  }

  getClock() { return this.clock.get(); }
  onClockChange(listener: (state: ClockState) => void) { return this.clock.onChange(listener); }
  pauseClock() { this.clock.pause(); }
  resumeClock() { this.clock.resume(); }
  setClockSpeed(speed: ClockSpeed) { this.clock.setSpeed(speed); }
  async step(amountMs: number) { await this.execute("advance", { durationMs: amountMs }); }

  private readLease(): ClockLease | null {
    const raw = this.storage.getItem(LEASE_KEY);
    return raw ? (JSON.parse(raw) as ClockLease) : null;
  }

  private tick() {
    const now = Date.now();
    const lease = acquireLease(this.readLease(), this.ownerId, now, this.leaseDurationMs);
    this.storage.setItem(LEASE_KEY, JSON.stringify(lease));
    const elapsedRealMs = now - this.lastTick;
    this.lastTick = now;
    if (!isOwner(lease, this.ownerId, now)) return;
    const durationMs = this.clock.tickDuration(elapsedRealMs);
    if (durationMs > 0) void this.execute("advance", { durationMs });
  }

  subscribe(from: number, listener: (event: SimulationEvent) => void): () => void {
    const source = new EventSource(`${this.baseUrl}/api/events?from=${from}`);
    source.onmessage = (message) => listener(JSON.parse(message.data) as SimulationEvent);
    source.onerror = () => listener({ type: "gap", resync: true });

    this.subscriberCount++;
    if (!this.timer) {
      this.lastTick = Date.now();
      this.timer = setInterval(() => this.tick(), this.tickMs);
    }

    return () => {
      source.close();
      this.subscriberCount--;
      if (this.subscriberCount <= 0 && this.timer) {
        clearInterval(this.timer);
        this.timer = undefined;
      }
    };
  }
}

const LEASE_KEY = "nsdl-clock-lease";

export type WasmBridge = { command(request: string): string };
type LocalWireResult<T> = CommandResult<T> & { events?: SimulationEvent[] };
const MUTATING_OPERATIONS = new Set([
  "create", "applyTopology", "connect", "disconnect", "configure", "powerOn", "powerOff", "advance",
]);

declare global {
  interface Window { nsdlWasmReady?: Promise<WasmBridge> }
}

export class BrowserWasmTransport implements SimulationTransport {
  readonly driveMode = "browser" as const;
  private readonly listeners = new Set<(event: SimulationEvent) => void>();
  private readonly ready: Promise<WasmBridge>;
  private readonly clock = new ClockController();
  private timer: ReturnType<typeof setInterval> | undefined;
  private lastTick = Date.now();
  private lastRevision = 0;

  constructor(ready?: Promise<WasmBridge>, private readonly tickMs = 250) {
    this.ready = ready ?? window.nsdlWasmReady ?? new Promise(() => {});
  }

  async execute<T = unknown>(op: string, params: Record<string, unknown> = {}): Promise<CommandResult<T>> {
    const bridge = await this.ready;
    const result = JSON.parse(bridge.command(JSON.stringify({ v: 1, op, params }))) as LocalWireResult<T>;
    const previousRevision = this.lastRevision;
    if (result.ok) this.lastRevision = Math.max(this.lastRevision, result.revision);
    if (result.ok && op === "advance") this.clock.observeResult(result.data);
    const emittedEvents = result.ok && result.revision > previousRevision;
    if (result.ok && result.events?.length) {
      result.events.forEach((event) => this.listeners.forEach((listener) => listener(event)));
    } else if (result.ok && result.changed && MUTATING_OPERATIONS.has(op) && (op !== "advance" || emittedEvents)) {
      this.listeners.forEach((listener) => listener({ type: "event" }));
    }
    return result;
  }

  listTypes() { return this.execute<ObjectTypeSchema[]>("listTypes"); }
  listObjects() { return this.execute<ObjectSnapshot[]>("listObjects"); }

  getClock() { return this.clock.get(); }
  onClockChange(listener: (state: ClockState) => void) { return this.clock.onChange(listener); }
  pauseClock() { this.clock.pause(); }
  resumeClock() { this.clock.resume(); }
  setClockSpeed(speed: ClockSpeed) { this.clock.setSpeed(speed); }
  async step(amountMs: number) { await this.execute("advance", { durationMs: amountMs }); }

  subscribe(_from: number, listener: (event: SimulationEvent) => void): () => void {
    this.lastRevision = Math.max(this.lastRevision, _from);
    this.listeners.add(listener);
    if (!this.timer) {
      this.lastTick = Date.now();
      this.timer = setInterval(() => {
        const now = Date.now();
        const elapsedRealMs = now - this.lastTick;
        this.lastTick = now;
        const durationMs = this.clock.tickDuration(elapsedRealMs);
        if (durationMs > 0) void this.execute("advance", { durationMs });
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
