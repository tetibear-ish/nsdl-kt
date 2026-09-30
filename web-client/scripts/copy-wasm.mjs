import { cp, mkdir, readdir, rm } from "node:fs/promises";
import { resolve } from "node:path";

const source = resolve("../web/build/dist/wasmJs/productionExecutable");
const target = resolve("public/wasm");
await rm(target, { recursive: true, force: true });
await mkdir(target, { recursive: true });
for (const name of await readdir(source)) {
  if (name === "nsdl-web.js" || name.endsWith(".wasm")) await cp(resolve(source, name), resolve(target, name));
}
