(function () {
  let resolveReady;
  window.nsdlWasmReady = new Promise(function (resolve) { resolveReady = resolve; });
  window.startNsdl = function (_execute, _graph, command) {
    resolveReady({ command: command });
  };
}());
