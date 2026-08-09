#!/usr/bin/env node
//
// Guards against a CSS rule that lost its closing brace.
//
// This exists because of a real bug that every other gate passed. A block of rules
// was moved into a stylesheet and the last rule arrived without its `}`, so every
// selector after it became a *descendant* of that rule — `.rt-watch-editor` silently
// became `.cg-btn[disabled] .rt-watch-editor`. The file was valid CSS, the build
// succeeded, the colour checker was happy, and 1,090 unit tests passed, because jsdom
// does no layout. It was only visible in a browser, as a 240px popover rendering
// 1061px wide.
//
// Two checks, both structural:
//   1. braces balance, per file;
//   2. no declaration is followed by a selector *inside* a block — the signature of a
//      rule that lost its brace, and the thing that makes the failure silent.
//
// Deliberately not a CSS parser: a parser accepts the broken file, because nesting a
// selector inside a rule is legal CSS these days. The point is that we do not use
// nesting, so its appearance means a brace went missing.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = process.cwd();
const ROOTS = ['web', 'frontend/src'];
const IGNORED_DIRS = new Set(['node_modules', 'dist']);

/**
 * Files that legitimately nest selectors inside blocks.
 *
 * The vendored design system is not ours to reformat. Add a path here only with a
 * reason — an entry is an assertion that the file uses nesting on purpose.
 */
const NESTING_ALLOWED = [
  'frontend/vendor/',
  // At-rules nest by definition; they are unwrapped before the scan instead.
];

function* walk(dir) {
  let entries;
  try {
    entries = readdirSync(join(ROOT, dir));
  } catch {
    return;
  }
  for (const entry of entries) {
    if (IGNORED_DIRS.has(entry)) continue;
    const path = join(dir, entry);
    if (statSync(join(ROOT, path)).isDirectory()) yield* walk(path);
    else if (path.endsWith('.css')) yield path;
  }
}

/** Comments hold braces and colons that are not code. */
const withoutComments = (css) => css.replace(/\/\*[\s\S]*?\*\//g, (match) => match.replace(/[^\n]/g, ' '));

/** Where each character sits, so a report can name a line. */
function lineOf(css, index) {
  let line = 1;
  for (let i = 0; i < index; i++) if (css[i] === '\n') line += 1;
  return line;
}

const problems = [];

for (const root of ROOTS) {
  for (const file of walk(root)) {
    const css = withoutComments(readFileSync(join(ROOT, file), 'utf8'));

    // --- 1. braces balance -------------------------------------------------
    let depth = 0;
    let unmatchedAt = -1;
    for (let i = 0; i < css.length; i++) {
      if (css[i] === '{') depth += 1;
      else if (css[i] === '}') {
        depth -= 1;
        if (depth < 0 && unmatchedAt < 0) unmatchedAt = i;
      }
    }
    if (unmatchedAt >= 0) {
      problems.push(`${file}:${lineOf(css, unmatchedAt)}: a } with no matching {`);
      continue;
    }
    if (depth !== 0) {
      problems.push(`${file}: ${depth} unclosed block(s) — a rule is missing its }`);
      continue;
    }

    if (NESTING_ALLOWED.some((prefix) => file.startsWith(prefix))) continue;

    // --- 2. no selector inside a rule --------------------------------------
    // At-rule bodies (`@media`, `@supports`, `@keyframes`) contain rules by
    // definition, so their braces are neutralised before the walk. What is left is
    // one level: a rule body, which must contain only declarations.
    let scanDepth = 0;
    let inAtRuleAt = -1;
    let sawDeclaration = false;
    let blockStart = -1;

    for (let i = 0; i < css.length; i++) {
      const char = css[i];
      if (char === '{') {
        scanDepth += 1;
        if (scanDepth === 1) {
          const header = css.slice(css.lastIndexOf('}', i - 1) + 1, i);
          const isAtRule = /@[a-z-]+/i.test(header);
          if (isAtRule) {
            inAtRuleAt = scanDepth;
          } else {
            blockStart = i;
            sawDeclaration = false;
          }
        } else if (scanDepth === 2 && inAtRuleAt === 1) {
          blockStart = i;
          sawDeclaration = false;
        }
        continue;
      }
      if (char === '}') {
        if (scanDepth === inAtRuleAt) inAtRuleAt = -1;
        scanDepth -= 1;
        blockStart = -1;
        continue;
      }
      if (blockStart < 0) continue;

      if (char === ';') {
        sawDeclaration = true;
        continue;
      }
      // A `{` would have been caught above, so reaching a selector character after a
      // declaration means the enclosing rule never closed. `:` is excluded: it is a
      // declaration separator and a pseudo-class both.
      if (sawDeclaration && char === '.' && /\.[a-zA-Z-]/.test(css.slice(i, i + 2))) {
        const nextBrace = css.indexOf('{', i);
        const nextSemi = css.indexOf(';', i);
        // Only a selector if a `{` comes before the next `;` — otherwise it is a
        // decimal or part of a value.
        if (nextBrace > 0 && (nextSemi < 0 || nextBrace < nextSemi)) {
          problems.push(
            `${file}:${lineOf(css, i)}: a selector inside a rule that opened at line ` +
              `${lineOf(css, blockStart)} — that rule is probably missing its }`,
          );
          break;
        }
      }
    }
  }
}

if (problems.length > 0) {
  console.error('CSS block structure problems:\n');
  for (const problem of problems) console.error(`  ${problem}`);
  console.error(
    '\nA rule without its closing brace is still valid CSS: every selector after it\n' +
      'becomes a descendant of it and silently stops matching. Nothing else catches\n' +
      'this — not the build, not the colour checker, not jsdom.',
  );
  process.exit(1);
}

const count = ROOTS.flatMap((root) => [...walk(root)]).length;
console.log(`css blocks ok — ${count} stylesheets, braces balanced, no rule lost its }`);
