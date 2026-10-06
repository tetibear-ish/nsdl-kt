export type ObjectKind = "DEVICE" | "INTERFACE" | "CABLE" | "PROTOCOL";

export type ObjectSnapshot = {
  id: string;
  type: string;
  kind: ObjectKind;
  state: Record<string, unknown>;
  relations: Record<string, string[]>;
};

export type PropertySpec = {
  name: string;
  type: "STRING" | "LONG" | "IPV4" | "MAC" | "LINK_PROFILE" | "BOOLEAN" | "PORT_FORWARDS";
  required: boolean;
  default?: unknown;
  mutable?: boolean;
  description?: string;
};

export type ObjectTypeSchema = {
  name: string;
  kind: ObjectKind;
  properties: PropertySpec[];
  interfaces: Array<{ name: string; media: string }>;
};

export type CommandSuccess<T = unknown> = {
  ok: true;
  revision: number;
  changed: boolean;
  data: T;
};

export type CommandFailure = {
  ok: false;
  error: { code: string; message: string; details?: Record<string, unknown> };
};

export type CommandResult<T = unknown> = CommandSuccess<T> | CommandFailure;

/** A node's shared canvas position -- UI-only session state synchronized across clients (see transport.ts). */
export type Position = { x: number; y: number };

export type SimulationEvent = {
  type: "event" | "gap";
  seq?: number;
  source?: string;
  eventType?: string;
  data?: Record<string, unknown>;
  resync?: boolean;
};
