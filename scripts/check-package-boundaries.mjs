#!/usr/bin/env node

import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = fileURLToPath(new URL('..', import.meta.url));
const FRONTEND_DIRECTORY = 'frontend';
const FORBIDDEN_PACKAGE = '@roadtrip/db';
const FORBIDDEN_PACKAGE_DIRECTORY = 'packages/db';
const SOURCE_EXTENSION = /\.[cm]?[jt]sx?$/;
const SKIPPED_DIRECTORIES = new Set(['node_modules', 'dist', 'storybook-static']);
const DEPENDENCY_FIELDS = ['dependencies', 'devDependencies', 'optionalDependencies', 'peerDependencies'];
const MODULE_SPECIFIER =
  /(?:from\s+|import\s*(?:\(\s*)?|require\s*\(\s*)['"]([^'"]+)['"]/g;

function reachesForbiddenPackage(specifier, file, repoRoot) {
  if (specifier === FORBIDDEN_PACKAGE || specifier.startsWith(`${FORBIDDEN_PACKAGE}/`)) return true;
  if (!specifier.startsWith('.')) return false;
  const relative = path.relative(
    path.join(repoRoot, FORBIDDEN_PACKAGE_DIRECTORY),
    path.resolve(path.dirname(file), specifier),
  );
  return !relative.startsWith('..') && !path.isAbsolute(relative);
}

async function sourceFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(
    entries.map(async (entry) => {
      const target = path.join(directory, entry.name);
      if (entry.isDirectory()) return SKIPPED_DIRECTORIES.has(entry.name) ? [] : sourceFiles(target);
      return SOURCE_EXTENSION.test(entry.name) ? [target] : [];
    }),
  );
  return nested.flat();
}

export async function findPackageBoundaryViolations(repoRoot) {
  const frontendRoot = path.join(repoRoot, FRONTEND_DIRECTORY);
  const violations = [];

  const manifestPath = path.join(frontendRoot, 'package.json');
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
  for (const field of DEPENDENCY_FIELDS) {
    if (Object.hasOwn(manifest[field] ?? {}, FORBIDDEN_PACKAGE)) {
      violations.push(`${path.relative(repoRoot, manifestPath)}: ${field} declares ${FORBIDDEN_PACKAGE}`);
    }
  }

  for (const file of await sourceFiles(frontendRoot)) {
    const source = await readFile(file, 'utf8');
    for (const match of source.matchAll(MODULE_SPECIFIER)) {
      if (!reachesForbiddenPackage(match[1], file, repoRoot)) continue;
      const line = source.slice(0, match.index).split('\n').length;
      violations.push(`${path.relative(repoRoot, file)}:${line}: imports ${match[1]}`);
    }
  }
  return violations;
}

async function main() {
  const violations = await findPackageBoundaryViolations(REPO_ROOT);
  if (violations.length > 0) {
    console.error(
      `${FRONTEND_DIRECTORY}/ must not depend on ${FORBIDDEN_PACKAGE}: it carries a Postgres driver. Import shared vocabulary from @roadtrip/domain instead:\n`,
    );
    console.error(violations.join('\n'));
    process.exitCode = 1;
    return;
  }
  console.log('Package boundaries OK');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
