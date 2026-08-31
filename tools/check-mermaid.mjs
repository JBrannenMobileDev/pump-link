#!/usr/bin/env node
// Extracts every ```mermaid block from the Markdown in /docs and the root
// README, and renders each one with mermaid-cli. A diagram that does not
// render is a broken document, so this runs in CI alongside the unit tests.
//
//   node tools/check-mermaid.mjs

import { readdir, readFile, mkdir, writeFile, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import { execFile } from "node:child_process";
import { join, relative } from "node:path";
import { promisify } from "node:util";

const run = promisify(execFile);
const repoRoot = new URL("..", import.meta.url).pathname;
const outDir = join(repoRoot, ".mermaid-check");

const FENCE = /^```mermaid[^\n]*\n([\s\S]*?)^```/gm;

async function markdownFiles() {
  const files = [];
  const readme = join(repoRoot, "README.md");
  if (existsSync(readme)) files.push(readme);

  const docs = join(repoRoot, "docs");
  if (existsSync(docs)) {
    for (const name of await readdir(docs)) {
      if (name.endsWith(".md")) files.push(join(docs, name));
    }
  }
  return files.sort();
}

function extract(source) {
  const blocks = [];
  for (const match of source.matchAll(FENCE)) {
    const line = source.slice(0, match.index).split("\n").length;
    blocks.push({ line, body: match[1] });
  }
  return blocks;
}

const failures = [];
let total = 0;

await rm(outDir, { recursive: true, force: true });
await mkdir(outDir, { recursive: true });

for (const file of await markdownFiles()) {
  const rel = relative(repoRoot, file);
  for (const [index, block] of extract(await readFile(file, "utf8")).entries()) {
    total += 1;
    const stem = `${rel.replace(/[^\w]+/g, "_")}_${index}`;
    const input = join(outDir, `${stem}.mmd`);
    await writeFile(input, block.body);

    try {
      await run(
        "npx",
        ["-y", "-p", "@mermaid-js/mermaid-cli", "mmdc",
         "-i", input, "-o", join(outDir, `${stem}.svg`), "-q"],
        { cwd: repoRoot, timeout: 120_000 },
      );
      process.stdout.write(".");
    } catch (error) {
      process.stdout.write("F");
      const detail = `${error.stderr || ""}${error.stdout || ""}`.trim();
      failures.push({ where: `${rel}:${block.line}`, detail });
    }
  }
}

process.stdout.write("\n");

if (failures.length > 0) {
  for (const { where, detail } of failures) {
    console.error(`\n${where}\n${detail.split("\n").slice(0, 12).join("\n")}`);
  }
  console.error(`\n${failures.length} of ${total} diagrams failed to render.`);
  process.exit(1);
}

console.log(`${total} diagrams rendered.`);
