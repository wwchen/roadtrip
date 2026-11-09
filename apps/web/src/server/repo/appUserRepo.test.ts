import { appUser, userIdentity } from '@roadtrip/db';
import { afterAll, beforeAll, beforeEach, expect, test } from 'vitest';
import { startCatalogFixture, type CatalogFixture } from '../testing/catalogFixture';
import { EmailTakenError, NEON_AUTH_PROVIDER, UnverifiedIdentityError, linkedAppUser, type IdentityClaims } from './appUserRepo';

const CONCURRENT_FIRST_CALLS = 5;
const DEFAULT_THEME = 'system';

let fixture: CatalogFixture;

const claims = (overrides: Partial<IdentityClaims> = {}): IdentityClaims => ({
  subject: 'subject-a',
  email: 'will@example.com',
  emailVerified: true,
  displayName: 'Will',
  ...overrides,
});

const counts = async () => ({
  users: (await fixture.writeDb.select().from(appUser)).length,
  identities: (await fixture.writeDb.select().from(userIdentity)).length,
});

beforeAll(async () => {
  fixture = await startCatalogFixture();
});

afterAll(async () => {
  await fixture?.stop();
});

beforeEach(async () => {
  await fixture.writeDb.delete(userIdentity);
  await fixture.writeDb.delete(appUser);
});

test('a new identity creates one app_user and one user_identity', async () => {
  const user = await linkedAppUser(fixture.writeDb, claims());
  expect(user).toMatchObject({ email: 'will@example.com', emailVerified: true, displayName: 'Will', theme: DEFAULT_THEME });
  expect(await counts()).toEqual({ users: 1, identities: 1 });
  const [identity] = await fixture.writeDb.select().from(userIdentity);
  expect(identity).toMatchObject({ provider: NEON_AUTH_PROVIDER, subject: 'subject-a', userId: user.id });
});

test('the same identity again returns the same id and refreshes the profile', async () => {
  const first = await linkedAppUser(fixture.writeDb, claims({ displayName: 'Old' }));
  const second = await linkedAppUser(fixture.writeDb, claims({ displayName: 'New' }));
  expect(second.id).toBe(first.id);
  expect(second).toMatchObject({ emailVerified: true, displayName: 'New' });
  expect(await counts()).toEqual({ users: 1, identities: 1 });
});

test('a second verified identity with the same email, in any case, joins the existing user', async () => {
  const first = await linkedAppUser(fixture.writeDb, claims());
  const second = await linkedAppUser(fixture.writeDb, claims({ subject: 'subject-b', email: 'Will@Example.com' }));
  expect(second.id).toBe(first.id);
  expect(await counts()).toEqual({ users: 1, identities: 2 });
});

test('an unverified identity never joins an existing user by email', async () => {
  await linkedAppUser(fixture.writeDb, claims());
  await expect(
    linkedAppUser(fixture.writeDb, claims({ subject: 'subject-b', emailVerified: false })),
  ).rejects.toBeInstanceOf(UnverifiedIdentityError);
  expect(await counts()).toEqual({ users: 1, identities: 1 });
});

test('concurrent first calls for one identity both answer the same user', async () => {
  const users = await Promise.all(
    Array.from({ length: CONCURRENT_FIRST_CALLS }, () => linkedAppUser(fixture.writeDb, claims())),
  );
  expect(new Set(users.map((user) => user.id)).size).toBe(1);
  expect(await counts()).toEqual({ users: 1, identities: 1 });
});

test('an unverified identity with no account gets none', async () => {
  await expect(linkedAppUser(fixture.writeDb, claims({ emailVerified: false }))).rejects.toBeInstanceOf(UnverifiedIdentityError);
  expect(await counts()).toEqual({ users: 0, identities: 0 });
});

test('a verified identity never joins an unverified account with its email', async () => {
  await fixture.writeDb.insert(appUser).values({ email: 'will@example.com', emailVerified: false });
  await expect(linkedAppUser(fixture.writeDb, claims({ subject: 'subject-b' }))).rejects.toBeInstanceOf(EmailTakenError);
  expect(await counts()).toEqual({ users: 1, identities: 0 });
});
