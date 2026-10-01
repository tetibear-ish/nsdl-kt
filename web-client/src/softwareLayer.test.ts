import { describe, expect, it } from "vitest";
import { addSoftwarePrinter, removeSoftwarePrinter, renameSoftwarePrinter, type SoftwarePrinter } from "./softwareLayer";

const printer: SoftwarePrinter = { id: "printer1", name: "printer1", address: "10.0.0.20", mac: "02:00:00:00:00:20" };

describe("software printer inventory", () => {
  it("adds each discovered printer once", () => {
    expect(addSoftwarePrinter(addSoftwarePrinter([], printer), printer)).toEqual([printer]);
  });

  it("renames and removes software entries without changing network identity", () => {
    const renamed = renameSoftwarePrinter([printer], printer.id, "Office printer");
    expect(renamed[0]).toEqual({ ...printer, name: "Office printer" });
    expect(removeSoftwarePrinter(renamed, printer.id)).toEqual([]);
  });
});
