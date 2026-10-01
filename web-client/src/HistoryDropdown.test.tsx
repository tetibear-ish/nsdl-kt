import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { HistoryDropdown } from "./HistoryDropdown";

describe("HistoryDropdown", () => {
  it("shows the latest commit first with its short hash", async () => {
    render(<HistoryDropdown commits={[
      { hash: "8b5ab85", subject: "fix(web): keep cable color stable on click" },
      { hash: "d003fbc", subject: "feat(web): stabilize gateway lease table" },
    ]} />);

    fireEvent.pointerDown(screen.getByRole("button", { name: "History" }), { button: 0, ctrlKey: false });

    expect(await screen.findByText("fix(web): keep cable color stable on click")).toBeInTheDocument();
    expect(screen.getByText("8b5ab85")).toBeInTheDocument();
    const entries = screen.getAllByRole("menuitem");
    expect(entries[0]).toHaveTextContent("fix(web): keep cable color stable on click");
  });
});
