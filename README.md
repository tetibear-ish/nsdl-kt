# nsdl

## Running

This project needs a JDK (toolchain target: 21). On NixOS, use the provided
flake to get a correct `JAVA_HOME` without depending on whatever happens to
be on `PATH`:

```sh
nix develop --command ./gradlew run
```

(`nix-shell` also works if flakes aren't enabled: `nix-shell --command './gradlew run'`.)
