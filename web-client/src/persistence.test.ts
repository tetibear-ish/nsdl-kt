import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  buildDocument,
  clearSavedLab,
  exportDocumentAsFile,
  importDocumentFromFile,
  loadLabFromLocalStorage,
  parseDocument,
  saveLabToLocalStorage,
  serializeDocument,
  TOPOLOGY_DOCUMENT_VERSION,
  type TopologyDocument,
} from "./persistence";

const validDoc: TopologyDocument = {
  version: TOPOLOGY_DOCUMENT_VERSION,
  objects: [
    { id: "printer1", type: "printer", props: { bootMs: 500 } },
    { id: "switch1", type: "ethernet-switch", props: {} },
    { id: "cable1", type: "cat5-cable", props: {} },
  ],
  connections: [{ cableId: "cable1", a: "printer1.eth0", b: "switch1.port1" }],
  positions: { printer1: { x: 80, y: 120 }, switch1: { x: 320, y: 120 } },
};

describe("buildDocument", () => {
  it("assembles a versioned document from objects, connections and positions", () => {
    const doc = buildDocument(validDoc.objects, validDoc.connections, validDoc.positions);
    expect(doc).toEqual(validDoc);
  });
});

describe("serializeDocument / parseDocument round trip", () => {
  it("parses back to an equal document, including positions", () => {
    const json = serializeDocument(validDoc);
    const result = parseDocument(json);

    expect(result.ok).toBe(true);
    if (result.ok) expect(result.document).toEqual(validDoc);
  });
});

describe("parseDocument validation", () => {
  it("rejects malformed JSON with a precise error", () => {
    const result = parseDocument("{not json");
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors[0].message).toMatch(/json/i);
  });

  it("rejects a document that is not a JSON object", () => {
    const result = parseDocument("[]");
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors[0].path).toBe("");
  });

  it("rejects an unsupported schema version", () => {
    const result = parseDocument(JSON.stringify({ ...validDoc, version: 2 }));
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors).toEqual([{ path: "version", message: expect.stringMatching(/unsupported version/i) }]);
  });

  it("rejects duplicate object ids", () => {
    const doc = {
      ...validDoc,
      objects: [...validDoc.objects, { id: "printer1", type: "printer", props: {} }],
    };
    const result = parseDocument(JSON.stringify(doc));
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors).toEqual([{ path: "objects[3].id", message: expect.stringMatching(/duplicate/i) }]);
  });

  it("rejects a connection referencing an object that doesn't exist", () => {
    const doc = {
      ...validDoc,
      connections: [{ cableId: "cable1", a: "printer1.eth0", b: "nope.eth0" }],
    };
    const result = parseDocument(JSON.stringify(doc));
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors).toEqual([{ path: "connections[0].b", message: expect.stringMatching(/unknown object 'nope'/i) }]);
  });

  it("rejects a connection whose cableId is not one of the document's objects", () => {
    const doc = { ...validDoc, connections: [{ cableId: "ghost-cable", a: "printer1.eth0", b: "switch1.port1" }] };
    const result = parseDocument(JSON.stringify(doc));
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors).toEqual([{ path: "connections[0].cableId", message: expect.stringMatching(/unknown object 'ghost-cable'/i) }]);
  });

  it("collects every error in one pass rather than stopping at the first", () => {
    const doc = {
      ...validDoc,
      objects: [...validDoc.objects, { id: "printer1", type: "printer", props: {} }],
      connections: [{ cableId: "cable1", a: "printer1.eth0", b: "nope.eth0" }],
    };
    const result = parseDocument(JSON.stringify(doc));
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.errors).toHaveLength(2);
  });
});

describe("localStorage save/load, including position restoration", () => {
  beforeEach(() => clearSavedLab());

  it("returns null when nothing has been saved", () => {
    expect(loadLabFromLocalStorage()).toBeNull();
  });

  it("round-trips a saved lab, restoring object positions exactly", () => {
    saveLabToLocalStorage(validDoc);

    const result = loadLabFromLocalStorage();

    expect(result?.ok).toBe(true);
    if (result?.ok) {
      expect(result.document.positions).toEqual(validDoc.positions);
      expect(result.document).toEqual(validDoc);
    }
  });

  it("reports a validation error instead of throwing when the stored value is corrupted", () => {
    localStorage.setItem("nsdl-topology-lab", "{not json");

    const result = loadLabFromLocalStorage();

    expect(result?.ok).toBe(false);
  });

  it("clearSavedLab removes the entry so a later load sees nothing saved", () => {
    saveLabToLocalStorage(validDoc);
    clearSavedLab();

    expect(loadLabFromLocalStorage()).toBeNull();
  });
});

describe("importDocumentFromFile", () => {
  it("parses a valid uploaded file", async () => {
    const file = new File([serializeDocument(validDoc)], "topology.json", { type: "application/json" });

    const result = await importDocumentFromFile(file);

    expect(result.ok).toBe(true);
    if (result.ok) expect(result.document).toEqual(validDoc);
  });

  it("reports a validation error instead of throwing for a malformed uploaded file", async () => {
    const file = new File(["{not json"], "topology.json", { type: "application/json" });

    const result = await importDocumentFromFile(file);

    expect(result.ok).toBe(false);
  });
});

describe("exportDocumentAsFile", () => {
  afterEach(() => vi.restoreAllMocks());

  it("downloads the serialized document as a named JSON file", () => {
    const createUrl = vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:mock-url");
    const revokeUrl = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
    const clickSpy = vi.fn();
    const anchor = { href: "", download: "", click: clickSpy } as unknown as HTMLAnchorElement;
    vi.spyOn(document, "createElement").mockReturnValue(anchor);

    exportDocumentAsFile(validDoc, "my-lab.json");

    expect(createUrl).toHaveBeenCalledTimes(1);
    const blob = createUrl.mock.calls[0][0] as Blob;
    expect(blob.type).toBe("application/json");
    expect(anchor.download).toBe("my-lab.json");
    expect(clickSpy).toHaveBeenCalledTimes(1);
    expect(revokeUrl).toHaveBeenCalledWith("blob:mock-url");
  });
});
