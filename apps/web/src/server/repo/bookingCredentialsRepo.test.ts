import { appUser, userBookingCredentials, userSettings } from '@roadtrip/db';
import { afterAll, beforeAll, beforeEach, expect, test } from 'vitest';
import { startCatalogFixture, type CatalogFixture } from '../testing/catalogFixture';
import { RECGOV_PROVIDER, findCredentials, removeCredentials, updateCredentialsUsername, upsertCredentials } from './bookingCredentialsRepo';
import { notificationsFor } from './userSettingsRepo';

let fixture: CatalogFixture;
let userId: bigint;

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
    notification_email: 'camper@example.com',
    slack_configured: false,
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
    notification_email: 'alerts@example.com',
    slack_channel: '#camp',
    slack_configured: true,
    slack_token_hint: 'abcd',
  });
});

test('a username-only update keeps the sealed password', async () => {
  await upsertCredentials(fixture.writeDb, userId, RECGOV_PROVIDER, 'old@example.com', Buffer.from([7, 7]));
  await updateCredentialsUsername(fixture.writeDb, userId, RECGOV_PROVIDER, 'new@example.com');
  const found = await findCredentials(fixture.writeDb, userId, RECGOV_PROVIDER);
  expect(found?.username).toBe('new@example.com');
  expect(found?.secretCipher.equals(Buffer.from([7, 7]))).toBe(true);
});
