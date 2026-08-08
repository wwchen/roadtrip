// Bundles smoke-test.entry.jsx (JSX needs a transform Node can't do natively)
// and runs it — server-rendered checks that every wrapper produces sane
// markup. See browser-test.mjs for the real-DOM half of this package.
import esbuild from 'esbuild';
import { spawnSync } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
// Built inside the package tree (not the system tmpdir) so Node's module
// resolution walks up to the workspace's real node_modules for the
// externalized packages below. CommonJS output, run via `node` directly
// (rather than a dynamic `import()`), sidesteps ESM/CJS interop producing a
// second react module instance — which reads as an "Invalid hook call".
const work = await mkdtemp(join(HERE, '.tmp-'));
const outfile = join(work, 'entry.cjs');

await esbuild.build({
  entryPoints: [join(HERE, 'smoke-test.entry.jsx')],
  bundle: true,
  format: 'cjs',
  platform: 'node',
  packages: 'external',
  outfile,
  logLevel: 'warning',
});

try {
  const result = spawnSync(process.execPath, [outfile], { stdio: 'inherit' });
  if (result.status !== 0) process.exit(result.status ?? 1);
} finally {
  await rm(work, { recursive: true, force: true });
}
