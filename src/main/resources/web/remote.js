(() => {
  let nextId = 1;
  let lastSeq = 0;

  const properties = words => Object.fromEntries(words.map(word => {
    const separator = word.indexOf("=");
    if (separator < 1) throw new Error(`property '${word}' must be NAME=VALUE`);
    return [word.slice(0, separator), word.slice(separator + 1)];
  }));

  const operation = line => {
    const words = line.trim().split(/\s+/).filter(Boolean);
    if (!words.length) throw new Error("empty command");
    switch (words[0]) {
      case "types": return ["listTypes", {}];
      case "list": return ["listObjects", {}];
      case "inspect": return ["inspect", { id: words[1] }];
      case "create": return ["create", { type: words[1], id: words[2], props: properties(words.slice(3)) }];
      case "connect": return ["connect", { cableId: words[1], a: words[2], b: words[3] }];
      case "disconnect": return ["disconnect", { cableId: words[1] }];
      case "power-on": return ["powerOn", { id: words[1] }];
      case "power-off": return ["powerOff", { id: words[1] }];
      case "advance": return ["advance", { durationMs: Number(words[1]) }];
      case "help": return null;
      default: throw new Error(`unknown command '${words[0]}'; type 'help'`);
    }
  };

  const execute = async line => {
    const decoded = operation(line);
    if (decoded === null) return JSON.stringify({
      ok: true,
      changed: false,
      data: "types | list | inspect ID\ncreate TYPE ID [PROPERTY=VALUE ...]\nconnect CABLE ENDPOINT_A ENDPOINT_B | disconnect CABLE\npower-on ID | power-off ID | advance MILLISECONDS"
    });
    const [op, params] = decoded;
    const response = await fetch("/api/command", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ v: 1, id: `web-${nextId++}`, op, params })
    });
    const wire = await response.json();
    return JSON.stringify(wire.type === "result"
      ? { ok: true, changed: wire.changed, revision: wire.revision, data: wire.data }
      : { ok: false, error: wire.error });
  };

  const graph = async () => {
    const response = JSON.parse(await execute("list"));
    if (!response.ok) throw new Error(response.error.message);
    lastSeq = Math.max(lastSeq, response.revision || 0);
    return JSON.stringify(response.data || []);
  };

  startNsdl(execute, graph);

  const events = new EventSource(`/api/events?from=${lastSeq}`);
  events.onmessage = event => {
    const message = JSON.parse(event.data);
    if (message.seq) lastSeq = message.seq;
    if (window.nsdlRefresh) window.nsdlRefresh();
  };
})();
