import { useEffect, useMemo, useState } from "react";
import type { ObjectTypeSchema } from "./types";

type Props = {
  id: string;
  schema: ObjectTypeSchema;
  currentProps?: Record<string, unknown>;
  onConfigure: (props: Record<string, string>) => Promise<string | null>;
};

export function initialConfiguration(
  schema: ObjectTypeSchema,
  currentProps: Record<string, unknown> = {},
): Record<string, string> {
  return Object.fromEntries(schema.properties.map((property) => {
    const value = currentProps[property.name] ?? property.default;
    return [property.name, value == null ? "" : String(value)];
  }));
}

export function configurationPayload(
  schema: ObjectTypeSchema,
  values: Record<string, string>,
): Record<string, string> {
  return Object.fromEntries(schema.properties.flatMap((property) => {
    const value = values[property.name] ?? "";
    return value === "" && !property.required ? [] : [[property.name, value]];
  }));
}

export function ConfigureObjectForm({ id, schema, currentProps, onConfigure }: Props) {
  const initial = useMemo(() => initialConfiguration(schema, currentProps), [currentProps, schema]);
  const [values, setValues] = useState(initial);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    setValues(initial);
    setError(null);
  }, [id, initial]);

  if (schema.properties.length === 0) return null;

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setSubmitting(true);
    const failure = await onConfigure(configurationPayload(schema, values));
    setSubmitting(false);
    setError(failure);
  };

  return <form className="configuration-form" onSubmit={submit}>
    <h3>Configuration</h3>
    <p>Applying changes replaces and reboots this device.</p>
    {schema.properties.map((property) => <label key={property.name}>
      <span>{property.name}{property.required ? " *" : ""}</span>
      <input
        aria-label={property.name}
        required={property.required}
        inputMode={property.type === "LONG" ? "numeric" : undefined}
        placeholder={property.default == null ? property.description : String(property.default)}
        title={property.description}
        value={values[property.name] ?? ""}
        onChange={(event) => setValues((current) => ({ ...current, [property.name]: event.target.value }))}
      />
      {property.description && <small>{property.description}</small>}
    </label>)}
    {error && <p className="form-error" role="alert">{error}</p>}
    <button type="submit" disabled={submitting}>{submitting ? "Applying…" : "Apply configuration"}</button>
  </form>;
}
