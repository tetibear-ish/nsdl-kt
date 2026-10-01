import { describe, expect, it } from "vitest";
import {
  classifyPort,
  describeConnectionRejection,
  guidePortStates,
  type GuidePort,
} from "./connectionGuide";

const port = (id: string, media: string, occupied = false): GuidePort => ({ id, media, occupied });

describe("classifyPort", () => {
  it("classifies the dragged port itself as the source", () => {
    const eth0 = port("printer1.eth0", "TWISTED_PAIR");
    expect(classifyPort(eth0, eth0)).toBe("source");
  });

  it("classifies a free port with matching media as compatible", () => {
    const source = port("printer1.eth0", "TWISTED_PAIR");
    const candidate = port("switch1.port1", "TWISTED_PAIR");
    expect(classifyPort(source, candidate)).toBe("compatible");
  });

  it("classifies a free port with different media as incompatible", () => {
    const source = port("printer1.eth0", "TWISTED_PAIR");
    const candidate = port("uplink1.sfp0", "FIBER");
    expect(classifyPort(source, candidate)).toBe("incompatible");
  });

  it("classifies an already-connected port as occupied, even when the media matches", () => {
    const source = port("printer1.eth0", "TWISTED_PAIR");
    const candidate = port("switch1.port1", "TWISTED_PAIR", true);
    expect(classifyPort(source, candidate)).toBe("occupied");
  });

  it("reports occupied before incompatible when a port is both", () => {
    const source = port("printer1.eth0", "TWISTED_PAIR");
    const candidate = port("uplink1.sfp0", "FIBER", true);
    expect(classifyPort(source, candidate)).toBe("occupied");
  });
});

describe("guidePortStates", () => {
  it("discovers every compatible, available port relative to the dragged source", () => {
    const ports: GuidePort[] = [
      port("printer1.eth0", "TWISTED_PAIR"),
      port("switch1.port1", "TWISTED_PAIR"),
      port("switch1.port2", "TWISTED_PAIR", true),
      port("uplink1.sfp0", "FIBER"),
    ];

    const guide = guidePortStates("printer1.eth0", ports);

    expect(guide).toEqual({
      "printer1.eth0": "source",
      "switch1.port1": "compatible",
      "switch1.port2": "occupied",
      "uplink1.sfp0": "incompatible",
    });
  });

  it("returns no guidance when the dragged source port can't be found", () => {
    const ports: GuidePort[] = [port("switch1.port1", "TWISTED_PAIR")];
    expect(guidePortStates("missing.eth0", ports)).toEqual({});
  });
});

describe("describeConnectionRejection", () => {
  it("explains an incompatible media rejection", () => {
    const message = describeConnectionRejection({ code: "INCOMPATIBLE_MEDIA", message: "incompatible media: TWISTED_PAIR vs FIBER" });
    expect(message).toBe("These ports use different cable media and cannot be linked.");
  });

  it("names the occupied endpoint when given one", () => {
    const message = describeConnectionRejection({
      code: "ENDPOINT_OCCUPIED",
      message: "endpoint 'switch1.port1' is occupied",
      details: { endpoint: "switch1.port1" },
    });
    expect(message).toBe("switch1.port1 already has a cable attached.");
  });

  it("falls back to a generic occupied message with no endpoint detail", () => {
    const message = describeConnectionRejection({ code: "ENDPOINT_OCCUPIED", message: "endpoint occupied" });
    expect(message).toBe("That port already has a cable attached.");
  });

  it("explains a self connection rejection", () => {
    const message = describeConnectionRejection({ code: "SELF_CONNECTION", message: "cannot connect: SELF_CONNECTION" });
    expect(message).toBe("A cable cannot connect a port to itself.");
  });

  it("explains a cable already occupied by a different pair", () => {
    const message = describeConnectionRejection({ code: "CABLE_OCCUPIED", message: "cable 'cable1' is already connected to a different pair" });
    expect(message).toBe("That cable is already connected to a different pair of ports.");
  });

  it("falls back to the server message for an unrecognized code", () => {
    const message = describeConnectionRejection({ code: "INTERNAL", message: "something went wrong" });
    expect(message).toBe("something went wrong");
  });
});
