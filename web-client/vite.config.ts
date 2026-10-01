import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

function readGitHistory() {
  try {
    const repository = fileURLToPath(new URL("..", import.meta.url));
    const output = execFileSync(
      "git",
      ["log", "-10", "--pretty=format:%h%x1f%s"],
      { cwd: repository, encoding: "utf8" },
    );
    return output.split("\n").filter(Boolean).map((line) => {
      const [hash, ...subject] = line.split("\x1f");
      return { hash, subject: subject.join("\x1f") };
    });
  } catch {
    return [];
  }
}

export default defineConfig({
  base: "./",
  define: { __GIT_HISTORY__: JSON.stringify(readGitHistory()) },
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://127.0.0.1:8080",
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
  },
});
