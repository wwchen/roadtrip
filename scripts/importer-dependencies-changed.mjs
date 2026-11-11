#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export const IMPORTER_WORKSPACES = ['@roadtrip/worker', '@roadtrip/db'];

const RUNTIME_DEPENDENCY_FIELDS = ['dependencies', 'optionalDependencies', 'peerDependencies'];
const NODE_MODULES = 'node_modules/';
const NESTED_NODE_MODULES = `/${NODE_MODULES}`;

const MISSING_WORKSPACE = 'missing workspace';

function workspaceLocation(packages, name) {
  return Object.keys(packages).find(
    (key) => key !== '' && !key.includes(NODE_MODULES) && packages[key].name === name,
  );
}

function runtimeDependencies(entry) {
  const optionalPeers = entry.peerDependenciesMeta ?? {};
  return RUNTIME_DEPENDENCY_FIELDS.flatMap((field) =>
    Object.keys(entry[field] ?? {}).filter(
      (name) => field !== 'peerDependencies' || optionalPeers[name]?.optional !== true,
    ),
  );
}

function parentOf(location) {
  const nested = location.lastIndexOf(NESTED_NODE_MODULES);
  return nested === -1 ? '' : location.slice(0, nested);
}

function resolve(packages, from, name) {
  let dir = from;
  for (;;) {
    const candidate = `${dir === '' ? '' : `${dir}/`}${NODE_MODULES}${name}`;
    if (packages[candidate] !== undefined) return candidate;
    if (dir === '') return undefined;
    dir = parentOf(dir);
  }
}

function fingerprint(entry) {
  const { version, resolved, integrity, link } = entry;
  const declared = Object.fromEntries(RUNTIME_DEPENDENCY_FIELDS.map((field) => [field, entry[field] ?? {}]));
  return JSON.stringify({ version, resolved, integrity, link, declared });
}

export function importerClosure(lock) {
  const packages = lock.packages ?? {};
  const seen = new Map();
  const queue = [];
  for (const name of IMPORTER_WORKSPACES) {
    const location = workspaceLocation(packages, name);
    if (location === undefined) seen.set(name, MISSING_WORKSPACE);
    else queue.push(location);
  }
  while (queue.length > 0) {
    const location = queue.pop();
    if (seen.has(location)) continue;
    const entry = packages[location];
    seen.set(location, fingerprint(entry));
    if (entry.link) {
      queue.push(entry.resolved);
      continue;
    }
    for (const name of runtimeDependencies(entry)) {
      const target = resolve(packages, location, name);
      if (target !== undefined) queue.push(target);
    }
  }
  return seen;
}

export function importerDependenciesChanged(baseLock, headLock) {
  const base = importerClosure(baseLock);
  const head = importerClosure(headLock);
  if (base.size !== head.size) return true;
  for (const [location, print] of head) {
    if (base.get(location) !== print) return true;
  }
  return false;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [basePath, headPath] = process.argv.slice(2);
  if (basePath === undefined || headPath === undefined) {
    console.error('usage: importer-dependencies-changed.mjs <base package-lock.json> <head package-lock.json>');
    process.exit(2);
  }
  const read = (file) => JSON.parse(readFileSync(file, 'utf8'));
  console.log(String(importerDependenciesChanged(read(basePath), read(headPath))));
}
