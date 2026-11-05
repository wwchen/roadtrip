import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
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
const POSTGRES_IN_NAMESPACE = '127.0.0.1:5432';
const WEB_IN_NAMESPACE = 'http://127.0.0.1:3000';
const SMOKE_STARTUP_MS = 60_000;
const SMOKE_POLL_MS = 500;
const HTTP_OK = 200;
const STATUS_SEPARATOR = ' ';
const NOT_LISTENING = 0;

interface InContainerResponse {
  status: number;
  body: string;
}

describe.skipIf(IMAGE === undefined)('the web image', () => {
  let fixture: CatalogFixture;
  let containerId = '';

  const request = (requestPath: string): InContainerResponse => {
    const script = `fetch('${WEB_IN_NAMESPACE}${requestPath}').then(async (r) => console.log(r.status + '${STATUS_SEPARATOR}' + await r.text()), () => console.log('${NOT_LISTENING}${STATUS_SEPARATOR}'))`;
    const out = execFileSync('docker', ['exec', containerId, 'node', '-e', script], { encoding: 'utf8' }).trim();
    const split = out.indexOf(STATUS_SEPARATOR);
    return { status: Number(out.slice(0, split)), body: out.slice(split + STATUS_SEPARATOR.length) };
  };

  const logs = () => execFileSync('docker', ['logs', containerId], { encoding: 'utf8' });

  beforeAll(async () => {
    fixture = await startCatalogFixture();
    await fixture.writeDb.execute(sql.raw(readFileSync(MIGRATION, 'utf8')));
    await fixture.writeDb.execute(sql.raw(`ALTER ROLE ${WEB_READ_ROLE} PASSWORD '${PASSWORD}'`));
    const secrets = mkdtempSync(path.join(tmpdir(), 'web-image-'));
    writeFileSync(path.join(secrets, SECRET_NAME), PASSWORD);
    containerId = execFileSync(
      'docker',
      [
        'run', '-d',
        `--network=container:${fixture.container}`,
        '-v', `${secrets}:/run/secrets:ro`,
        '-e', `DATABASE_URL=postgres://${WEB_READ_ROLE}@${POSTGRES_IN_NAMESPACE}/${fixture.database}`,
        '-e', `DATABASE_PASSWORD_FILE=/run/secrets/${SECRET_NAME}`,
        '-e', `ENABLED_DATA_PROVIDERS=${ENABLED_PROVIDER}`,
        IMAGE!,
      ],
      { encoding: 'utf8' },
    ).trim();
    const deadline = Date.now() + SMOKE_STARTUP_MS;
    while (request('/api/health').status !== HTTP_OK) {
      if (Date.now() > deadline) throw new Error(`web image never became healthy:\n${logs()}`);
      await new Promise((resolve) => setTimeout(resolve, SMOKE_POLL_MS));
    }
  }, SMOKE_STARTUP_MS * 2);

  afterAll(async () => {
    if (containerId !== '') execFileSync('docker', ['rm', '-f', containerId]);
    await fixture?.stop();
  });

  test('the image answers place search from the database', () => {
    const response = request('/api/pois/search?q=Near');
    expect(response.status, logs()).toBe(HTTP_OK);
    const body = JSON.parse(response.body) as { results: { name: string }[] };
    expect(body.results.map((hit) => hit.name)).toContain(NEAR_NAME);
  });

  test('the image serves a campground detail', () => {
    const response = request(`/api/pois/${NEAR_POI_ID}`);
    expect(response.status, logs()).toBe(HTTP_OK);
    expect(JSON.parse(response.body)).toMatchObject({ type: 'Feature' });
  });
});
