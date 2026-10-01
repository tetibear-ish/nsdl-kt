import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ConfigureObjectForm, configurationPayload, initialConfiguration } from "./ConfigureObjectForm";
import type { ObjectTypeSchema } from "./types";

const schema: ObjectTypeSchema = {
  name: "printer",
  kind: "DEVICE",
  interfaces: [{ name: "eth0", media: "TWISTED_PAIR" }],
  properties: [
    { name: "bootMs", type: "LONG", required: false, default: 3000, description: "Boot time" },
    { name: "mac", type: "MAC", required: false, description: "Optional fixed address" },
  ],
};

describe("device configuration", () => {
  it("prefills saved values ahead of schema defaults", () => {
    expect(initialConfiguration(schema, { bootMs: "1500" })).toEqual({ bootMs: "1500", mac: "" });
  });

  it("omits an empty optional value from the configure payload", () => {
    expect(configurationPayload(schema, { bootMs: "2500", mac: "" })).toEqual({ bootMs: "2500" });
  });

  it("renders every schema property and reports configure failures", async () => {
    const configure = vi.fn().mockResolvedValue("INVALID_PROPERTY: invalid MAC");
    render(<ConfigureObjectForm id="printer1" schema={schema} currentProps={{ bootMs: 1200 }} onConfigure={configure} />);

    expect(screen.getByLabelText("bootMs")).toHaveValue("1200");
    expect(screen.getByLabelText("mac")).toHaveValue("");
    fireEvent.change(screen.getByLabelText("mac"), { target: { value: "bad" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply configuration" }));

    await waitFor(() => expect(configure).toHaveBeenCalledWith({ bootMs: "1200", mac: "bad" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("INVALID_PROPERTY: invalid MAC");
  });
});
