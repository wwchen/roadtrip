import type { ActionResult, HoldResult, LoginResult, SessionHealth, SignOutResult } from '@/server/service/booking/model';

type RelayAnswer<R> = { answer: unknown; result: R };

export const RELAY_ANSWERS: {
  hold: RelayAnswer<HoldResult>;
  signIn: RelayAnswer<LoginResult>;
  submitMfa: RelayAnswer<LoginResult>;
  refresh: RelayAnswer<ActionResult>;
  verify: RelayAnswer<ActionResult>;
  health: RelayAnswer<SessionHealth>;
  signOut: RelayAnswer<SignOutResult>;
} = {
  hold: { answer: { ok: true, cart_url: 'u' }, result: { kind: 'held', cartUrl: 'u' } },
  signIn: { answer: { ok: true }, result: { kind: 'signed_in' } },
  submitMfa: {
    answer: { ok: false, code: 'mfa_required', challenge_id: 'ch-10' },
    result: { kind: 'mfa_required', challengeId: 'ch-10' },
  },
  refresh: { answer: { ok: true }, result: { kind: 'ok' } },
  verify: {
    answer: { ok: false, code: 'x', category: 'caller_action' },
    result: { kind: 'failed', refusal: { category: 'caller_action', code: 'x' } },
  },
  health: { answer: { ok: true, health: 'active' }, result: { kind: 'active' } },
  signOut: {
    answer: { ok: true, profile_destroyed: true, signed_out: true },
    result: { kind: 'wiped', signedOut: true },
  },
};
