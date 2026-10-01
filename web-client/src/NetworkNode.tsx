import { Handle, Position, type NodeProps } from "@xyflow/react";
import type { CSSProperties } from "react";
import { useActivityStore } from "./activityStore";
import { formatVirtualTime } from "./clock";
import { useClockTimeStore } from "./clockStore";
import { parseDhcpLease } from "./dhcpLease";
import { parseDhcpServerLeases } from "./dhcpServerLeases";
import type { NetworkNode as NetworkNodeType } from "./topology";

export function NetworkNode({ data }: NodeProps<NetworkNodeType>) {
  const power = String(data.snapshot.state.power ?? "OFF");
  const split = Math.ceil(data.ports.length / 2);
  const pulses = useActivityStore((state) => state.pulses);
  const nowMs = useClockTimeStore((state) => state.nowMs);
  const lease = data.dhcpLease ? parseDhcpLease(data.dhcpLease, nowMs) : null;
  const leases = data.dhcpServer ? parseDhcpServerLeases(data.dhcpServer, nowMs) : [];

  return (
    <article className={`network-node power-${power.toLowerCase()}`} aria-label={`${data.snapshot.id} ${power}`}>
      <div className="node-status" aria-hidden="true" />
      <strong>{data.snapshot.id}</strong>
      <span>{data.snapshot.type}</span>
      <small>{power}</small>
      {lease?.address && (
        <small className="node-dhcp">
          {lease.address}
          {lease.remainingMs !== null && ` · ${formatVirtualTime(lease.remainingMs)}`}
        </small>
      )}
      {data.dhcpServer && (
        <table className="node-dhcp-leases nodrag nopan">
          <colgroup>
            <col className="lease-address" />
            <col className="lease-mac" />
            <col className="lease-remaining" />
          </colgroup>
          <thead>
            <tr>
              <th scope="col">Address</th>
              <th scope="col">MAC address</th>
              <th scope="col">Lease</th>
            </tr>
          </thead>
          <tbody>
            {leases.length === 0 ? (
              <tr>
                <td className="lease-waiting" colSpan={3}>Waiting for DHCP Broadcast...</td>
              </tr>
            ) : leases.map((row) => (
              <tr key={row.mac}>
                <td>{row.address}</td>
                <td>{row.mac}</td>
                <td>{row.remainingMs !== null ? formatVirtualTime(row.remainingMs) : "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <button
        className="node-power nodrag nopan"
        onClick={(event) => { event.stopPropagation(); data.onPowerToggle?.(data.snapshot); }}
        title={power === "OFF" ? "Power on" : "Power off"}
        type="button"
      >{power === "OFF" ? "Power on" : "Power off"}</button>
      {data.ports.map((port, index) => {
        const left = index < split;
        const sideIndex = left ? index : index - split;
        const sideCount = left ? split : data.ports.length - split;
        const pulse = pulses[port.id];
        const classNames = ["port"];
        if (port.occupied) classNames.push("occupied");
        if (pulse) classNames.push(`pulse-${pulse.kind}`);
        return (
          <Handle
            className={classNames.join(" ")}
            id={port.id}
            isConnectable={!port.occupied || port.reconnectable}
            key={port.id}
            onContextMenu={(event) => {
              event.preventDefault();
              event.stopPropagation();
              if (port.cableId) data.onCableDelete?.(port.cableId);
            }}
            position={left ? Position.Left : Position.Right}
            type="source"
            style={{
              top: `${((sideIndex + 1) / (sideCount + 1)) * 100}%`,
              ...(pulse ? { "--pulse-strength": Math.min(pulse.count, 6), animationDuration: `${Math.max(80, 400 / pulse.count)}ms` } : {}),
            } as CSSProperties}
            title={`${port.name}${port.occupied ? " (connected)" : ""}`}
          />
        );
      })}
    </article>
  );
}
