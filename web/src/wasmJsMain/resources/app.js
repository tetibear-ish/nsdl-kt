function startNsdl(execute, graph) {
  const output = document.getElementById("output");
  const input = document.getElementById("command");
  const svg = document.getElementById("topology");
  const empty = document.getElementById("empty");
  const revision = document.getElementById("revision");
  const history = [];
  let historyIndex = 0;

  const append = (text, className = "") => {
    const line = document.createElement("span");
    line.className = className;
    line.textContent = text + "\n";
    output.appendChild(line);
    output.scrollTop = output.scrollHeight;
  };

  const render = async () => {
    const objects = JSON.parse(await Promise.resolve(graph()));
    const nodes = objects.filter(object => object.kind === "DEVICE");
    const cables = objects.filter(object => object.kind === "CABLE" && object.relations.endpoints.length === 2);
    svg.replaceChildren();
    empty.hidden = nodes.length > 0;
    if (!nodes.length) return;

    const width = Math.max(svg.clientWidth, 500);
    const height = Math.max(svg.clientHeight, 360);
    const centerX = width / 2;
    const centerY = height / 2;
    const radius = Math.min(width, height) * 0.32;
    const positions = new Map(nodes.map((node, index) => {
      const angle = -Math.PI / 2 + index * Math.PI * 2 / nodes.length;
      return [node.id, { x: centerX + Math.cos(angle) * radius, y: centerY + Math.sin(angle) * radius }];
    }));
    const owner = endpoint => endpoint.includes(".") ? endpoint.slice(0, endpoint.lastIndexOf(".")) : endpoint;
    const element = (name, attributes = {}) => {
      const el = document.createElementNS("http://www.w3.org/2000/svg", name);
      Object.entries(attributes).forEach(([key, value]) => el.setAttribute(key, value));
      return el;
    };

    cables.forEach(cable => {
      const [a, b] = cable.relations.endpoints.map(owner).map(id => positions.get(id));
      if (!a || !b) return;
      svg.appendChild(element("line", { x1: a.x, y1: a.y, x2: b.x, y2: b.y, class: "cable" }));
      const label = element("text", { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 - 8, class: "cable-label" });
      label.textContent = cable.id;
      svg.appendChild(label);
    });

    nodes.forEach(node => {
      const point = positions.get(node.id);
      const group = element("g", { class: `node ${node.state.power === "ON" ? "online" : ""}`, tabindex: "0" });
      group.appendChild(element("circle", { cx: point.x, cy: point.y, r: 47 }));
      const name = element("text", { x: point.x, y: point.y - 3, class: "node-name" });
      name.textContent = node.id;
      const type = element("text", { x: point.x, y: point.y + 17, class: "node-type" });
      type.textContent = node.type;
      group.append(name, type);
      group.addEventListener("click", () => run(`inspect ${node.id}`));
      svg.appendChild(group);
    });
  };

  const run = async command => {
    if (!command.trim()) return;
    append(`nsdl> ${command}`, "command-line");
    let response;
    try {
      response = JSON.parse(await Promise.resolve(execute(command)));
      append(JSON.stringify(response, null, 2), response.ok ? "success" : "failure");
      if (response.revision !== undefined) revision.textContent = `revision ${response.revision}`;
      await render();
    } catch (error) {
      append(`Browser error: ${error.message}`, "failure");
    }
  };

  document.getElementById("command-form").addEventListener("submit", event => {
    event.preventDefault();
    const command = input.value;
    if (command.trim()) { history.push(command); historyIndex = history.length; }
    input.value = "";
    run(command);
  });
  input.addEventListener("keydown", event => {
    if (event.key === "ArrowUp" && historyIndex > 0) { event.preventDefault(); input.value = history[--historyIndex]; }
    if (event.key === "ArrowDown") { event.preventDefault(); input.value = historyIndex < history.length - 1 ? history[++historyIndex] : ""; }
  });
  document.getElementById("clear").addEventListener("click", () => output.replaceChildren());
  document.getElementById("demo").addEventListener("click", async () => {
    const commands = [
      "reset 7",
      "create printer printer1 bootMs=0",
      "create gateway gateway address=10.0.0.1 poolStart=10.0.0.100 poolEnd=10.0.0.110 router=10.0.0.1",
      "create ethernet-switch switch1",
      "create cat5-cable printer-cable",
      "create cat5-cable gateway-cable",
      "connect printer-cable printer1.eth0 switch1.port1",
      "connect gateway-cable gateway.eth0 switch1.port2",
      "power-on switch1", "power-on gateway", "power-on printer1",
      "advance 5000", "inspect printer1.eth0"
    ];
    for (const command of commands) await run(command);
  });

  window.nsdlRefresh = render;
  const runtime = document.querySelector(".eyebrow").textContent.includes("Wasm") ? "Wasm" : "server";
  append(`NSDL ${runtime} ready. Type 'help' or load the DHCP demo.`, "welcome");
  render();
}
