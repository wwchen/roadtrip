#!/usr/bin/env node

import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { findModuleSpecifiers, isPackageSpecifier, SKIPPED_DIRECTORIES, SOURCE_EXTENSION } from './module-specifiers.mjs';

const REPO_ROOT = fileURLToPath(new URL('..', import.meta.url));
const FRONTEND_DIRECTORY = 'frontend';
const FORBIDDEN_PACKAGE = '@roadtrip/db';
const FORBIDDEN_PACKAGE_DIRECTORY = 'packages/db';
const DEPENDENCY_FIELDS = ['dependencies', 'devDependencies', 'optionalDependencies', 'peerDependencies'];

function reachesForbiddenPackage(specifier, file, repoRoot) {
  if (isPackageSpecifier(specifier, FORBIDDEN_PACKAGE)) return true;
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
    for (const { specifier, line } of findModuleSpecifiers(source)) {
      if (!reachesForbiddenPackage(specifier, file, repoRoot)) continue;
      violations.push(`${path.relative(repoRoot, file)}:${line}: imports ${specifier}`);
    }
  }
  return violations;
}

async function main() {
  const violations = await findPackageBoundaryViolations(REPO_ROOT);
  if (violations.length > 0) {
    console.error(
      `${FRONTEND_DIRECTORY}/ must not depend on ${FORBIDDEN_PACKAGE}: it carries a Postgres driver. Import shared vocabulary from @roadtrip/domain instead. Only literal specifiers are checked: import(someVariable), require(someVariable) and template specifiers that begin with an interpolation cannot be seen, so do not rely on this gate for them.\n`,
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
