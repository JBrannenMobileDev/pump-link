#!/usr/bin/env node
// Verifies every relative Markdown link in /docs and the root README resolves
// to a real file, that every "#anchor" matches a real heading, and that every
// diagram referenced from a <picture> element exists in both themes.
//
// Slug generation mirrors github-slugger: lower-case, strip punctuation, then
// replace each space individually. Em dashes in headings therefore leave two
// consecutive hyphens, which is easy to get wrong by collapsing whitespace.
//
//   node tools/check-links.mjs

import { readdir, readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join, dirname, relative, resolve } from "node:path";

const repoRoot = new URL("..", import.meta.url).pathname;
const LINK = /\]\((?!https?:|mailto:)([^)\s#]*)(#[^)\s]*)?\)/g;
const HEADING = /^#{1,6}\s+(.+?)\s*$/gm;
const ASSET = /(?:src|srcset)="(?!https?:)([^"]+)"/g;

function slug(heading) {
  return (
    "#" +
    heading
      .toLowerCase()
      .replace(/`/g, "")
      .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
      .replace(/[^\p{Letter}\p{Number}\s_-]/gu, "")
      .replace(/ /g, "-")
  );
}

const anchorCache = new Map();
async function anchorsOf(file) {
  if (!anchorCache.has(file)) {
    const body = await readFile(file, "utf8");
    const seen = new Map();
    const set = new Set();
    for (const [, text] of body.matchAll(HEADING)) {
      const base = slug(text);
      const n = seen.get(base) ?? 0;
      seen.set(base, n + 1);
      set.add(n === 0 ? base : `${base}-${n}`);
    }
    anchorCache.set(file, set);
  }
  return anchorCache.get(file);
}

const sources = [];
if (existsSync(join(repoRoot, "README.md"))) sources.push(join(repoRoot, "README.md"));
const docs = join(repoRoot, "docs");
if (existsSync(docs)) {
  for (const name of await readdir(docs)) {
    if (name.endsWith(".md")) sources.push(join(docs, name));
  }
}

let checked = 0;
const broken = [];

for (const file of sources.sort()) {
  const body = await readFile(file, "utf8");
  for (const match of body.matchAll(LINK)) {
    checked += 1;
    const [, target, anchor] = match;
    const resolved = target ? resolve(dirname(file), target) : file;
    const line = body.slice(0, match.index).split("\n").length;
    const where = `${relative(repoRoot, file)}:${line}`;

    if (!existsSync(resolved)) {
      broken.push(`${where}  missing file: ${target}`);
      continue;
    }
    if (anchor && !(await anchorsOf(resolved)).has(anchor)) {
      broken.push(`${where}  missing anchor: ${target}${anchor}`);
    }
  }

  for (const match of body.matchAll(ASSET)) {
    checked += 1;
    const [, target] = match;
    const line = body.slice(0, match.index).split("\n").length;
    if (!existsSync(resolve(dirname(file), target))) {
      broken.push(`${relative(repoRoot, file)}:${line}  missing asset: ${target}`);
    }
  }
}

if (broken.length > 0) {
  for (const line of broken) console.error(line);
  console.error(`\n${broken.length} of ${checked} links broken.`);
  process.exit(1);
}

console.log(`${checked} internal links resolve.`);
