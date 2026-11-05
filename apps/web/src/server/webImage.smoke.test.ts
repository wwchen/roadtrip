import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';
import { WEB_READ_ROLE } from './repo/webReadGrants';
import { ENABLED_PROVIDER, NEAR_NAME, NEAR_POI_ID, startCatalogFixture, type CatalogFixture } from './testing/catalogFixture';

const IMAGE = process.env.WEB_IMAGE;
const MIGRATION = path.resolve(import.meta.dirname, '../../../../backend/src/main/resources/db/migration/R__web_reader_grants.sql');
const PASSWORD = 'smoke-web-reader';
const SECRET_NAME = 'web_db_password';
const CONTAINER_PORT = 3000;
const DOCKER_HOST_ALIAS = 'host.docker.internal';
const SMOKE_STARTUP_MS = 60_000;
const SMOKE_POLL_MS = 500;
const HTTP_OK = 200;

async function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      server.close(() => (typeof address === 'object' && address !== null ? resolve(address.port) : reject(new Error('no port'))));
    });
  });
}

describe.skipIf(IMAGE === undefined)('the web image', () => {
  let fixture: CatalogFixture;
  let containerId = '';
  let base = '';

  beforeAll(async () => {
    fixture = await startCatalogFixture();
    await fixture.writeDb.execute(sql.raw(readFileSync(MIGRATION, 'utf8')));
    await fixture.writeDb.execute(sql.raw(`ALTER ROLE ${WEB_READ_ROLE} PASSWORD '${PASSWORD}'`));
    const secrets = mkdtempSync(path.join(tmpdir(), 'web-image-'));
    writeFileSync(path.join(secrets, SECRET_NAME), PASSWORD);
    const database = new URL(fixture.url);
    database.username = WEB_READ_ROLE;
    database.password = '';
    database.hostname = DOCKER_HOST_ALIAS;
    const port = await freePort();
    base = `http://127.0.0.1:${port}`;
    containerId = execFileSync(
      'docker',
      [
        'run', '-d',
        `--add-host=${DOCKER_HOST_ALIAS}:host-gateway`,
        '-p', `127.0.0.1:${port}:${CONTAINER_PORT}`,
        '-v', `${secrets}:/run/secrets:ro`,
        '-e', `DATABASE_URL=${database.toString()}`,
        '-e', `DATABASE_PASSWORD_FILE=/run/secrets/${SECRET_NAME}`,
        '-e', `ENABLED_DATA_PROVIDERS=${ENABLED_PROVIDER}`,
        IMAGE!,
      ],
      { encoding: 'utf8' },
    ).trim();
    const deadline = Date.now() + SMOKE_STARTUP_MS;
    for (;;) {
      const status = await fetch(`${base}/api/health`).then((response) => response.status, () => 0);
      if (status === HTTP_OK) return;
      if (Date.now() > deadline) throw new Error(`web image never became healthy:\n${execFileSync('docker', ['logs', containerId], { encoding: 'utf8' })}`);
      await new Promise((resolve) => setTimeout(resolve, SMOKE_POLL_MS));
    }
  }, SMOKE_STARTUP_MS * 2);

  afterAll(async () => {
    if (containerId !== '') execFileSync('docker', ['rm', '-f', containerId]);
    await fixture?.stop();
  });

  test('the image answers place search from the database', async () => {
    const response = await fetch(`${base}/api/pois/search?q=Near`);
    expect(response.status).toBe(HTTP_OK);
    const body = (await response.json()) as { results: { name: string }[] };
    expect(body.results.map((hit) => hit.name)).toContain(NEAR_NAME);
  });

  test('the image serves a campground detail', async () => {
    const response = await fetch(`${base}/api/pois/${NEAR_POI_ID}`);
    expect(response.status).toBe(HTTP_OK);
    expect(await response.json()).toMatchObject({ type: 'Feature' });
  });
});
