import { config } from '../config';
import { challengeCodec, type ChallengeCodec } from '../mapper/http/recgovHttp';
import type { RecgovPorts } from '../service/settings/ports';
import { liveGateway, liveVault } from './bookingAdapters';

const DEFAULT_MFA_CHALLENGE_TTL_MS = 300_000;

export function recgovPorts(): RecgovPorts & { challenges: ChallengeCodec } {
  const clock = { now: Date.now };
  return {
    gateway: liveGateway(),
    vault: liveVault(),
    clock,
    mfaChallengeTtlMs: config.booking?.mfaChallengeTtlMs ?? DEFAULT_MFA_CHALLENGE_TTL_MS,
    challenges: challengeCodec(config.bookingCipherKey, clock),
  };
}
