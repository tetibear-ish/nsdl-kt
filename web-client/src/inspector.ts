import type { ObjectSnapshot } from "./types";

export function inspectionIds(snapshot: ObjectSnapshot): string[] {
  return [snapshot.id, ...(snapshot.relations.interfaces ?? []), ...(snapshot.relations.services ?? [])];
}
