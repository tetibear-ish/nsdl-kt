import { Handle, Position, type NodeProps } from "@xyflow/react";
import type { NetworkNode as NetworkNodeType } from "./topology";

export function NetworkNode({ data }: NodeProps<NetworkNodeType>) {
  const power = String(data.snapshot.state.power ?? "OFF");
  const split = Math.ceil(data.ports.length / 2);

  return (
    <article className={`network-node power-${power.toLowerCase()}`} aria-label={`${data.snapshot.id} ${power}`}>
      <div className="node-status" aria-hidden="true" />
      <strong>{data.snapshot.id}</strong>
      <span>{data.snapshot.type}</span>
      <small>{power}</small>
      {data.ports.map((port, index) => {
        const left = index < split;
        const sideIndex = left ? index : index - split;
        const sideCount = left ? split : data.ports.length - split;
        return (
          <Handle
            className={port.occupied ? "port occupied" : "port"}
            id={port.id}
            isConnectable={!port.occupied}
            key={port.id}
            position={left ? Position.Left : Position.Right}
            type="source"
            style={{ top: `${((sideIndex + 1) / (sideCount + 1)) * 100}%` }}
            title={`${port.name}${port.occupied ? " (connected)" : ""}`}
          />
        );
      })}
    </article>
  );
}
