import type { CredentialVault } from '../service/booking/ports';

export interface MemoryCredentials {
  username: string;
  password: string;
}

export const KEY_CHANGED = 'key_changed';

export function memoryVault(
  initial: MemoryCredentials | null,
  keyed: boolean | typeof KEY_CHANGED = true,
): { vault: CredentialVault; row(): MemoryCredentials | null } {
  let current = initial;
  let sealedUnderOtherKey = keyed === KEY_CHANGED;
  const vault: CredentialVault = {
    async status() {
      if (current === null) return { kind: 'none' };
      if (keyed === false) return { kind: 'locked', username: current.username };
      return sealedUnderOtherKey ? { kind: 'key_changed', username: current.username } : { kind: 'stored', username: current.username };
    },
    async open() {
      if (keyed === false) return { kind: 'unavailable' };
      if (current === null) return { kind: 'none' };
      return sealedUnderOtherKey ? { kind: 'key_changed' } : { kind: 'opened', username: current.username, password: current.password };
    },
    async store(_userId, username, password) {
      if (keyed === false) return 'unavailable';
      current = { username, password };
      sealedUnderOtherKey = false;
      return 'stored';
    },
    async rename(_userId, username) {
      if (current !== null) current = { ...current, username };
    },
    async remove() {
      const had = current !== null;
      current = null;
      return had;
    },
  };
  return { vault, row: () => current };
}
