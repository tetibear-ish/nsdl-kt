import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SoftwareDesktop } from "./SoftwareDesktop";

const candidate = { id: "printer1", name: "Office printer", address: "10.0.0.20", mac: "02:00:00:00:00:20" };

describe("workstation software desktop", () => {
  it("discovers a network printer before adding it to the software inventory", () => {
    const onAddPrinter = vi.fn();
    render(<SoftwareDesktop address="10.0.0.10" printers={[]} candidates={[candidate]} onAddPrinter={onAddPrinter} onRenamePrinter={vi.fn()} onDeletePrinter={vi.fn()} onTestPage={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: /Print/ }));
    fireEvent.click(screen.getByRole("button", { name: /Add Printer/ }));
    expect(screen.getByText("Printers discovered on this network")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Add" }));
    expect(onAddPrinter).toHaveBeenCalledWith(candidate);
  });
});
