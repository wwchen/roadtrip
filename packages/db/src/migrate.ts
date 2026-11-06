import { createDb, migrateDb } from './client.js';

const MISSING_URL_EXIT_CODE = 2;

const url = process.env.DATABASE_URL;

if (!url) {
  console.error('DATABASE_URL is required; usage: DATABASE_URL=postgres://… npm run migrate');
  process.exit(MISSING_URL_EXIT_CODE);
}

const db = createDb(url, { prepare: false });

try {
  await migrateDb(db);
} finally {
  await db.$client.end();
}
