import { describe, expect, it } from "vitest";
import { defaultObjectId } from "./AddObjectDialog";

describe("defaultObjectId", () => {
  it("uses friendly sequential names and skips existing ids", () => {
    expect(defaultObjectId("ethernet-switch", ["switch1", "switch2"])).toBe("switch3");
    expect(defaultObjectId("printer", ["printer1"])).toBe("printer2");
    expect(defaultObjectId("gateway", [])).toBe("gateway1");
  });
});
