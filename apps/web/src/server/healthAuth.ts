import { createHash, timingSafeEqual } from 'node:crypto';

const BEARER_PATTERN = /^Bearer (.+)$/i;
const DIGEST_ALGORITHM = 'sha256';

function digestOf(value: string): Buffer {
  return createHash(DIGEST_ALGORITHM).update(value).digest();
}

export function isAuthorizedForDatabaseCheck(
  authorizationHeader: string,
  configuredToken: string | undefined,
): boolean {
  if (configuredToken === undefined || configuredToken.trim().length === 0) return false;
  const presented = BEARER_PATTERN.exec(authorizationHeader)?.[1];
  if (presented === undefined) return false;
  return timingSafeEqual(digestOf(presented), digestOf(configuredToken));
}
