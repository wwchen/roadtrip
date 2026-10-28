#!/usr/bin/env node

import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB_DIRECTORY = 'apps/web';
const WEB_SOURCE_DIRECTORY = 'apps/web/src';
const SERVER_DIRECTORY = 'apps/web/src/server';
const ALIAS_PREFIX = '@/';
const FORBIDDEN_PACKAGE = '@roadtrip/db';
const FORBIDDEN_PACKAGE_DIRECTORY = 'packages/db';
const SOURCE_EXTENSION = /\.[cm]?[jt]sx?$/;
const SKIPPED_DIRECTORIES = new Set(['node_modules', 'dist', 'storybook-static', '.next']);
const MODULE_SPECIFIER =
  /(?:from\s+|import\s*(?:\(\s*)?|require\s*\(\s*)['"]([^'"]+)['"]/g;
const CLIENT_DIRECTIVE = /^(?:'use client'|"use client")(?:\s*;|\s*$|[ \t]*(?:\r?\n|\/\/|\/\*))/;
const PUBLIC_ENV_NAME = /NEXT_PUBLIC_\w*/g;
const RESOLVABLE_SUFFIXES = ['.ts', '.tsx', '.js', '.jsx', '.mts', '.cts', '.mjs', '.cjs'];
const COMPILED_SUFFIX_SOURCES = new Map([
  ['.js', ['.ts', '.tsx']],
  ['.jsx', ['.tsx']],
  ['.mjs', ['.mts']],
  ['.cjs', ['.cts']],
]);
const BYTE_ORDER_MARK = /^﻿/;
const LEADING_LINE_COMMENT = /^\/\/[^\n]*(?:\n|$)/;
const LEADING_BLOCK_COMMENT = /^\/\*[\s\S]*?\*\//;

function isInside(directory, target) {
  const relative = path.relative(directory, target);
  return !relative.startsWith('..') && !path.isAbsolute(relative);
}

function lineOf(source, index) {
  return source.slice(0, index).split('\n').length;
}

function declaresClientDirective(source) {
  let rest = source.replace(BYTE_ORDER_MARK, '').trimStart();
  for (;;) {
    const comment = rest.match(LEADING_LINE_COMMENT) ?? rest.match(LEADING_BLOCK_COMMENT);
    if (!comment) break;
    rest = rest.slice(comment[0].length).trimStart();
  }
  return CLIENT_DIRECTIVE.test(rest);
}

function specifierTarget(specifier, file, repoRoot) {
  if (specifier.startsWith(ALIAS_PREFIX)) {
    return path.join(repoRoot, WEB_SOURCE_DIRECTORY, specifier.slice(ALIAS_PREFIX.length));
  }
  if (specifier.startsWith('.')) return path.resolve(path.dirname(file), specifier);
  return null;
}

function reachesForbiddenPackage(specifier, target, repoRoot) {
  if (specifier === FORBIDDEN_PACKAGE || specifier.startsWith(`${FORBIDDEN_PACKAGE}/`)) return true;
  return target !== null && isInside(path.join(repoRoot, FORBIDDEN_PACKAGE_DIRECTORY), target);
}

function candidatePaths(target) {
  const candidates = [target];
  for (const suffix of RESOLVABLE_SUFFIXES) candidates.push(`${target}${suffix}`);
  const compiled = path.extname(target);
  for (const source of COMPILED_SUFFIX_SOURCES.get(compiled) ?? []) {
    candidates.push(`${target.slice(0, -compiled.length)}${source}`);
  }
  for (const suffix of RESOLVABLE_SUFFIXES) candidates.push(path.join(target, `index${suffix}`));
  return candidates;
}

function resolveSource(target, known) {
  return candidatePaths(target).find((candidate) => known.has(candidate)) ?? null;
}

async function walk(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(
    entries.map(async (entry) => {
      const target = path.join(directory, entry.name);
      if (entry.isDirectory()) return SKIPPED_DIRECTORIES.has(entry.name) ? [] : walk(target);
      return [target];
    }),
  );
  return nested.flat().sort();
}

async function readModules(files, repoRoot) {
  const sources = files.filter((file) => SOURCE_EXTENSION.test(file));
  const known = new Set(sources);
  const modules = new Map();
  for (const file of sources) {
    const source = await readFile(file, 'utf8');
    const imports = [...source.matchAll(MODULE_SPECIFIER)].map((match) => {
      const specifier = match[1];
      const target = specifierTarget(specifier, file, repoRoot);
      return {
        specifier,
        line: lineOf(source, match.index),
        forbiddenPackage: reachesForbiddenPackage(specifier, target, repoRoot),
        server: target !== null && isInside(path.join(repoRoot, SERVER_DIRECTORY), target),
        resolved: target === null ? null : resolveSource(target, known),
      };
    });
    modules.set(file, { client: declaresClientDirective(source), imports });
  }
  return modules;
}

function clientClosure(modules) {
  const entryOf = new Map();
  const queue = [];
  for (const [file, module] of modules) {
    if (!module.client) continue;
    entryOf.set(file, file);
    queue.push(file);
  }
  for (let index = 0; index < queue.length; index += 1) {
    const file = queue[index];
    for (const edge of modules.get(file).imports) {
      if (edge.server || edge.resolved === null || entryOf.has(edge.resolved)) continue;
      entryOf.set(edge.resolved, entryOf.get(file));
      queue.push(edge.resolved);
    }
  }
  return entryOf;
}

export async function findClientBoundaryViolations(repoRoot) {
  const files = await walk(path.join(repoRoot, WEB_DIRECTORY));
  const modules = await readModules(files, repoRoot);
  const violations = [];

  for (const [file, entry] of clientClosure(modules)) {
    for (const edge of modules.get(file).imports) {
      if (!edge.forbiddenPackage && !edge.server) continue;
      violations.push(
        `${path.relative(repoRoot, file)}:${edge.line}: imports ${edge.specifier} (reached from client component ${path.relative(repoRoot, entry)})`,
      );
    }
  }

  for (const file of files) {
    const source = await readFile(file, 'utf8');
    for (const match of source.matchAll(PUBLIC_ENV_NAME)) {
      violations.push(`${path.relative(repoRoot, file)}:${lineOf(source, match.index)}: references ${match[0]}`);
    }
  }
  return violations;
}

async function main() {
  const violations = await findClientBoundaryViolations(REPO_ROOT);
  if (violations.length > 0) {
    console.error(
      `${WEB_DIRECTORY}/ must keep ${FORBIDDEN_PACKAGE} and ${SERVER_DIRECTORY}/ out of client components, and must not use NEXT_PUBLIC_ names: they are inlined into the bundle every visitor downloads. Move the server work behind a server component or route handler and pass plain props down:\n`,
    );
    console.error(violations.join('\n'));
    process.exitCode = 1;
    return;
  }
  console.log(`Client boundary OK: no client component reaches ${FORBIDDEN_PACKAGE} or ${SERVER_DIRECTORY}/, and no NEXT_PUBLIC_ names`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
