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
```

`serve` prints `NSDL_IPC_LISTENING port=N` once it is ready. The transport is
newline-delimited JSON over TCP on `127.0.0.1`; it is intended as a local
process boundary, not an authenticated network service.

## Documentation

- [Architecture](docs/ARCHITECTURE.md) describes ownership, dependency direction,
  extension points, the simulated link, and the supported DHCP behavior.
- [IPC protocol](docs/IPC.md) documents protocol version 1, commands, replies,
  events, errors, subscriptions, and reconnect behavior.
