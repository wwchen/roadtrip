import { describe, expect, test } from 'vitest';
import { settingsErrorMessage } from './settings-errors';

const DEFAULT = 'Something went wrong. Please try again.';

describe('settingsErrorMessage', () => {
  test('maps each known backend code', () => {
    expect(settingsErrorMessage('invalid_field')).toBe('Please check the highlighted fields.');
    expect(settingsErrorMessage('slack_invalid_auth')).toBe('Slack rejected this token.');
    expect(settingsErrorMessage('slack_not_configured')).toBe('No Slack token is set.');
    expect(settingsErrorMessage('slack_send_failed')).toBe("Couldn't send to Slack.");
    expect(settingsErrorMessage('encryption_unavailable')).toBe(
      "Secret storage isn't configured on the server.",
    );
    expect(settingsErrorMessage('email_send_failed')).toBe("Couldn't send the test email.");
  });

  test('maps the rec.gov booking codes', () => {
    expect(settingsErrorMessage('login_failed')).toBe(
      'rec.gov rejected these credentials.',
    );
    expect(settingsErrorMessage('mfa_invalid')).toBe(
      'That code was rejected. Start the login again for a new one.',
    );
    expect(settingsErrorMessage('captcha_required')).toBe(
      'rec.gov showed a challenge we cannot solve. Try again in a moment.',
    );
    expect(settingsErrorMessage('companion_unavailable')).toBe(
      "The booking service isn't reachable right now.",
    );
  });

  test('the transient companion codes read as "try again", not as failures', () => {
    expect(settingsErrorMessage('profile_busy')).toBe(
      'Another operation is using your rec.gov session — try again shortly.',
    );
    expect(settingsErrorMessage('browser_cap_reached')).toBe(
      'The booking service is at capacity — try again shortly.',
    );
  });

  test('the two dead-challenge codes both end the challenge, and only one names the deadline', () => {
    // Both mean "start over" — "try again" (the fallback's advice) is the one
    // wrong instruction, because the backend has already cleared the challenge
    // and the code field is gone. They are worded apart on purpose: `expired`
    // is the user having been slow, so the deadline is worth naming; `unknown`
    // is a challenge that was never ours, where a deadline would confuse.
    const expired = settingsErrorMessage('mfa_challenge_expired');
    const unknown = settingsErrorMessage('mfa_challenge_unknown');

    expect(expired).toBe(
      'That code expired before it was entered. Start the login again, and enter the new code within a few minutes.',
    );
    expect(unknown).toBe('That code request expired. Start the login again.');
    expect(expired).not.toBe(unknown);
    for (const message of [expired, unknown]) {
      expect(message).toMatch(/start the login again/i);
      // "Try again" would point at a field that no longer exists.
      expect(message).not.toMatch(/try again/i);
    }
  });

  test('a session that dies mid-hold reads as the expiry it is, not as a raw code', () => {
    // The grid's add-to-cart preflights session health, then the session dies
    // before the click. These three arrive where `recgov_session_expired`
    // would have, so they must not read differently from it.
    const expected = 'Your rec.gov session expired — test login in Settings.';

    expect(settingsErrorMessage('recgov_session_expired')).toBe(expected);
    expect(settingsErrorMessage('recgov_spa_logged_out')).toBe(expected);
    expect(settingsErrorMessage('recgov_refresh_failed')).toBe(expected);
  });

  test('a refused automated sign-in points at the credentials, not at the session', () => {
    // Distinct from the two above: here we *did* sign in again with the saved
    // credentials and rec.gov refused, so a changed password is on the table.
    const message = settingsErrorMessage('recgov_login_failed');

    expect(message).toBe(
      'rec.gov would not sign you back in — check your credentials in Settings.',
    );
    expect(message).not.toBe(settingsErrorMessage('recgov_spa_logged_out'));
  });

  test('an unreadable cart is not reported as a signed-out session', () => {
    // "Test login again" would send the user in circles: the session is fine.
    const message = settingsErrorMessage('recgov_cart_unreachable');

    expect(message).toBe(
      "You're signed in, but rec.gov's cart could not be read — try again shortly.",
    );
    expect(message).not.toMatch(/test login/i);
  });

  test('an unknown code is named in the fallback', () => {
    // Still vague about meaning — we do not know it — but "something went
    // wrong" alone gave the user nothing to report and support nothing to act on.
    expect(settingsErrorMessage('brand_new_code')).toBe(
      'Something went wrong (brand_new_code). Please try again.',
    );
  });

  test('the booking service internal errors read as its problem, not the user\'s', () => {
    for (const code of ['recgov_login_exception', 'recgov_verify_exception', 'recgov_auth_check_exception']) {
      expect(settingsErrorMessage(code)).toBe('The booking service hit an internal error — check its logs.');
    }
  });

  test('the three add-to-cart misses read differently from each other', () => {
    // They all used to be "cart_not_added — someone else likely took it",
    // which is wrong advice for two of them: if rec.gov never offered the
    // dates or the button, retrying cannot help.
    const dates = settingsErrorMessage('recgov_dates_not_offered');
    const button = settingsErrorMessage('recgov_no_reserve_button');
    const raced = settingsErrorMessage('cart_not_added');

    expect(dates).toMatch(/does not offer those dates/i);
    expect(button).toMatch(/no way to book/i);
    expect(raced).toMatch(/try again/i);
    expect(new Set([dates, button, raced]).size).toBe(3);
    // Only the genuine race invites a retry.
    expect(dates).not.toMatch(/try again/i);
  });

  test('falls back for a missing code', () => {
    expect(settingsErrorMessage(undefined)).toBe(DEFAULT);
    expect(settingsErrorMessage(null)).toBe(DEFAULT);
    expect(settingsErrorMessage('')).toBe(DEFAULT);
  });

  test('a prototype-shadowing code still yields the fallback', () => {
    for (const code of ['toString', 'constructor', 'hasOwnProperty', '__proto__']) {
      expect(settingsErrorMessage(code)).toBe(`Something went wrong (${code}). Please try again.`);
    }
  });
});
