import { useState } from "react";
import type { SoftwarePrinter } from "./softwareLayer";

type Props = {
  address?: string | null;
  printers: SoftwarePrinter[];
  candidates: SoftwarePrinter[];
  onAddPrinter: (printer: SoftwarePrinter) => void;
  onRenamePrinter: (printer: SoftwarePrinter) => void;
  onDeletePrinter: (printer: SoftwarePrinter) => void;
  onTestPage: (printer: SoftwarePrinter) => void;
};

export function SoftwareDesktop({ address, printers, candidates, onAddPrinter, onRenamePrinter, onDeletePrinter, onTestPage }: Props) {
  const [printOpen, setPrintOpen] = useState(false);
  const [discoveryOpen, setDiscoveryOpen] = useState(false);
  const available = candidates.filter((candidate) => !printers.some((printer) => printer.id === candidate.id));

  return (
    <section className="software-desktop nodrag nopan" aria-label="Simulated workstation desktop">
      <header><strong>Workstation desktop</strong><span>{address ?? "No IP address"}</span></header>
      <div className="software-desktop-body">
        <button className="desktop-app" type="button" onClick={(event) => { event.stopPropagation(); setPrintOpen((open) => !open); }}>
          <span className="desktop-app-icon">▣</span>Print
        </button>
        {printOpen && <div className="print-menu" onClick={(event) => event.stopPropagation()}>
          <strong>Print</strong>
          {printers.length === 0 && <p>No printers configured.</p>}
          {printers.map((printer) => <div className="desktop-printer" key={printer.id}>
            <span><b>{printer.name}</b><small>{printer.address}</small></span>
            <span className="desktop-printer-actions">
              <button type="button" onClick={() => onTestPage(printer)}>Send test page</button>
              <button type="button" onClick={() => onRenamePrinter(printer)}>Rename…</button>
              <button type="button" onClick={() => onDeletePrinter(printer)}>Delete</button>
            </span>
          </div>)}
          <button className="add-printer" type="button" onClick={() => setDiscoveryOpen((open) => !open)} disabled={available.length === 0}>
            {discoveryOpen ? "Hide printer discovery" : "Add Printer"}{available.length > 0 ? ` (${available.length} available)` : ""}
          </button>
          {discoveryOpen && <div className="printer-candidates" aria-label="Discovered printers">
            <strong>Printers discovered on this network</strong>
            {available.map((printer) => <div className="printer-candidate" key={printer.id}>
              <span><b>{printer.name}</b><small>{printer.address}</small></span>
              <button type="button" onClick={() => { onAddPrinter(printer); setDiscoveryOpen(false); }}>Add</button>
            </div>)}
          </div>}
        </div>}
      </div>
      <footer>Software layer · virtual desktop</footer>
    </section>
  );
}
