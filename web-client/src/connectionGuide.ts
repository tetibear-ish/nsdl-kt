/** A port as seen from the connection-drag guide: enough to classify it, nothing more. */
export type GuidePort = {
  id: string;
  media: string;
  occupied: boolean;
};

/** How a candidate port relates to the port a connection drag started from. */
export type PortGuideState = "source" | "compatible" | "incompatible" | "occupied";

/** Classifies `candidate` relative to the port a connection drag started from ([source]).
 *  Occupied takes priority over media mismatch: an occupied port isn't a valid target
 *  regardless of why, so there is no point also flagging it as incompatible. */
export function classifyPort(source: GuidePort, candidate: GuidePort): PortGuideState {
  if (candidate.id === source.id) return "source";
  if (candidate.occupied) return "occupied";
  if (candidate.media !== source.media) return "incompatible";
  return "compatible";
}

/** Classifies every port in `ports` relative to the dragged port named `sourcePortId`.
 *  Returns an empty guide when the source port can't be found (e.g. stale drag state). */
export function guidePortStates(sourcePortId: string, ports: GuidePort[]): Record<string, PortGuideState> {
  const source = ports.find((port) => port.id === sourcePortId);
  if (!source) return {};
  return Object.fromEntries(ports.map((port) => [port.id, classifyPort(source, port)]));
}

export type ConnectionError = { code: string; message: string; details?: Record<string, unknown> };

/** Turns a rejected connect/applyTopology error into a short, structured explanation for
 *  display near the attempted connection, rather than just the raw error code and message. */
export function describeConnectionRejection(error: ConnectionError): string {
  const endpoint = typeof error.details?.endpoint === "string" ? error.details.endpoint : null;
  switch (error.code) {
    case "INCOMPATIBLE_MEDIA":
      return "These ports use different cable media and cannot be linked.";
    case "ENDPOINT_OCCUPIED":
      return endpoint ? `${endpoint} already has a cable attached.` : "That port already has a cable attached.";
    case "SELF_CONNECTION":
      return "A cable cannot connect a port to itself.";
    case "CABLE_OCCUPIED":
      return "That cable is already connected to a different pair of ports.";
    default:
      return error.message;
  }
}
