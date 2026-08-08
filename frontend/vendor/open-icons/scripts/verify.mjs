// Guards the two things that can silently break the sprite:
//   1. an unsubstituted `${u}` placeholder, which collapses every mask in the
//      set onto four shared ids — the defect this package was built to fix;
//   2. a duplicate id, which makes `url(#…)` resolve to whichever element the
//      parser saw first rather than the one inside the symbol being drawn.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const pkg = join(dirname(fileURLToPath(import.meta.url)), '..');
const svg = readFileSync(join(pkg, 'icons.svg'), 'utf8');
const names = JSON.parse(readFileSync(join(pkg, 'names.json'), 'utf8'));

const fail = [];

if (svg.includes('${u}')) fail.push('sprite contains an unsubstituted ${u} placeholder');

const ids = [...svg.matchAll(/\sid="([^"]+)"/g)].map((m) => m[1]);
const dupes = ids.filter((id, i) => ids.indexOf(id) !== i);
if (dupes.length) fail.push(`duplicate ids: ${[...new Set(dupes)].join(', ')}`);

const symbols = [...svg.matchAll(/<symbol id="([^"]+)"/g)].map((m) => m[1]);
if (symbols.length !== names.length) {
  fail.push(`sprite has ${symbols.length} symbols but names.json lists ${names.length}`);
}
const missing = names.filter((n) => !symbols.includes(n));
if (missing.length) fail.push(`names.json lists symbols absent from the sprite: ${missing.join(', ')}`);

// Every internal reference must point at an id that exists.
const refs = [...svg.matchAll(/url\(#([^)]+)\)/g)].map((m) => m[1]);
const dangling = [...new Set(refs.filter((r) => !ids.includes(r)))];
if (dangling.length) fail.push(`dangling references: ${dangling.join(', ')}`);

if (fail.length) {
  for (const f of fail) console.error(`verify: ${f}`);
  process.exit(1);
}
console.log(`verify: ${symbols.length} symbols, ${ids.length} ids, ${refs.length} references, all resolvable`);
