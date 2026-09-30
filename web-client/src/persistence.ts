export const TOPOLOGY_DOCUMENT_VERSION = 1;

export type TopologyDocumentObject = { id: string; type: string; props: Record<string, unknown> };
export type TopologyDocumentConnection = { cableId: string; a: string; b: string };
export type TopologyPosition = { x: number; y: number };

export type TopologyDocument = {
  version: typeof TOPOLOGY_DOCUMENT_VERSION;
  objects: TopologyDocumentObject[];
  connections: TopologyDocumentConnection[];
  positions: Record<string, TopologyPosition>;
};

export type ValidationError = { path: string; message: string };
export type ValidationResult =
  | { ok: true; document: TopologyDocument }
  | { ok: false; errors: ValidationError[] };

const STORAGE_KEY = "nsdl-topology-lab";

export function saveLabToLocalStorage(document: TopologyDocument): void {
  localStorage.setItem(STORAGE_KEY, serializeDocument(document));
}

export function loadLabFromLocalStorage(): ValidationResult | null {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (raw === null) return null;
  return parseDocument(raw);
}

export function clearSavedLab(): void {
  localStorage.removeItem(STORAGE_KEY);
}

export async function importDocumentFromFile(file: File): Promise<ValidationResult> {
  return parseDocument(await file.text());
}

export function exportDocumentAsFile(document: TopologyDocument, filename = "topology.json"): void {
  const blob = new Blob([serializeDocument(document)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = window.document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}

export function buildDocument(
  objects: TopologyDocumentObject[],
  connections: TopologyDocumentConnection[],
  positions: Record<string, TopologyPosition>,
): TopologyDocument {
  return { version: TOPOLOGY_DOCUMENT_VERSION, objects, connections, positions };
}

export function serializeDocument(document: TopologyDocument): string {
  return JSON.stringify(document, null, 2);
}

/** The owning object id of an endpoint ref like "printer1.eth0" (itself, if there's no dot). */
const endpointOwner = (endpoint: string): string =>
  endpoint.includes(".") ? endpoint.slice(0, endpoint.lastIndexOf(".")) : endpoint;

export function parseDocument(json: string): ValidationResult {
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch (error) {
    return { ok: false, errors: [{ path: "", message: `malformed JSON: ${error instanceof Error ? error.message : String(error)}` }] };
  }
  return validateDocument(parsed);
}

export function validateDocument(value: unknown): ValidationResult {
  const errors: ValidationError[] = [];

  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return { ok: false, errors: [{ path: "", message: "document must be a JSON object" }] };
  }
  const root = value as Record<string, unknown>;

  if (root.version !== TOPOLOGY_DOCUMENT_VERSION) {
    errors.push({ path: "version", message: `unsupported version: ${JSON.stringify(root.version)}` });
  }

  const objects = Array.isArray(root.objects) ? (root.objects as unknown[]) : [];
  if (!Array.isArray(root.objects)) errors.push({ path: "objects", message: "must be an array" });
  const seenIds = new Set<string>();
  objects.forEach((entry, index) => {
    const object = entry as Partial<TopologyDocumentObject> | null;
    const id = object?.id;
    if (typeof id !== "string" || id.length === 0) {
      errors.push({ path: `objects[${index}].id`, message: "must be a non-empty string" });
      return;
    }
    if (seenIds.has(id)) {
      errors.push({ path: `objects[${index}].id`, message: `duplicate object id '${id}'` });
      return;
    }
    seenIds.add(id);
  });

  const connections = Array.isArray(root.connections) ? (root.connections as unknown[]) : [];
  if (!Array.isArray(root.connections)) errors.push({ path: "connections", message: "must be an array" });
  connections.forEach((entry, index) => {
    const connection = entry as Partial<TopologyDocumentConnection> | null;
    (["cableId", "a", "b"] as const).forEach((field) => {
      const ref = connection?.[field];
      if (typeof ref !== "string" || ref.length === 0) {
        errors.push({ path: `connections[${index}].${field}`, message: "must be a non-empty string" });
        return;
      }
      const owner = field === "cableId" ? ref : endpointOwner(ref);
      if (!seenIds.has(owner)) {
        errors.push({ path: `connections[${index}].${field}`, message: `unknown object '${owner}'` });
      }
    });
  });

  const positions = (typeof root.positions === "object" && root.positions !== null && !Array.isArray(root.positions))
    ? (root.positions as Record<string, unknown>)
    : null;
  if (positions === null) {
    errors.push({ path: "positions", message: "must be an object" });
  } else {
    for (const [id, pos] of Object.entries(positions)) {
      const p = pos as Partial<TopologyPosition> | null;
      if (typeof p?.x !== "number" || typeof p?.y !== "number") {
        errors.push({ path: `positions.${id}`, message: "must be {x: number, y: number}" });
      }
    }
  }

  if (errors.length > 0) return { ok: false, errors };
  return {
    ok: true,
    document: {
      version: TOPOLOGY_DOCUMENT_VERSION,
      objects: objects as TopologyDocumentObject[],
      connections: connections as TopologyDocumentConnection[],
      positions: positions as Record<string, TopologyPosition>,
    },
  };
}
