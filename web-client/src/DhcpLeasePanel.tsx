import { formatVirtualTime } from "./clock";
import { dhcpStatusClass, parseDhcpLease } from "./dhcpLease";
import type { ObjectSnapshot } from "./types";

type Props = { snapshot: ObjectSnapshot; nowMs: number };

const STATE_LABELS: Record<string, string> = {
  STOPPED: "Stopped", INIT: "Discovering…", SELECTING: "Discovering…",
  REQUESTING: "Requesting…", BOUND: "Bound", RENEWING: "Renewing", REBINDING: "Rebinding",
};

export function DhcpLeasePanel({ snapshot, nowMs }: Props) {
  const view = parseDhcpLease(snapshot, nowMs);
  return (
    <>
      <p className={`dhcp-status ${dhcpStatusClass(view)}`}>
        {view.expired ? "Expired" : STATE_LABELS[view.clientState] ?? view.clientState}
      </p>
      {view.address ? (
        <dl className="dhcp-lease">
          <dt>Address</dt><dd>{view.address}</dd>
          {view.subnetMask && <><dt>Subnet</dt><dd>{view.subnetMask}</dd></>}
          {view.router && <><dt>Router</dt><dd>{view.router}</dd></>}
          {view.server && <><dt>Server</dt><dd>{view.server}</dd></>}
          {view.leaseDurationMs !== null && <><dt>Lease</dt><dd>{formatVirtualTime(view.leaseDurationMs)}</dd></>}
          {view.remainingMs !== null && <><dt>Remaining</dt><dd>{formatVirtualTime(view.remainingMs)}</dd></>}
        </dl>
      ) : <p>No active lease.</p>}
      <details>
        <summary>Raw snapshot</summary>
        <pre>{JSON.stringify({ type: snapshot.type, kind: snapshot.kind, state: snapshot.state, relations: snapshot.relations }, null, 2)}</pre>
      </details>
    </>
  );
}
