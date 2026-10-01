import type { CommandResult } from "./types";

export type CableEndpoints = { a: string; b: string };
type Execute = (op: string, params: Record<string, unknown>) => Promise<CommandResult>;
type MoveResult = { ok: true } | { ok: false; message: string };

const failureMessage = (result: Extract<CommandResult, { ok: false }>) =>
  `${result.error.code}: ${result.error.message}`;

/** Moves one end of an existing cable, restoring its old attachment if the new port rejects it. */
export async function moveCable(
  execute: Execute,
  cableId: string,
  previous: CableEndpoints,
  next: CableEndpoints,
): Promise<MoveResult> {
  const disconnected = await execute("disconnect", { cableId });
  if (!disconnected.ok) return { ok: false, message: failureMessage(disconnected) };

  const connected = await execute("connect", { cableId, ...next });
  if (connected.ok) return { ok: true };

  await execute("connect", { cableId, ...previous });
  return { ok: false, message: failureMessage(connected) };
}
