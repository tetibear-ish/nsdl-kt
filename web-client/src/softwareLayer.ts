export type SoftwarePrinter = {
  id: string;
  name: string;
  address: string;
  mac: string;
};

export function addSoftwarePrinter(current: SoftwarePrinter[], printer: SoftwarePrinter): SoftwarePrinter[] {
  return current.some((candidate) => candidate.id === printer.id) ? current : [...current, printer];
}

export function renameSoftwarePrinter(current: SoftwarePrinter[], id: string, name: string): SoftwarePrinter[] {
  const trimmed = name.trim();
  return trimmed ? current.map((printer) => printer.id === id ? { ...printer, name: trimmed } : printer) : current;
}

export function removeSoftwarePrinter(current: SoftwarePrinter[], id: string): SoftwarePrinter[] {
  return current.filter((printer) => printer.id !== id);
}
