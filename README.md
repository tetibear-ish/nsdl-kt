# nsdl

## Running

This project needs a JDK (toolchain target: 21). On NixOS, use the provided
flake to get a correct `JAVA_HOME` without depending on whatever happens to
be on `PATH`:

```sh
nix develop --command ./gradlew run
```

(`nix-shell` also works if flakes aren't enabled: `nix-shell --command './gradlew run'`.)

Run the test suite with the pinned development environment and offline dependency cache:

```sh
nix develop --command ./gradlew test --offline --console=plain
```

The application has three commands:

```sh
# Start a loopback IPC server. Port 0 asks the OS to select a free port.
nix develop --command ./gradlew run --args='serve --port 0 --seed 7'

# Run the end-to-end DHCP example against an existing server.
nix develop --command ./gradlew run --args='example --port 54321'

# Start an in-process server and run the same example against it.
nix develop --command ./gradlew run --args=demo

# Open an interactive, in-memory network lab.
nix develop --command ./gradlew run --args='shell --seed 7'

# Or attach the shell to an already-running NSDL server.
nix develop --command ./gradlew run --args='shell --port 54321'

# Start the shared JVM simulation with its browser graph and CLI.
nix develop --command ./gradlew run --args='web --port 8080 --seed 7'

# Build the standalone Kotlin/Wasm site.
nix develop --command ./gradlew :web:wasmJsBrowserDistribution --offline
```

`serve` prints `NSDL_IPC_LISTENING port=N` once it is ready. The transport is
newline-delimited JSON over TCP on `127.0.0.1`; it is intended as a local
process boundary, not an authenticated network service.

Inside the shell, type `help` to see commands. Objects can be created with
`create TYPE ID [PROPERTY=VALUE ...]`, connected with a cable using
`connect CABLE ENDPOINT_A ENDPOINT_B`, powered on, advanced through virtual
time, listed, and inspected. For example, an `ethernet-switch` exposes
`switch1.port1` through `switch1.port8`.

The `web` command serves the UI at `http://127.0.0.1:8080/`. Commands use the
same versioned request model as IPC, and topology changes stream to every open
browser over server-sent events. The standalone Wasm distribution runs the
same simulation core entirely in the browser and is written to
`web/build/dist/wasmJs/productionExecutable`.

### React topology client

The interactive React Flow client lives in `web-client`. Start the JVM web
server, then run the Vite development server in another terminal:

```sh
nix develop --command ./gradlew run --args='web --port 8080 --seed 7'
cd web-client
nix develop --command npm ci
nix develop --command npm run dev
```

Open `http://127.0.0.1:5173/?runtime=server`. Vite proxies `/api` to the JVM
server. This mode shares one simulation among connected browsers. Run its tests
and production build with:

```sh
nix develop --command npm test
nix develop --command npm run build
```

For a fully static, single-user build that needs no JVM at runtime:

```sh
cd web-client
nix develop --command npm ci
nix develop --command npm run build:static
```

The result in `web-client/dist` contains both the React client and the Kotlin/Wasm
simulation. Serve that directory with any static file server. Its default mode
is browser-local; append `?runtime=server` only when it is hosted alongside the
NSDL HTTP service. Browser-local topology state lives in that page session and
is not shared with other users.

Pushes to `master` run `.github/workflows/pages.yml`, test the client, build the
static site, and deploy it to GitHub Pages. In the repository's Pages settings,
select **GitHub Actions** as the source before the first deployment.

## Documentation

- [Architecture](docs/ARCHITECTURE.md) describes ownership, dependency direction,
  extension points, the simulated link, and the supported DHCP behavior.
- [IPC protocol](docs/IPC.md) documents protocol version 1, commands, replies,
  events, errors, subscriptions, and reconnect behavior.
