#!/usr/bin/env node

import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = fileURLToPath(new URL('..', import.meta.url));
const SOURCE_EXTENSION = /\.[cm]?[jt]sx?$/;
const TEST_FILE = /\.(?:test|spec)\.[cm]?[jt]sx?$/;
const IMPORT_SPECIFIER = /(?:from\s+|import\s*(?:\(\s*)?)['"]([^'"]+)['"]/g;

async function sourceFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(
    entries.map(async (entry) => {
      const target = path.join(directory, entry.name);
      if (entry.isDirectory()) return sourceFiles(target);
      return SOURCE_EXTENSION.test(entry.name) && !TEST_FILE.test(entry.name) ? [target] : [];
    }),
  );
  return nested.flat();
}

function sourcePathFor(specifier, file, sourceRoot) {
  if (specifier.startsWith('@/')) return path.join(sourceRoot, specifier.slice(2));
  if (specifier.startsWith('.')) return path.resolve(path.dirname(file), specifier);
  return null;
}

function areaOf(target, sourceRoot) {
  const relative = path.relative(sourceRoot, target);
  if (relative.startsWith('..') || path.isAbsolute(relative)) return null;
  const [area, name] = relative.split(path.sep);
  return area === 'features' || area === 'domain' ? { area, name } : null;
}

export async function findBoundaryViolations(repoRoot) {
  const sourceRoot = path.join(repoRoot, 'frontend/src');
  const roots = ['features', 'domain'].map((area) => path.join(sourceRoot, area));
  const files = (await Promise.all(roots.map(sourceFiles))).flat();
  const violations = [];

  for (const file of files) {
    const sourceArea = areaOf(file, sourceRoot);
    if (!sourceArea) continue;
    const source = await readFile(file, 'utf8');
    for (const match of source.matchAll(IMPORT_SPECIFIER)) {
      const targetPath = sourcePathFor(match[1], file, sourceRoot);
      const targetArea = targetPath == null ? null : areaOf(targetPath, sourceRoot);
      const crossesFeatures =
        sourceArea.area === 'features' &&
        targetArea?.area === 'features' &&
        targetArea.name !== sourceArea.name;
      const leaksFeatureIntoDomain =
        sourceArea.area === 'domain' && targetArea?.area === 'features';
      if (!crossesFeatures && !leaksFeatureIntoDomain) continue;
      const line = source.slice(0, match.index).split('\n').length;
      violations.push(
        `${path.relative(repoRoot, file)}:${line}: ${sourceArea.area}/${sourceArea.name} imports ${targetArea.area}/${targetArea.name}`,
      );
    }
  }
  return violations;
}

async function main() {
  const violations = await findBoundaryViolations(REPO_ROOT);
  if (violations.length > 0) {
    console.error('Feature boundaries violated. Compose features in pages or extract shared code:\n');
    console.error(violations.join('\n'));
    process.exitCode = 1;
    return;
  }
  console.log('Feature boundaries OK');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
