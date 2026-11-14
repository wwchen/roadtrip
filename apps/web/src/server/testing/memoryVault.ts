import type { CredentialVault } from '../service/booking/ports';

export interface MemoryCredentials {
  username: string;
  password: string;
}

export function memoryVault(
  initial: MemoryCredentials | null,
  keyed = true,
): { vault: CredentialVault; row(): MemoryCredentials | null } {
  let current = initial;
  const vault: CredentialVault = {
    async status() {
      if (current === null) return { kind: 'none' };
      return keyed ? { kind: 'stored', username: current.username } : { kind: 'locked', username: current.username };
    },
    async open() {
      if (!keyed) return { kind: 'unavailable' };
      return current === null ? { kind: 'none' } : { kind: 'opened', username: current.username, password: current.password };
    },
    async store(_userId, username, password) {
      if (!keyed) return 'unavailable';
      current = { username, password };
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
