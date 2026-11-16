import { readFileSync } from 'node:fs';
import path from 'node:path';
import { expect, test } from 'vitest';
import { decryptSecret, encryptSecret } from './secretCipher';

const VECTORS_FILE = path.resolve(
  import.meta.dirname,
  '..', '..', '..', '..', '..', 'backend', 'src', 'test', 'resources', 'secret-cipher-vectors.json',
);

interface CipherVectors {
  key: string;
  vectors: { plaintext: string; sealed: string }[];
}

const fixture = JSON.parse(readFileSync(VECTORS_FILE, 'utf8')) as CipherVectors;
const key = Buffer.from(fixture.key, 'base64');

test('decrypts the vectors Kotlin SecretCipher opens', () => {
  for (const vector of fixture.vectors) {
    expect(decryptSecret(key, Buffer.from(vector.sealed, 'base64'))).toBe(vector.plaintext);
  }
});

test('round trips, with a fresh nonce each time', () => {
  const first = encryptSecret(key, 'hunter2');
  const second = encryptSecret(key, 'hunter2');
  expect(decryptSecret(key, first)).toBe('hunter2');
  expect(first.equals(second)).toBe(false);
});

test('lays out a 12-byte nonce, the ciphertext, then a 16-byte tag', () => {
  expect(encryptSecret(key, 'abc').length).toBe(12 + 3 + 16);
});

test('rejects a tampered tag', () => {
  const sealed = encryptSecret(key, 'hunter2');
  sealed[sealed.length - 1] = (sealed[sealed.length - 1] ?? 0) ^ 1;
  expect(() => decryptSecret(key, sealed)).toThrow();
});

test('rejects a different key', () => {
  const sealed = encryptSecret(key, 'hunter2');
  expect(() => decryptSecret(Buffer.alloc(32, 9), sealed)).toThrow();
});
