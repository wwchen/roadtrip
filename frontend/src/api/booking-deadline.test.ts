import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, test } from 'vitest';
import { CART_HOLD_DEADLINE_MS } from './booking-api';

const CONFIG_KT = join(
  process.cwd(),
  '../backend/src/main/kotlin/ca/floo/roadtrip/config/BookingConfig.kt',
);

const COMPANION_TIMEOUT = /defaultCompanionTimeout:\s*Duration\s*=\s*Duration\.ofSeconds\((\d+)\)/;
const FIRE_SHARE = /FIRE_TIMEOUT_SHARE_OF_RUN\s*=\s*(\d+)L/;
const PREFLIGHT_STEPS = 2;
const SECONDS_IN_MS = 1_000;

function readNumber(source: string, pattern: RegExp, what: string): number {
  const found = source.match(pattern);
  if (!found?.[1]) {
    throw new Error(
      `Could not read ${what} from BookingConfig.kt. If it moved or changed shape, this guard ` +
        `is blind — re-point it rather than deleting it, because CART_HOLD_DEADLINE_MS in ` +
        `api/booking-api.ts is sized against it.`,
    );
  }
  return Number(found[1]);
}

describe('the hold deadline against the backend it waits on', () => {
  test('outlasts a direct hold that pays for every preflight on the default budget', () => {
    const source = readFileSync(CONFIG_KT, 'utf8');
    const companionMs = readNumber(source, COMPANION_TIMEOUT, 'defaultCompanionTimeout') * SECONDS_IN_MS;
    const share = readNumber(source, FIRE_SHARE, 'FIRE_TIMEOUT_SHARE_OF_RUN');

    const worstCase = companionMs + PREFLIGHT_STEPS * (companionMs / share);

    expect(worstCase).toBe(300 * SECONDS_IN_MS);
    expect(CART_HOLD_DEADLINE_MS).toBeGreaterThan(worstCase);
  });

  test('leaves headroom rather than landing exactly on the worst case', () => {
    const source = readFileSync(CONFIG_KT, 'utf8');
    const companionMs = readNumber(source, COMPANION_TIMEOUT, 'defaultCompanionTimeout') * SECONDS_IN_MS;
    const share = readNumber(source, FIRE_SHARE, 'FIRE_TIMEOUT_SHARE_OF_RUN');
    const worstCase = companionMs + PREFLIGHT_STEPS * (companionMs / share);

    expect(CART_HOLD_DEADLINE_MS - worstCase).toBeGreaterThanOrEqual(30 * SECONDS_IN_MS);
  });

  test('a backend file this can no longer read fails loudly instead of passing', () => {
    expect(() => readNumber('nothing here', COMPANION_TIMEOUT, 'defaultCompanionTimeout')).toThrow(
      /re-point it rather than deleting it/,
    );
  });
});
