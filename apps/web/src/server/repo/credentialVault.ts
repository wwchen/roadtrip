import type { Db } from '@roadtrip/db';
import { decryptSecret, encryptSecret } from '../crypto/secretCipher';
import type { CredentialVault } from '../service/booking/ports';
import {
  RECGOV_PROVIDER,
  findCredentials,
  removeCredentials,
  updateCredentialsUsername,
  upsertCredentials,
} from './bookingCredentialsRepo';

export function credentialVault(db: Db, key: Buffer | null): CredentialVault {
  return {
    async status(userId) {
      const stored = await findCredentials(db, userId, RECGOV_PROVIDER);
      if (stored === null) return { kind: 'none' };
      return key === null ? { kind: 'locked', username: stored.username } : { kind: 'stored', username: stored.username };
    },
    async open(userId) {
      if (key === null) return { kind: 'unavailable' };
      const stored = await findCredentials(db, userId, RECGOV_PROVIDER);
      if (stored === null) return { kind: 'none' };
      return { kind: 'opened', username: stored.username, password: decryptSecret(key, stored.secretCipher) };
    },
    async store(userId, username, password) {
      if (key === null) return 'unavailable';
      await upsertCredentials(db, userId, RECGOV_PROVIDER, username, encryptSecret(key, password));
      return 'stored';
    },
    async rename(userId, username) {
      await updateCredentialsUsername(db, userId, RECGOV_PROVIDER, username);
    },
    remove: (userId) => removeCredentials(db, userId, RECGOV_PROVIDER),
  };
}
