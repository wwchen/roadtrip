import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';

const ALGORITHM = 'aes-256-gcm';
const NONCE_BYTES = 12;
const TAG_BYTES = 16;
const TEXT_ENCODING = 'utf8';

export function encryptSecret(key: Buffer, plaintext: string): Buffer {
  const nonce = randomBytes(NONCE_BYTES);
  const cipher = createCipheriv(ALGORITHM, key, nonce);
  const body = Buffer.concat([cipher.update(plaintext, TEXT_ENCODING), cipher.final()]);
  return Buffer.concat([nonce, body, cipher.getAuthTag()]);
}

export function decryptSecret(key: Buffer, sealed: Buffer): string {
  if (sealed.length < NONCE_BYTES + TAG_BYTES) throw new Error('sealed secret is too short');
  const nonce = sealed.subarray(0, NONCE_BYTES);
  const tag = sealed.subarray(sealed.length - TAG_BYTES);
  const body = sealed.subarray(NONCE_BYTES, sealed.length - TAG_BYTES);
  const decipher = createDecipheriv(ALGORITHM, key, nonce, { authTagLength: TAG_BYTES });
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(body), decipher.final()]).toString(TEXT_ENCODING);
}
