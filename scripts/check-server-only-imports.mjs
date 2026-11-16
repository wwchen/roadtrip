#!/usr/bin/env node

import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { findModuleSpecifiers, isPackageSpecifier, SKIPPED_DIRECTORIES as SHARED_SKIPPED_DIRECTORIES, SOURCE_EXTENSION } from './module-specifiers.mjs';

export const REPO_ROOT = fileURLToPath(new URL('..', import.meta.url));
const WEB_DIRECTORY = 'apps/web';
const WEB_SOURCE_DIRECTORY = 'apps/web/src';
const SERVER_DIRECTORY = 'apps/web/src/server';
const ALIAS_PREFIX = '@/';
const FORBIDDEN_PACKAGE = '@roadtrip/db';
const FORBIDDEN_PACKAGE_DIRECTORY = 'packages/db';
const SKIPPED_DIRECTORIES = new Set([...SHARED_SKIPPED_DIRECTORIES, '.next']);
const PROSE_EXTENSION = /\.mdx?$/;
const CLIENT_DIRECTIVE = /^(?:'use client'|"use client")(?:\s*;|\s*$|[ \t]*(?:\r?\n|\/\/|\/\*))/;
const PUBLIC_ENV_NAME = /NEXT_PUBLIC_\w*/g;
const RESOLVABLE_SUFFIXES = ['.ts', '.tsx', '.js', '.jsx', '.mts', '.cts', '.mjs', '.cjs'];
const COMPILED_SUFFIX_SOURCES = new Map([
  ['.js', ['.ts', '.tsx']],
  ['.jsx', ['.tsx']],
  ['.mjs', ['.mts']],
  ['.cjs', ['.cts']],
]);
const ENV_CONFIG_FILE = 'apps/web/src/server/config.ts';
const INSTRUMENTATION_FILE = 'apps/web/src/instrumentation.ts';
const RUNTIME_READ = /\bprocess\.env\.NEXT_RUNTIME\b/g;
const TEST_FILE = /\.test\.[cm]?[jt]sx?$/;
const PROCESS_MODULE = String.raw`['"\`](?:node:)?process['"\`]`;
const ENV_READ_FORMS = {
  member: String.raw`\bprocess\s*!?\s*\??\.\s*env\b`,
  bracket: String.raw`\bprocess\s*(?:\?\.)?\s*\[`,
  cast: String.raw`\(\s*process\b[^()]*\)\s*\??\.\s*env\b`,
  alias: String.raw`=\s*(?:globalThis\s*\.\s*)?process\s*(?:[;,]|$|as\b|satisfies\b)`,
  importFrom: String.raw`\bfrom\s*${PROCESS_MODULE}`,
  importCall: String.raw`\b(?:require|import)\s*\(?\s*${PROCESS_MODULE}`,
  globalLookup: String.raw`\[\s*['"\`]process['"\`]\s*\]`,
};
const ENV_READ = new RegExp(Object.values(ENV_READ_FORMS).join('|'), 'gm');
const ENV_READ_MESSAGE = 'reads process.env';
const PARENT_SEGMENT = '..';
const DIRECTORY_ENTRY_SUFFIX = '/';
const PATH_ENTRY_PREFIXES = ['server/', 'app/'];
const ENTRY_LIST_SEPARATOR = ', ';
const LAYER_HEADING = 'Layer rules violated:';
const ENV_HEADING = `process.env may be read only in ${ENV_CONFIG_FILE}:`;
const LAYERS_OK_MESSAGE = 'Layer rules OK: no import breaks a rule, and process.env is read only in server/config.ts';
const LEADING_LINE_COMMENT = /^\/\/[^\n]*(?:\n|$)/;
const LEADING_BLOCK_COMMENT = /^\/\*[\s\S]*?\*\//;

const MODEL_FILE = /^server\/service\/[^/]+\/(?:model|ports)\.ts$/;
const ROUTE_FILE = /^app\/(?:.*\/)?route\.tsx?$/;
const CONTROLLER_FILE = /Controller\.ts$/;
const SERVICE_CONTROLLER_FILE = /^server\/service\/[^/]+\/[^/]*Controller\.ts$/;
const GENERATED_CONTRACT = /(?:^|\/)frontend\/src\/api\/generated\//;
const DATABASE = ['server/db', 'server/appDb', 'drizzle-orm', '@roadtrip/db'];
const SESSION_LOOKUPS = ['server/auth/currentUser', 'server/auth/neonAuth', 'server/auth/signInConfigured', 'server/auth/authActions'];
const LAYERED_SERVICE_AREAS = ['server/service/booking/', 'server/service/settings/'];
const ROUTES_NOT_YET_LAYERED = [
  'app/api/availability/[...path]/route.ts',
  'app/api/build-info/route.ts',
  'app/api/docs/[[...path]]/route.ts',
  'app/api/geocode/route.ts',
  'app/api/health/route.ts',
  'app/api/pois/[id]/campsites/availability/refresh/route.ts',
  'app/api/pois/availability/stays/route.ts',
];

export const LAYER_RULES = [
  { name: 'model', from: [MODEL_FILE], only: ['@roadtrip/domain', MODEL_FILE] },
  { name: 'relay mapper', from: ['server/mapper/relay/'], forbid: ['server/repo/', 'server/client/', 'server/wiring/', 'server/config', ...DATABASE] },
  { name: 'client', from: ['server/client/'], forbid: ['server/repo/', CONTROLLER_FILE, 'server/mapper/http/', 'server/wiring/', ...DATABASE] },
  { name: 'http mapper', from: ['server/mapper/http/'], forbid: ['server/repo/', 'server/client/', ...DATABASE, 'server/wiring/', 'server/config', ...SESSION_LOOKUPS] },
  { name: 'row mapper', from: ['server/mapper/row/'], forbid: ['server/contract', GENERATED_CONTRACT, 'server/config', 'server/wiring/'] },
  { name: 'repository', from: ['server/repo/'], forbid: ['server/contract', GENERATED_CONTRACT, CONTROLLER_FILE, 'server/mapper/http/', 'server/client/', 'server/wiring/', 'server/config'] },
  {
    name: 'controller',
    from: [...LAYERED_SERVICE_AREAS, SERVICE_CONTROLLER_FILE],
    only: ['@roadtrip/domain', '@photostructure/tz-lookup', ...LAYERED_SERVICE_AREAS, MODEL_FILE],
    except: ['server/service/booking/campgroundCta.ts'],
  },
  { name: 'availability matrix', from: ['server/service/availability/availabilityMatrixService.ts'], forbid: ['server/repo/bookingCredentialsRepo'] },
  { name: 'route', from: [ROUTE_FILE], forbid: ['server/repo/', 'server/client/', ...DATABASE, 'server/config'], except: ROUTES_NOT_YET_LAYERED },
];

function isInside(directory, target) {
  const relative = path.relative(directory, target);
  return !relative.startsWith('..') && !path.isAbsolute(relative);
}

function lineOf(source, index) {
  return source.slice(0, index).split('\n').length;
}

function declaresClientDirective(source) {
  let rest = source.trimStart();
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
  if (isPackageSpecifier(specifier, FORBIDDEN_PACKAGE)) return true;
  return target !== null && isInside(path.join(repoRoot, FORBIDDEN_PACKAGE_DIRECTORY), target);
}

export function candidatePaths(target) {
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
    const imports = findModuleSpecifiers(source).map(({ specifier, line }) => {
      const target = specifierTarget(specifier, file, repoRoot);
      return {
        specifier,
        line,
        target,
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

  for (const file of files.filter((candidate) => !PROSE_EXTENSION.test(candidate))) {
    const source = await readFile(file, 'utf8');
    for (const match of source.matchAll(PUBLIC_ENV_NAME)) {
      violations.push(`${path.relative(repoRoot, file)}:${lineOf(source, match.index)}: references ${match[0]}`);
    }
  }
  return violations;
}

function sourceRelative(repoRoot, file) {
  return path.relative(path.join(repoRoot, WEB_SOURCE_DIRECTORY), file).split(path.sep).join('/');
}

function withoutExtension(modulePath) {
  return modulePath.replace(SOURCE_EXTENSION, '');
}

function isPackageEntry(entry) {
  return typeof entry === 'string' && !PATH_ENTRY_PREFIXES.some((prefix) => entry.startsWith(prefix));
}

function matchesPathEntry(entry, modulePath) {
  if (entry instanceof RegExp) return entry.test(modulePath);
  if (entry.endsWith(DIRECTORY_ENTRY_SUFFIX)) return modulePath.startsWith(entry);
  return withoutExtension(modulePath) === withoutExtension(entry);
}

function importMatchesEntry(entry, edge, repoRoot) {
  if (isPackageEntry(entry)) return isPackageSpecifier(edge.specifier, entry);
  if (edge.target === null) return false;
  return matchesPathEntry(entry, sourceRelative(repoRoot, edge.resolved ?? edge.target));
}

function describeEntries(entries) {
  return entries.map(String).join(ENTRY_LIST_SEPARATOR);
}

function layerViolation(rule, edge, entries, repoRoot, file) {
  const where = `${path.relative(repoRoot, file)}:${edge.line}: imports ${edge.specifier}`;
  if (rule.only) return `${where} (${rule.name} may import only ${describeEntries(entries)})`;
  const entry = entries.find((candidate) => importMatchesEntry(candidate, edge, repoRoot));
  return `${where} (${rule.name} may not import ${String(entry)})`;
}

export async function findLayerViolations(repoRoot, rules = LAYER_RULES) {
  const files = await walk(path.join(repoRoot, WEB_DIRECTORY));
  const modules = await readModules(files, repoRoot);
  const violations = [];

  for (const [file, module] of modules) {
    const modulePath = sourceRelative(repoRoot, file);
    if (modulePath.startsWith(PARENT_SEGMENT) || TEST_FILE.test(file)) continue;
    for (const rule of rules) {
      if (!rule.from.some((entry) => matchesPathEntry(entry, modulePath))) continue;
      if (rule.except?.some((entry) => matchesPathEntry(entry, modulePath))) continue;
      const entries = rule.only ?? rule.forbid;
      for (const edge of module.imports) {
        const matched = entries.some((entry) => importMatchesEntry(entry, edge, repoRoot));
        if (matched === Boolean(rule.only)) continue;
        violations.push(layerViolation(rule, edge, entries, repoRoot, file));
      }
    }
  }
  return violations;
}

export async function findEnvReadViolations(repoRoot) {
  const files = await walk(path.join(repoRoot, WEB_SOURCE_DIRECTORY));
  const violations = [];
  for (const file of files) {
    if (!SOURCE_EXTENSION.test(file) || TEST_FILE.test(file)) continue;
    const relative = path.relative(repoRoot, file).split(path.sep).join('/');
    if (relative === ENV_CONFIG_FILE) continue;
    const read = await readFile(file, 'utf8');
    const source = relative === INSTRUMENTATION_FILE ? read.replace(RUNTIME_READ, (match) => ' '.repeat(match.length)) : read;
    for (const match of source.matchAll(ENV_READ)) {
      violations.push(`${path.relative(repoRoot, file)}:${lineOf(source, match.index)}: ${ENV_READ_MESSAGE}`);
    }
  }
  return violations;
}

async function main() {
  const violations = await findClientBoundaryViolations(REPO_ROOT);
  const layerViolations = await findLayerViolations(REPO_ROOT);
  const envViolations = await findEnvReadViolations(REPO_ROOT);
  if (violations.length > 0) {
    console.error(
      `${WEB_DIRECTORY}/ must keep ${FORBIDDEN_PACKAGE} and ${SERVER_DIRECTORY}/ out of client components, and must not use NEXT_PUBLIC_ names: they are inlined into the bundle every visitor downloads. Move the server work behind a server component or route handler and pass plain props down. Only literal specifiers are checked: import(someVariable), require(someVariable) and template specifiers that begin with an interpolation cannot be resolved, so do not rely on this gate for them.\n`,
    );
    console.error(violations.join('\n'));
    process.exitCode = 1;
  }
  if (layerViolations.length > 0) {
    console.error(`${LAYER_HEADING}\n${layerViolations.join('\n')}`);
    process.exitCode = 1;
  }
  if (envViolations.length > 0) {
    console.error(`${ENV_HEADING}\n${envViolations.join('\n')}`);
    process.exitCode = 1;
  }
  if (violations.length + layerViolations.length + envViolations.length > 0) return;
  console.log(`Client boundary OK: no client component reaches ${FORBIDDEN_PACKAGE} or ${SERVER_DIRECTORY}/, and no NEXT_PUBLIC_ names`);
  console.log(LAYERS_OK_MESSAGE);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
