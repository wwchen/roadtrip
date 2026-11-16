import { appUser, userBookingCredentials, userSettings } from '@roadtrip/db';
import { afterAll, beforeAll, beforeEach, expect, test } from 'vitest';
import { startCatalogFixture, type CatalogFixture } from '../testing/catalogFixture';
import { RECGOV_PROVIDER, findCredentials, removeCredentials, updateCredentialsUsername, upsertCredentials } from './bookingCredentialsRepo';
import { credentialVault } from './credentialVault';
import { notificationsFor } from './userSettingsRepo';

let fixture: CatalogFixture;
let userId: bigint;

const KEY_BYTES = 32;
const KEY = Buffer.alloc(KEY_BYTES, 7);
const PASSWORD = 'correct horse';

beforeAll(async () => {
  fixture = await startCatalogFixture();
});

afterAll(async () => {
  await fixture?.stop();
});

beforeEach(async () => {
  await fixture.writeDb.delete(userBookingCredentials);
  await fixture.writeDb.delete(userSettings);
  await fixture.writeDb.delete(appUser);
  const [row] = await fixture.writeDb.insert(appUser).values({ email: 'camper@example.com', emailVerified: true }).returning({ id: appUser.id });
  userId = row!.id;
});

test('no row reads as null', async () => {
  expect(await findCredentials(fixture.writeDb, userId, RECGOV_PROVIDER)).toBeNull();
});

test('upsert stores, then replaces, the username and cipher', async () => {
  await upsertCredentials(fixture.writeDb, userId, RECGOV_PROVIDER, 'first@example.com', Buffer.from([1, 2, 3]));
  await upsertCredentials(fixture.writeDb, userId, RECGOV_PROVIDER, 'second@example.com', Buffer.from([4, 5]));
  const found = await findCredentials(fixture.writeDb, userId, RECGOV_PROVIDER);
  expect(found?.username).toBe('second@example.com');
  expect(Buffer.from(found!.secretCipher).equals(Buffer.from([4, 5]))).toBe(true);
});

test('remove deletes the row and says whether there was one', async () => {
  await upsertCredentials(fixture.writeDb, userId, RECGOV_PROVIDER, 'a@example.com', Buffer.from([1]));
  expect(await removeCredentials(fixture.writeDb, userId, RECGOV_PROVIDER)).toBe(true);
  expect(await removeCredentials(fixture.writeDb, userId, RECGOV_PROVIDER)).toBe(false);
  expect(await findCredentials(fixture.writeDb, userId, RECGOV_PROVIDER)).toBeNull();
});

test('notifications fall back to the login email and say Slack is not configured', async () => {
  expect(await notificationsFor(fixture.writeDb, userId, 'camper@example.com')).toEqual({
    notificationEmail: 'camper@example.com',
    slackChannel: null,
    slackConfigured: false,
    slackTokenHint: null,
  });
});

test('stored notification settings are read, never the Slack token', async () => {
  await fixture.writeDb.insert(userSettings).values({
    userId,
    notificationEmail: 'alerts@example.com',
    slackChannel: '#camp',
    slackTokenCipher: Buffer.from([9]),
    slackTokenHint: 'abcd',
  });
  expect(await notificationsFor(fixture.writeDb, userId, 'camper@example.com')).toEqual({
    notificationEmail: 'alerts@example.com',
    slackChannel: '#camp',
    slackConfigured: true,
    slackTokenHint: 'abcd',
  });
});

test('a username-only update keeps the sealed password', async () => {
  await upsertCredentials(fixture.writeDb, userId, RECGOV_PROVIDER, 'old@example.com', Buffer.from([7, 7]));
  await updateCredentialsUsername(fixture.writeDb, userId, RECGOV_PROVIDER, 'new@example.com');
  const found = await findCredentials(fixture.writeDb, userId, RECGOV_PROVIDER);
  expect(found?.username).toBe('new@example.com');
  expect(found?.secretCipher.equals(Buffer.from([7, 7]))).toBe(true);
});

test('the vault reads none, then stored after a store, and opens the password it sealed', async () => {
  const vault = credentialVault(fixture.writeDb, KEY);
  expect(await vault.status(userId)).toEqual({ kind: 'none' });
  expect(await vault.open(userId)).toEqual({ kind: 'none' });
  expect(await vault.store(userId, 'camper@example.com', PASSWORD)).toBe('stored');
  expect(await vault.status(userId)).toEqual({ kind: 'stored', username: 'camper@example.com' });
  expect(await vault.open(userId)).toEqual({ kind: 'opened', username: 'camper@example.com', password: PASSWORD });
});

test('without a key a stored row is locked, opens as unavailable, and cannot store', async () => {
  await credentialVault(fixture.writeDb, KEY).store(userId, 'camper@example.com', PASSWORD);
  const locked = credentialVault(fixture.writeDb, null);
  expect(await locked.status(userId)).toEqual({ kind: 'locked', username: 'camper@example.com' });
  expect(await locked.open(userId)).toEqual({ kind: 'unavailable' });
  expect(await locked.store(userId, 'other@example.com', 'other')).toBe('unavailable');
  expect(await credentialVault(fixture.writeDb, KEY).open(userId)).toEqual({ kind: 'opened', username: 'camper@example.com', password: PASSWORD });
});

test('rename keeps the sealed password, and remove says whether there was a row', async () => {
  const vault = credentialVault(fixture.writeDb, KEY);
  await vault.store(userId, 'old@example.com', PASSWORD);
  await vault.rename(userId, 'new@example.com');
  expect(await vault.open(userId)).toEqual({ kind: 'opened', username: 'new@example.com', password: PASSWORD });
  expect(await vault.remove(userId)).toBe(true);
  expect(await vault.remove(userId)).toBe(false);
  expect(await vault.status(userId)).toEqual({ kind: 'none' });
});
