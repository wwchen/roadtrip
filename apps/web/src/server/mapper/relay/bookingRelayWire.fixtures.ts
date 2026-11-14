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
  hold: { answer: { outcome: 'held', cart_url: 'u' }, result: { kind: 'held', cartUrl: 'u' } },
  signIn: { answer: { outcome: 'signed_in' }, result: { kind: 'signed_in' } },
  submitMfa: {
    answer: { outcome: 'mfa_required', challenge_id: 'ch-10' },
    result: { kind: 'mfa_required', challengeId: 'ch-10' },
  },
  refresh: { answer: { outcome: 'ok' }, result: { kind: 'ok' } },
  verify: {
    answer: { outcome: 'failed', failure: { code: 'recgov_not_authenticated', category: 'caller_action' } },
    result: { kind: 'failed', refusal: { category: 'caller_action', code: 'recgov_not_authenticated' } },
  },
  health: { answer: { session: 'active' }, result: { kind: 'active' } },
  signOut: {
    answer: { outcome: 'wiped', profile_destroyed: true, signed_out: true },
    result: { kind: 'wiped', signedOut: true },
  },
};
