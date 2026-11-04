#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

export const PREVIEW_PROBE = Object.freeze({ name: 'Mahood Lake', bbox: '-120.2,52.1,-120.0,52.3' });
export const BYPASS_HEADER = 'x-vercel-protection-bypass';
export const BYPASS_SECRET_ENV = 'VERCEL_AUTOMATION_BYPASS_SECRET';
export const PREVIEW_URL_ENV = 'PREVIEW_URL';
export const PREVIEW_ENVIRONMENT = 'preview';

const SEARCH_PATH = '/api/campgrounds/search';
const CAMPGROUND_PATH = '/campgrounds/';
const STATUS_OK = 200;
const SERVER_ERROR_FLOOR = 500;
const REQUEST_TIMEOUT_MS = 30_000;
const ATTEMPTS = 3;
const RETRY_DELAY_MS = 5_000;

const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function fetchWithRetry(url, init, { fetch, delayMs }) {
  let last;
  for (let attempt = 1; attempt <= ATTEMPTS; attempt += 1) {
    try {
      const response = await fetch(url, { ...init, signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS) });
      if (response.status < SERVER_ERROR_FLOOR) return response;
      last = new Error(`${url} answered ${response.status}`);
    } catch (error) {
      last = error;
    }
    if (attempt < ATTEMPTS) await pause(delayMs);
  }
  throw last;
}

export async function checkPreview({ baseUrl, bypassSecret, probe = PREVIEW_PROBE, fetch = globalThis.fetch, delayMs = RETRY_DELAY_MS }) {
  if (!baseUrl) throw new Error(`${PREVIEW_URL_ENV} is not set`);
  if (!bypassSecret) throw new Error(`${BYPASS_SECRET_ENV} is not set`);
  const init = { headers: { [BYPASS_HEADER]: bypassSecret }, redirect: 'manual' };
  const origin = new URL(baseUrl).origin;

  const searchUrl = new URL(SEARCH_PATH, origin);
  searchUrl.searchParams.set('bbox', probe.bbox);
  const search = await fetchWithRetry(searchUrl, init, { fetch, delayMs });
  if (search.status !== STATUS_OK) throw new Error(`search over ${probe.bbox} answered ${search.status}`);
  const { campgrounds } = await search.json();
  const found = campgrounds.find((campground) => campground.name === probe.name);
  if (found === undefined) {
    throw new Error(
      `search over ${probe.bbox} did not return ${probe.name}; it returned ${campgrounds.length} campgrounds` +
        (campgrounds.length === 0 ? '' : `: ${campgrounds.map((campground) => campground.name).join(', ')}`),
    );
  }

  const pageUrl = new URL(`${CAMPGROUND_PATH}${found.poiId}`, origin);
  const page = await fetchWithRetry(pageUrl, init, { fetch, delayMs });
  if (page.status !== STATUS_OK) throw new Error(`${pageUrl.pathname} answered ${page.status}`);
  if (!(await page.text()).includes(probe.name)) throw new Error(`${pageUrl.pathname} does not show ${probe.name}`);
  return { poiId: found.poiId };
}

export function previewFromDispatch(payload) {
  const { url, environment, git } = payload ?? {};
  if (typeof environment !== 'string' || typeof url !== 'string' || typeof git?.sha !== 'string') {
    throw new Error(
      `a Vercel deployment event without environment, url and git.sha; it has: ${Object.keys(payload ?? {}).join(', ')}`,
    );
  }
  if (environment !== PREVIEW_ENVIRONMENT) return { skip: `a ${environment} deployment, not a preview` };
  return { url: url.startsWith('https://') ? url : `https://${url}`, sha: git.sha };
}

async function main() {
  const { positionals, values } = parseArgs({
    allowPositionals: true,
    options: { url: { type: 'string' }, event: { type: 'string' } },
    strict: true,
  });
  const [command] = positionals;
  if (command === 'target') {
    const event = JSON.parse(await readFile(values.event, 'utf8'));
    const target = previewFromDispatch(event.client_payload);
    if (target.skip !== undefined) {
      console.error(`skipping ${target.skip}`);
      process.stdout.write('skip=true\n');
    } else {
      process.stdout.write(`skip=false\nurl=${target.url}\nsha=${target.sha}\n`);
    }
    return;
  }
  if (command !== 'check') {
    console.error('usage: check-preview.mjs target --event <path> | check [--url <url>]');
    process.exitCode = 2;
    return;
  }
  const { poiId } = await checkPreview({
    baseUrl: values.url ?? process.env[PREVIEW_URL_ENV],
    bypassSecret: process.env[BYPASS_SECRET_ENV],
  });
  console.log(`preview serves ${PREVIEW_PROBE.name} (poi ${poiId}) through search and its page`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    await main();
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
