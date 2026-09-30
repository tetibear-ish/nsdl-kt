export type ClockMode = "running" | "paused";
export type ClockSpeed = 1 | 2 | 10;
export const CLOCK_SPEEDS: readonly ClockSpeed[] = [1, 2, 10];

export type ClockState = { mode: ClockMode; speed: ClockSpeed; nowMs: number };

export const INITIAL_CLOCK_STATE: ClockState = { mode: "running", speed: 1, nowMs: 0 };

export function pause(state: ClockState): ClockState {
  return state.mode === "paused" ? state : { ...state, mode: "paused" };
}

export function resume(state: ClockState): ClockState {
  return state.mode === "running" ? state : { ...state, mode: "running" };
}

export function setSpeed(state: ClockState, speed: ClockSpeed): ClockState {
  return { ...state, speed };
}

/** Records the simulation's reported virtual time (e.g. from an advance result). Never moves backwards. */
export function observeNow(state: ClockState, nowMs: number): ClockState {
  return nowMs > state.nowMs ? { ...state, nowMs } : state;
}

/**
 * Virtual duration (ms) to request for elapsedRealMs of wall-clock time under the current
 * mode/speed. Zero while paused or for non-positive elapsed time, so a paused clock never
 * issues advance requests from its real-time ticker.
 */
export function tickDuration(state: ClockState, elapsedRealMs: number): number {
  if (state.mode === "paused" || elapsedRealMs <= 0) return 0;
  return elapsedRealMs * state.speed;
}
