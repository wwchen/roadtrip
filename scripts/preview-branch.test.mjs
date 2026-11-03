import assert from 'node:assert/strict';
import test from 'node:test';
import {
  ensurePreviewBranch,
  PREVIEW_BRANCH_BUDGET,
  previewBranchName,
  previewDatabaseUrl,
  removePreviewBranch,
} from './preview-branch.mjs';

const PR = 42;
const WEB_URI = 'postgresql://roadtrip_web:pw@ep-x-pooler.neon.test/roadtrip?sslmode=require';

function fakeNeon(names) {
  const branches = names.map((name, index) => ({ id: `br-${index}`, name }));
  const calls = [];
  return {
    calls,
    branches,
    listBranches: async () => branches.map((branch) => ({ ...branch })),
    findBranch: async (name) => branches.find((branch) => branch.name === name),
    createBranch: async (name, parentName) => {
      calls.push({ op: 'create', name, parentName });
      const branch = { id: `br-${branches.length}`, name };
      branches.push(branch);
      return branch;
    },
    deleteBranch: async (name) => {
      calls.push({ op: 'delete', name });
      const index = branches.findIndex((branch) => branch.name === name);
      if (index === -1) return false;
      branches.splice(index, 1);
      return true;
    },
    connectionUri: async (request) => {
      calls.push({ op: 'uri', ...request });
      return WEB_URI;
    },
  };
}

const prBranches = (count) => Array.from({ length: count }, (_, index) => `pr-${index + 1000}`);

test('a pull request number names its branch, and nothing else does', () => {
  assert.equal(previewBranchName(PR), 'pr-42');
  for (const bad of [0, -1, 1.5, Number.NaN, '42']) {
    assert.throws(() => previewBranchName(bad), /pull request number must be a positive integer/);
  }
});

test('a new pull request gets a branch of seed', async () => {
  const neon = fakeNeon(['main', 'seed']);
  assert.deepEqual(await ensurePreviewBranch(neon, PR), { name: 'pr-42', created: true });
  assert.deepEqual(neon.calls, [{ op: 'create', name: 'pr-42', parentName: 'seed' }]);
});

test('an existing pr branch is reused, not recreated', async () => {
  const neon = fakeNeon(['main', 'seed', 'pr-42']);
  assert.deepEqual(await ensurePreviewBranch(neon, PR), { name: 'pr-42', created: false });
  assert.deepEqual(neon.calls, []);
});

test('creation beyond the budget is refused and deletes nothing', async () => {
  const neon = fakeNeon(['main', 'seed', ...prBranches(PREVIEW_BRANCH_BUDGET)]);
  await assert.rejects(
    ensurePreviewBranch(neon, PR),
    new RegExp(`${PREVIEW_BRANCH_BUDGET} pull request branches already exist .*close a pull request`),
  );
  assert.deepEqual(neon.calls, []);
});

test('only pr- branches count toward the budget', async () => {
  const neon = fakeNeon(['main', 'seed', 'scratch', 'pr-notes', ...prBranches(PREVIEW_BRANCH_BUDGET - 1)]);
  assert.equal((await ensurePreviewBranch(neon, PR)).created, true);
});

test('removing a branch that is already gone succeeds', async () => {
  const neon = fakeNeon(['main', 'seed']);
  assert.equal(await removePreviewBranch(neon, PR), false);
  const withBranch = fakeNeon(['main', 'seed', 'pr-42']);
  assert.equal(await removePreviewBranch(withBranch, PR), true);
  assert.deepEqual(withBranch.branches.map((branch) => branch.name), ['main', 'seed']);
});

test('the preview connects pooled as roadtrip_web with the app timeouts', async () => {
  const neon = fakeNeon(['main', 'seed', 'pr-42']);
  const url = new URL(await previewDatabaseUrl(neon, PR));
  assert.deepEqual(neon.calls, [
    { op: 'uri', branchName: 'pr-42', database: 'roadtrip', role: 'roadtrip_web', pooled: true },
  ]);
  assert.equal(url.searchParams.get('sslmode'), 'require');
  assert.equal(url.searchParams.get('connect_timeout'), '10');
  assert.equal(url.searchParams.get('idle_timeout'), '20');
});
