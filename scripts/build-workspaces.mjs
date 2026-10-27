#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { glob } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const BUILD_SCRIPT = 'build';
const DEPENDENCY_FIELDS = ['dependencies', 'devDependencies', 'optionalDependencies', 'peerDependencies'];
const CYCLE_EXIT_CODE = 2;

const manifest = (dir) => JSON.parse(readFileSync(path.join(dir, 'package.json'), 'utf8'));

async function workspaceDirs() {
  const { workspaces = [] } = manifest(REPO_ROOT);
  const dirs = [];
  for (const pattern of workspaces) {
    for await (const match of glob(`${pattern}/package.json`, { cwd: REPO_ROOT })) {
      dirs.push(path.join(REPO_ROOT, path.dirname(match)));
    }
  }
  return dirs.sort();
}

function dependencyGraph(dirs) {
  const packages = new Map();
  for (const dir of dirs) {
    const pkg = manifest(dir);
    packages.set(pkg.name, { pkg, dependencies: new Set() });
  }
  for (const { pkg, dependencies } of packages.values()) {
    for (const field of DEPENDENCY_FIELDS) {
      for (const name of Object.keys(pkg[field] ?? {})) {
        if (packages.has(name) && name !== pkg.name) dependencies.add(name);
      }
    }
  }
  return packages;
}

function buildOrder(packages) {
  const order = [];
  const pending = new Set(packages.keys());
  while (pending.size > 0) {
    const ready = [...pending]
      .filter((name) => [...packages.get(name).dependencies].every((dep) => !pending.has(dep)))
      .sort();
    if (ready.length === 0) {
      throw new Error(`Workspace dependency cycle among: ${[...pending].sort().join(', ')}`);
    }
    for (const name of ready) {
      order.push(name);
      pending.delete(name);
    }
  }
  return order;
}

async function main() {
  const packages = dependencyGraph(await workspaceDirs());
  for (const name of buildOrder(packages)) {
    if (!packages.get(name).pkg.scripts?.[BUILD_SCRIPT]) continue;
    const { status } = spawnSync('npm', ['run', BUILD_SCRIPT, '--workspace', name], {
      cwd: REPO_ROOT,
      stdio: 'inherit',
    });
    if (status !== 0) {
      process.exitCode = status ?? CYCLE_EXIT_CODE;
      return;
    }
  }
}

await main();
