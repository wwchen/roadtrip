#!/usr/bin/env node
// The Neon branch a pull request's preview reads from: `pr-<n>`, a child of the
// branch `seed`, created when the pull request opens and deleted when it
// closes. `ensure` prints the preview's DATABASE_URL (pooled, as the read-only
// roadtrip_web role) and nothing else, so a workflow can capture it without it
// reaching a log.
//
// Usage:
//   node scripts/preview-branch.mjs ensure --pr <n>
//   node scripts/preview-branch.mjs remove --pr <n>

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { createNeonClient } from './neon.mjs';
import { PREVIEW_ROLE, SEED_BRANCH, SEED_DATABASE } from './seed-branches.mjs';

export const PREVIEW_BRANCH_PREFIX = 'pr-';
export const PREVIEW_BRANCH_BUDGET = 8;

const PREVIEW_CONNECTION_PARAMS = { connect_timeout: '10', idle_timeout: '20' };
const PR_BRANCH_PATTERN = new RegExp(`^${PREVIEW_BRANCH_PREFIX}\\d+$`);

export function previewBranchName(pr) {
  if (!Number.isSafeInteger(pr) || pr < 1) throw new Error(`a pull request number must be a positive integer, got ${pr}`);
  return `${PREVIEW_BRANCH_PREFIX}${pr}`;
}

export async function ensurePreviewBranch(neon, pr) {
  const name = previewBranchName(pr);
  const branches = await neon.listBranches();
  if (branches.some((branch) => branch.name === name)) return { name, created: false };
  const open = branches.filter((branch) => PR_BRANCH_PATTERN.test(branch.name));
  if (open.length >= PREVIEW_BRANCH_BUDGET) {
    throw new Error(
      `${open.length} pull request branches already exist (${open.map((branch) => branch.name).join(', ')}), ` +
        `the budget is ${PREVIEW_BRANCH_BUDGET}; close a pull request to free one`,
    );
  }
  await neon.createBranch(name, SEED_BRANCH);
  return { name, created: true };
}

export function removePreviewBranch(neon, pr) {
  return neon.deleteBranch(previewBranchName(pr));
}

export async function previewDatabaseUrl(neon, pr) {
  const uri = await neon.connectionUri({
    branchName: previewBranchName(pr),
    database: SEED_DATABASE,
    role: PREVIEW_ROLE,
    pooled: true,
  });
  const url = new URL(uri);
  for (const [key, value] of Object.entries(PREVIEW_CONNECTION_PARAMS)) url.searchParams.set(key, value);
  return url.toString();
}

async function main() {
  const { positionals, values } = parseArgs({
    allowPositionals: true,
    options: { pr: { type: 'string' } },
    strict: true,
  });
  const [command] = positionals;
  const pr = Number(values.pr);
  const neon = createNeonClient({ apiKey: process.env.NEON_API_KEY, projectId: process.env.NEON_PROJECT_ID });
  if (command === 'ensure') {
    const { name, created } = await ensurePreviewBranch(neon, pr);
    console.error(`${created ? 'created' : 'reusing'} Neon branch ${name}`);
    process.stdout.write(await previewDatabaseUrl(neon, pr));
  } else if (command === 'remove') {
    const removed = await removePreviewBranch(neon, pr);
    console.error(removed ? `deleted Neon branch ${previewBranchName(pr)}` : `no Neon branch ${previewBranchName(pr)}`);
  } else {
    console.error('usage: preview-branch.mjs ensure|remove --pr <n>');
    process.exitCode = 2;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
