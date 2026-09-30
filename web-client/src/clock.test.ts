import { describe, expect, it } from "vitest";
import { INITIAL_CLOCK_STATE, observeNow, pause, resume, setSpeed, tickDuration } from "./clock";

describe("pause and resume", () => {
  it("starts running by default", () => {
    expect(INITIAL_CLOCK_STATE.mode).toBe("running");
  });

  it("pause switches mode to paused", () => {
    expect(pause(INITIAL_CLOCK_STATE).mode).toBe("paused");
  });

  it("resume switches a paused clock back to running", () => {
    const paused = pause(INITIAL_CLOCK_STATE);
    expect(resume(paused).mode).toBe("running");
  });

  it("pausing an already-paused clock is a no-op (same reference)", () => {
    const paused = pause(INITIAL_CLOCK_STATE);
    expect(pause(paused)).toBe(paused);
  });

  it("resuming an already-running clock is a no-op (same reference)", () => {
    expect(resume(INITIAL_CLOCK_STATE)).toBe(INITIAL_CLOCK_STATE);
  });
});

describe("setSpeed", () => {
  it("changes the speed multiplier without affecting mode", () => {
    const next = setSpeed(INITIAL_CLOCK_STATE, 10);
    expect(next.speed).toBe(10);
    expect(next.mode).toBe("running");
  });
});

describe("observeNow", () => {
  it("records a later virtual time", () => {
    const next = observeNow(INITIAL_CLOCK_STATE, 5000);
    expect(next.nowMs).toBe(5000);
  });

  it("never moves the recorded time backwards", () => {
    const state = observeNow(INITIAL_CLOCK_STATE, 5000);
    expect(observeNow(state, 1000)).toBe(state);
  });
});

describe("tickDuration: elapsed-time accounting", () => {
  it("requests elapsedRealMs of virtual duration at 1x speed", () => {
    expect(tickDuration(INITIAL_CLOCK_STATE, 250)).toBe(250);
  });

  it("scales the requested duration by the speed multiplier", () => {
    const fast = setSpeed(INITIAL_CLOCK_STATE, 10);
    expect(tickDuration(fast, 250)).toBe(2500);
  });

  it("requests nothing while paused, regardless of elapsed time", () => {
    const paused = pause(setSpeed(INITIAL_CLOCK_STATE, 10));
    expect(tickDuration(paused, 250)).toBe(0);
  });

  it("requests nothing for zero or negative elapsed time", () => {
    expect(tickDuration(INITIAL_CLOCK_STATE, 0)).toBe(0);
    expect(tickDuration(INITIAL_CLOCK_STATE, -10)).toBe(0);
  });
});
