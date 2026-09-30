import { Dialog } from "radix-ui";
import { useEffect, useMemo, useState } from "react";
import type { ObjectTypeSchema } from "./types";

type Props = {
  open: boolean;
  types: ObjectTypeSchema[];
  existingIds: string[];
  onOpenChange: (open: boolean) => void;
  onCreate: (type: ObjectTypeSchema, id: string, props: Record<string, string>) => Promise<string | null>;
};

const FRIENDLY_NAMES: Record<string, string> = {
  "ethernet-switch": "switch",
  "dhcp-server-host": "gateway",
  printer: "printer",
};

export function defaultObjectId(typeName: string, existingIds: string[]): string {
  const base = FRIENDLY_NAMES[typeName] ?? (typeName.replace(/[^A-Za-z0-9]/g, "") || "object");
  const occupied = new Set(existingIds);
  let sequence = 1;
  while (occupied.has(`${base}${sequence}`)) sequence += 1;
  return `${base}${sequence}`;
}

export function AddObjectDialog({ open, types, existingIds, onOpenChange, onCreate }: Props) {
  const deviceTypes = useMemo(() => types.filter((type) => type.kind === "DEVICE"), [types]);
  const [selectedName, setSelectedName] = useState("");
  const [id, setId] = useState("");
  const [properties, setProperties] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const selected = deviceTypes.find((type) => type.name === selectedName);

  useEffect(() => {
    if (open && !selectedName && deviceTypes[0]) setSelectedName(deviceTypes[0].name);
  }, [deviceTypes, open, selectedName]);

  useEffect(() => {
    if (open && selectedName && !id) setId(defaultObjectId(selectedName, existingIds));
  }, [existingIds, id, open, selectedName]);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!selected) return;
    const failure = await onCreate(selected, id, properties);
    if (failure) setError(failure);
    else {
      setId("");
      setProperties({});
      setError(null);
      onOpenChange(false);
    }
  };

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="dialog-overlay" />
        <Dialog.Content className="dialog-content">
          <Dialog.Title>Add network object</Dialog.Title>
          <Dialog.Description>New devices are created powered off and disconnected.</Dialog.Description>
          <form onSubmit={submit}>
            <label>
              Type
              <select value={selectedName} onChange={(event) => {
                setSelectedName(event.target.value);
                setId(defaultObjectId(event.target.value, existingIds));
                setProperties({});
              }}>
                {deviceTypes.map((type) => <option key={type.name} value={type.name}>{type.name}</option>)}
              </select>
            </label>
            <label>
              Object ID
              <input required pattern="[A-Za-z][A-Za-z0-9_.-]{0,63}" value={id} onChange={(event) => setId(event.target.value)} />
            </label>
            {selected?.properties.map((property) => (
              <label key={property.name}>
                {property.name}{property.required ? " *" : ""}
                <input
                  required={property.required}
                  placeholder={property.default == null ? property.description : String(property.default)}
                  value={properties[property.name] ?? ""}
                  onChange={(event) => setProperties((current) => ({ ...current, [property.name]: event.target.value }))}
                />
              </label>
            ))}
            {error && <p className="form-error">{error}</p>}
            <footer>
              <Dialog.Close asChild><button type="button" className="secondary">Cancel</button></Dialog.Close>
              <button type="submit">Add object</button>
            </footer>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
