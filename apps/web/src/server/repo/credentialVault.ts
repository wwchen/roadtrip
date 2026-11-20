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

const VAULT_LOG = 'credential vault:';
const KEY_CHANGED = 'key_changed';

function keyChanged(userId: bigint): { kind: typeof KEY_CHANGED } {
  console.warn(VAULT_LOG, KEY_CHANGED, String(userId));
  return { kind: KEY_CHANGED };
}

function openSealed(key: Buffer, sealed: Uint8Array): string | null {
  try {
    return decryptSecret(key, Buffer.from(sealed));
  } catch {
    return null;
  }
}

export function credentialVault(db: Db, key: Buffer | null): CredentialVault {
  return {
    async status(userId) {
      const stored = await findCredentials(db, userId, RECGOV_PROVIDER);
      if (stored === null) return { kind: 'none' };
      if (key === null) return { kind: 'locked', username: stored.username };
      if (openSealed(key, stored.secretCipher) === null) return { ...keyChanged(userId), username: stored.username };
      return { kind: 'stored', username: stored.username };
    },
    async open(userId) {
      if (key === null) return { kind: 'unavailable' };
      const stored = await findCredentials(db, userId, RECGOV_PROVIDER);
      if (stored === null) return { kind: 'none' };
      const password = openSealed(key, stored.secretCipher);
      if (password === null) return keyChanged(userId);
      return { kind: 'opened', username: stored.username, password };
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
