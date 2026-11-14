import { realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export const REQUEST_TIMEOUT_MS = 30_000;
export const RETRY_COUNT = 2;
export const RETRY_DELAY_MS = 5_000;
export const POLL_INTERVAL_MS = 10_000;
export const DEFAULT_AWAIT_TIMEOUT_SECONDS = 900;
export const MILLISECONDS_PER_SECOND = 1_000;
export const SERVER_ERROR_STATUS_FLOOR = 500;
export const REDIRECT_MODE = 'manual';
export const BYPASS_HEADER = 'x-vercel-protection-bypass';
export const SEARCH_PATH = '/api/campgrounds/search';
export const CAMPGROUND_PAGE_PATH = '/campgrounds';
export const BUILD_INFO_PATH = '/api/build-info';
export const HOME_PATH = '/';
export const ME_PATH = '/api/me';
export const POLLERS_SUMMARY_PATH = '/api/availability/pollers/summary';
export const OPENAPI_PATH = '/api/docs/openapi.json';
export const COMMANDS = ['catalog', 'surface', 'await-sha'];
export const FAILURE_EXIT_CODE = 1;

export class ProbeError extends Error {}

const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

const isObject = (value) => typeof value === 'object' && value !== null && !Array.isArray(value);

function required(env, name) {
  const value = env[name];
  if (!value) throw new ProbeError(`${name} is not set`);
  return value;
}

export function readConfig(env, overrides = {}) {
  const awaitSeconds = env.AWAIT_TIMEOUT_SECONDS
    ? Number(env.AWAIT_TIMEOUT_SECONDS)
    : DEFAULT_AWAIT_TIMEOUT_SECONDS;
  if (!Number.isFinite(awaitSeconds) || awaitSeconds < 0) {
    throw new ProbeError(`AWAIT_TIMEOUT_SECONDS is not a non-negative number: ${env.AWAIT_TIMEOUT_SECONDS}`);
  }
  return {
    baseUrl: required(env, 'BASE_URL').replace(/\/+$/, ''),
    probeName: env.PROBE_NAME,
    probeBbox: env.PROBE_BBOX,
    expectedSha: env.EXPECTED_SHA,
    awaitTimeoutMs: awaitSeconds * MILLISECONDS_PER_SECOND,
    bypassSecret: env.VERCEL_AUTOMATION_BYPASS_SECRET || undefined,
    requestTimeoutMs: REQUEST_TIMEOUT_MS,
    retryCount: RETRY_COUNT,
    retryDelayMs: RETRY_DELAY_MS,
    pollIntervalMs: POLL_INTERVAL_MS,
    ...overrides,
  };
}

export async function request(config, path) {
  const headers = config.bypassSecret ? { [BYPASS_HEADER]: config.bypassSecret } : {};
  let failure = '';
  for (let attempt = 0; attempt <= config.retryCount; attempt += 1) {
    if (attempt > 0) await sleep(config.retryDelayMs);
    try {
      const response = await fetch(`${config.baseUrl}${path}`, {
        headers,
        redirect: REDIRECT_MODE,
        signal: AbortSignal.timeout(config.requestTimeoutMs),
      });
      const body = await response.text();
      if (response.status < SERVER_ERROR_STATUS_FLOOR) {
        return { status: response.status, contentType: response.headers.get('content-type') ?? '', body };
      }
      failure = `${path} answered ${response.status}`;
    } catch (error) {
      failure = `${path} could not be reached: ${error.message}`;
    }
  }
  throw new ProbeError(failure);
}

function parseJson(path, response) {
  try {
    return JSON.parse(response.body);
  } catch {
    throw new ProbeError(`${path} did not return JSON`);
  }
}

async function getJson(config, path) {
  const response = await request(config, path);
  if (response.status !== 200) throw new ProbeError(`${path} answered ${response.status}`);
  return parseJson(path, response);
}

export async function catalog(config) {
  const { probeName, probeBbox } = config;
  if (!probeName) throw new ProbeError('PROBE_NAME is not set');
  if (!probeBbox) throw new ProbeError('PROBE_BBOX is not set');
  const search = await getJson(config, `${SEARCH_PATH}?bbox=${probeBbox}`);
  const match = Array.isArray(search?.campgrounds)
    ? search.campgrounds.find((campground) => campground?.name === probeName)
    : undefined;
  if (!match || match.poiId == null) {
    throw new ProbeError(`search over ${probeBbox} did not return ${probeName}`);
  }
  const pagePath = `${CAMPGROUND_PAGE_PATH}/${encodeURIComponent(match.poiId)}`;
  const page = await request(config, pagePath);
  if (page.status !== 200) throw new ProbeError(`${pagePath} answered ${page.status}`);
  if (!page.body.includes(probeName)) throw new ProbeError(`${pagePath} does not show ${probeName}`);
  return `the deployment serves ${probeName} (poi ${match.poiId}) through search and its page`;
}

const SURFACE_CHECKS = [
  {
    path: HOME_PATH,
    kind: 'html',
    shape: 'an HTML page',
    valid: () => true,
  },
  {
    path: ME_PATH,
    kind: 'json',
    shape: 'a boolean authenticated',
    valid: (body) => isObject(body) && typeof body.authenticated === 'boolean',
  },
  {
    path: POLLERS_SUMMARY_PATH,
    kind: 'json',
    shape: 'a JSON object',
    valid: isObject,
  },
  {
    path: OPENAPI_PATH,
    kind: 'json',
    shape: 'a string openapi and an object paths',
    valid: (body) => isObject(body) && typeof body.openapi === 'string' && isObject(body.paths),
  },
];

export async function surface(config) {
  for (const check of SURFACE_CHECKS) {
    const response = await request(config, check.path);
    if (response.status !== 200) throw new ProbeError(`${check.path} answered ${response.status}`);
    const wanted = check.kind === 'html' ? 'text/html' : 'json';
    if (!response.contentType.toLowerCase().includes(wanted)) {
      throw new ProbeError(`${check.path} content type is ${response.contentType || 'missing'}, expected ${check.kind}`);
    }
    if (check.kind === 'json' && !check.valid(parseJson(check.path, response))) {
      throw new ProbeError(`${check.path} does not have ${check.shape}`);
    }
  }
  return `public surface answers on ${SURFACE_CHECKS.map((check) => check.path).join(', ')}`;
}

export async function awaitSha(config) {
  if (!config.expectedSha) throw new ProbeError('EXPECTED_SHA is not set');
  const deadline = Date.now() + config.awaitTimeoutMs;
  let lastSeen = 'nothing';
  for (;;) {
    try {
      const info = await getJson(config, BUILD_INFO_PATH);
      lastSeen = typeof info?.sha === 'string' ? info.sha : 'no sha';
      if (lastSeen === config.expectedSha) return `${BUILD_INFO_PATH} reports ${config.expectedSha}`;
    } catch (error) {
      lastSeen = error.message;
    }
    if (Date.now() >= deadline) {
      throw new ProbeError(`${BUILD_INFO_PATH} did not report ${config.expectedSha} in time; last saw ${lastSeen}`);
    }
    await sleep(config.pollIntervalMs);
  }
}

export const commands = { catalog, surface, 'await-sha': awaitSha };

export async function main(argv, env) {
  const command = argv[0];
  if (!COMMANDS.includes(command)) {
    throw new ProbeError(`usage: probe-deployment.mjs <${COMMANDS.join('|')}>`);
  }
  console.log(await commands[command](readConfig(env)));
}

if (process.argv[1] && realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2), process.env).catch((error) => {
    console.error(error instanceof ProbeError ? error.message : error.stack);
    process.exit(FAILURE_EXIT_CODE);
  });
}
