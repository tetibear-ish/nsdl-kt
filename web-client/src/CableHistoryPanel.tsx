import { useState } from "react";
import { formatVirtualTime } from "./clock";
import { parseCableFrames, parseCablePackets, type CableFrameEntry, type CablePacketEntry } from "./cableHistory";
import { rootNodeId } from "./packetInspector";
import type { ObjectSnapshot } from "./types";

type Props = { snapshot: ObjectSnapshot; onNavigate: (nodeId: string) => void };
type View = "packets" | "frames";

function Endpoint({ id, onNavigate }: { id: string; onNavigate: (nodeId: string) => void }) {
  return <button type="button" className="packet-endpoint" onClick={() => onNavigate(rootNodeId(id))}>{id}</button>;
}

function outcomeText(entry: { outcome: string; dropReason?: string }): string {
  return entry.outcome === "DROPPED" ? entry.dropReason ?? "DROPPED" : "delivered";
}

function packetDetail(entry: CablePacketEntry): string {
  if (entry.dhcp) return entry.dhcp.messageType;
  if (entry.sourceIp && entry.destIp) {
    const ports = entry.sourcePort !== undefined && entry.destPort !== undefined ? `:${entry.sourcePort} > :${entry.destPort}` : "";
    return `${entry.sourceIp} > ${entry.destIp}${ports}`;
  }
  return entry.protocol ?? "";
}

/** Every cable's own bounded, ordered history at both layers (S20): the Ethernet frames that
 * traversed it, and the decoded packets carried by those frames -- sharing one id per transit so
 * switching views never suggests two transmissions where there was one. */
export function CableHistoryPanel({ snapshot, onNavigate }: Props) {
  const [view, setView] = useState<View>("packets");
  const frames = parseCableFrames(snapshot);
  const packets = parseCablePackets(snapshot);
  const empty = frames.length === 0 && packets.length === 0;

  return (
    <div className="cable-history">
      <div className="cable-history-tabs">
        <button className={view === "packets" ? "secondary active" : "secondary"} onClick={() => setView("packets")}>Packets</button>
        <button className={view === "frames" ? "secondary active" : "secondary"} onClick={() => setView("frames")}>Frames</button>
      </div>
      {empty ? <p>No packets observed on this cable yet.</p> : (
        <table className="packet-table">
          <thead>
            {view === "packets"
              ? <tr><th>Time</th><th>From</th><th>To</th><th>Protocol</th><th>Detail</th><th>Outcome</th></tr>
              : <tr><th>Time</th><th>From</th><th>To</th><th>Source MAC</th><th>Dest MAC</th><th>Outcome</th></tr>}
          </thead>
          <tbody>
            {view === "packets"
              ? packets.map((entry: CablePacketEntry) => (
                <tr key={entry.id} className={entry.outcome === "DROPPED" ? "packet-dropped" : undefined}>
                  <td>{formatVirtualTime(entry.sentAtMs)}</td>
                  <td><Endpoint id={entry.from} onNavigate={onNavigate} /></td>
                  <td><Endpoint id={entry.to} onNavigate={onNavigate} /></td>
                  <td>{entry.protocol ?? ""}</td>
                  <td>{packetDetail(entry)}</td>
                  <td>{outcomeText(entry)}</td>
                </tr>
              ))
              : frames.map((entry: CableFrameEntry) => (
                <tr key={entry.id} className={entry.outcome === "DROPPED" ? "packet-dropped" : undefined}>
                  <td>{formatVirtualTime(entry.sentAtMs)}</td>
                  <td><Endpoint id={entry.from} onNavigate={onNavigate} /></td>
                  <td><Endpoint id={entry.to} onNavigate={onNavigate} /></td>
                  <td>{entry.sourceMac}</td>
                  <td>{entry.destMac}</td>
                  <td>{outcomeText(entry)}</td>
                </tr>
              ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
