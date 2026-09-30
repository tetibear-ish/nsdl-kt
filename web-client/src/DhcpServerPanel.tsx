import { formatVirtualTime } from "./clock";
import { parseDhcpServerLeases } from "./dhcpServerLeases";
import type { ObjectSnapshot } from "./types";

type Props = { snapshot: ObjectSnapshot; nowMs: number };

export function DhcpServerPanel({ snapshot, nowMs }: Props) {
  const rows = parseDhcpServerLeases(snapshot, nowMs);
  return (
    <>
      {rows.length === 0 ? <p>No leases.</p> : (
        <table className="dhcp-leases">
          <thead><tr><th>Address</th><th>MAC</th><th>State</th><th>Remaining</th></tr></thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.mac}>
                <td>{row.address}</td>
                <td>{row.mac}</td>
                <td>{row.state}</td>
                <td>{row.remainingMs !== null ? formatVirtualTime(row.remainingMs) : "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <details>
        <summary>Raw snapshot</summary>
        <pre>{JSON.stringify({ type: snapshot.type, kind: snapshot.kind, state: snapshot.state, relations: snapshot.relations }, null, 2)}</pre>
      </details>
    </>
  );
}
