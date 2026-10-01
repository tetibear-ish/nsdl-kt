import type { SoftwarePrinter } from "./softwareLayer";

type Props = {
  address?: string | null;
  printers: SoftwarePrinter[];
};

export function SoftwareInspector({ address, printers }: Props) {
  return (
    <section className="software-inspector" aria-label="Software layer">
      <h3>Software layer · Workstation</h3>
      <p className="software-address">Desktop network address: <code>{address ?? "unconfigured"}</code></p>
      <strong>Print</strong>
      {printers.length === 0
        ? <p>No printers have been added to this workstation.</p>
        : <table className="software-printers">
          <thead><tr><th>Printer</th><th>IP address</th></tr></thead>
          <tbody>{printers.map((printer) => <tr key={printer.id}><td>{printer.name}</td><td><code>{printer.address}</code></td></tr>)}</tbody>
        </table>}
      <small>Printer names are software aliases; IP addresses are learned from the simulated network.</small>
    </section>
  );
}
