import { formatVirtualTime } from "./clock";
import { filterPackets, groupByExchange, rootNodeId, type ObservedPacket, type PacketFilter } from "./packetInspector";

type Props = {
  packets: ObservedPacket[];
  filter: PacketFilter;
  paused: boolean;
  onSetFilter: (filter: PacketFilter) => void;
  onTogglePause: () => void;
  onClear: () => void;
  onNavigate: (nodeId: string) => void;
};

const PROTOCOLS = ["DHCP", "UDP", "IPv4", "ETHERNET"];

function detail(packet: ObservedPacket): string {
  if (packet.dhcp) return packet.dhcp.messageType;
  if (packet.sourceIp && packet.destIp) {
    const ports = packet.sourcePort !== undefined && packet.destPort !== undefined ? `:${packet.sourcePort} > :${packet.destPort}` : "";
    return `${packet.sourceIp} > ${packet.destIp}${ports}`;
  }
  return packet.protocol;
}

function Endpoint({ id, onNavigate }: { id: string; onNavigate: (nodeId: string) => void }) {
  return <button type="button" className="packet-endpoint" onClick={() => onNavigate(rootNodeId(id))}>{id}</button>;
}

export function PacketInspectorPanel({ packets, filter, paused, onSetFilter, onTogglePause, onClear, onNavigate }: Props) {
  const visible = filterPackets(packets, filter);
  const groups = groupByExchange(visible);

  return (
    <section className="packet-pane" aria-label="Packet inspector">
      <header>
        <strong>Packet inspector</strong>
        <div className="packet-filters">
          <label>
            Protocol
            <select
              aria-label="Protocol"
              value={filter.protocol ?? ""}
              onChange={(event) => onSetFilter({ ...filter, protocol: event.target.value || undefined })}
            >
              <option value="">All</option>
              {PROTOCOLS.map((protocol) => <option key={protocol} value={protocol}>{protocol}</option>)}
            </select>
          </label>
          <input
            aria-label="Node id"
            placeholder="node id"
            value={filter.nodeId ?? ""}
            onChange={(event) => onSetFilter({ ...filter, nodeId: event.target.value || undefined })}
          />
          <input
            aria-label="Search"
            placeholder="search"
            value={filter.text ?? ""}
            onChange={(event) => onSetFilter({ ...filter, text: event.target.value || undefined })}
          />
          <label>
            <input
              type="checkbox"
              aria-label="Dropped only"
              checked={filter.onlyDropped ?? false}
              onChange={(event) => onSetFilter({ ...filter, onlyDropped: event.target.checked || undefined })}
            />
            Dropped only
          </label>
        </div>
        <div>
          <button className="secondary" onClick={onTogglePause}>{paused ? "Resume" : "Pause"}</button>
          <button className="secondary" onClick={onClear}>Clear</button>
        </div>
      </header>
      <div className="packet-table-wrap">
        {groups.length === 0
          ? <p>No packets observed yet.</p>
          : groups.map((group) => (
            <details key={group.key} className="packet-exchange" role="group" open>
              <summary>{group.key} ({group.events.length})</summary>
              <table className="packet-table">
                <thead>
                  <tr><th>Time</th><th>From</th><th>To</th><th>Protocol</th><th>Detail</th><th>Outcome</th></tr>
                </thead>
                <tbody>
                  {group.events.map((packet) => (
                    <tr key={packet.transitId} className={packet.outcome === "DROPPED" ? "packet-dropped" : undefined}>
                      <td>{formatVirtualTime(packet.sentAtMs)}</td>
                      <td><Endpoint id={packet.from} onNavigate={onNavigate} /></td>
                      <td><Endpoint id={packet.to} onNavigate={onNavigate} /></td>
                      <td>{packet.protocol}</td>
                      <td>{detail(packet)}</td>
                      <td>{packet.outcome === "DROPPED" ? packet.dropReason ?? "DROPPED" : "delivered"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </details>
          ))}
      </div>
    </section>
  );
}
