import { describe, expect, test } from 'vitest';
import { settingsErrorCopy, settingsErrorMessage } from './settings-errors';


describe('settingsErrorMessage', () => {
  test('maps each known backend code', () => {
    expect(settingsErrorCopy('invalid_field')).toBe('A field was refused.');
    expect(settingsErrorCopy('slack_invalid_auth')).toBe('Slack rejected this token.');
    expect(settingsErrorCopy('slack_not_configured')).toBe('No Slack token is set.');
    expect(settingsErrorCopy('slack_send_failed')).toBe("Couldn't send to Slack.");
    expect(settingsErrorCopy('encryption_unavailable')).toBe(
      "Secret storage isn't configured on the server.",
    );
    expect(settingsErrorCopy('email_send_failed')).toBe("Couldn't send the test email.");
  });

  test('maps the Recreation.gov booking codes', () => {
    expect(settingsErrorCopy('login_failed')).toBe(
      'Recreation.gov rejected these credentials.',
    );
    expect(settingsErrorCopy('mfa_invalid')).toBe(
      'That code was rejected. Start the login again for a new one.',
    );
    expect(settingsErrorCopy('captcha_required')).toBe(
      'Recreation.gov showed a challenge we cannot solve. Try again in a moment.',
    );
    expect(settingsErrorCopy('companion_unavailable')).toBe(
      "The booking service isn't reachable right now.",
    );
  });

  test('a login page the booking service could not drive does not blame the credentials', () => {
    const message = settingsErrorCopy('login_page_changed');

    expect(message).toBe(
      "Recreation.gov's sign-in page has changed, so the sign-in could not finish. Your credentials were not rejected — the booking service needs attention.",
    );
    expect(message).not.toBe(settingsErrorCopy('login_failed'));
  });

  test('the add-to-cart codes the web app answers have their own copy', () => {
    expect(settingsErrorCopy('booking_unavailable')).toBe(
      "Booking isn't available right now — try again shortly.",
    );
    expect(settingsErrorCopy('relay_timeout')).toBe(
      'The booking service took too long to answer. If you were adding a site, check your cart before trying again.',
    );
    expect(settingsErrorCopy('invalid_window')).toBe('Check-out must be after check-in.');
  });

  test('the transient companion codes read as "try again", not as failures', () => {
    expect(settingsErrorCopy('profile_busy')).toBe(
      'Your Recreation.gov session is busy, often with a sign-in still waiting for its verification code. Enter that code, or try again in a few minutes.',
    );
    expect(settingsErrorCopy('browser_cap_reached')).toBe(
      'The booking service is at capacity — try again shortly.',
    );
  });

  test('the two dead-challenge codes both end the challenge, and only one names the deadline', () => {
    // Both mean "start over" — "try again" (the fallback's advice) is the one
    // wrong instruction, because the backend has already cleared the challenge
    // and the code field is gone. They are worded apart on purpose: `expired`
    // is the user having been slow, so the deadline is worth naming; `unknown`
    // is a challenge that was never ours, where a deadline would confuse.
    const expired = settingsErrorCopy('mfa_challenge_expired');
    const unknown = settingsErrorCopy('mfa_challenge_unknown');

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
    const expected = 'Your Recreation.gov session expired — test login in Settings.';

    expect(settingsErrorCopy('recgov_session_expired')).toBe(expected);
    expect(settingsErrorCopy('recgov_spa_logged_out')).toBe(expected);
    expect(settingsErrorCopy('recgov_refresh_failed')).toBe(expected);
  });

  test('a refused automated sign-in points at the credentials, not at the session', () => {
    // Distinct from the two above: here we *did* sign in again with the saved
    // credentials and Recreation.gov refused, so a changed password is on the table.
    const message = settingsErrorCopy('recgov_login_failed');

    expect(message).toBe(
      'Recreation.gov would not sign you back in — check your credentials in Settings.',
    );
    expect(message).not.toBe(settingsErrorCopy('recgov_spa_logged_out'));
  });

  test('an unreadable cart is not reported as a signed-out session', () => {
    // "Test login again" would send the user in circles: the session is fine.
    const message = settingsErrorCopy('recgov_cart_unreachable');

    expect(message).toBe(
      "You're signed in, but Recreation.gov's cart could not be read — try again shortly.",
    );
    expect(message).not.toMatch(/test login/i);
  });

  test('the booking service internal errors read as its problem, not the user\'s', () => {
    for (const code of ['recgov_login_exception', 'recgov_verify_exception', 'recgov_auth_check_exception']) {
      expect(settingsErrorCopy(code)).toBe('The booking service hit an internal error — check its logs.');
    }
  });

  test('the three add-to-cart misses read differently from each other', () => {
    // They all used to be "cart_not_added — someone else likely took it",
    // which is wrong advice for two of them: if Recreation.gov never offered the
    // dates or the button, retrying cannot help.
    const dates = settingsErrorCopy('recgov_dates_not_offered');
    const button = settingsErrorCopy('recgov_no_reserve_button');
    const raced = settingsErrorCopy('cart_not_added');

    expect(dates).toMatch(/does not offer those dates/i);
    expect(button).toMatch(/no way to book/i);
    expect(raced).toMatch(/try again/i);
    expect(new Set([dates, button, raced]).size).toBe(3);
    // Only the genuine race invites a retry.
    expect(dates).not.toMatch(/try again/i);
  });

  test('the generic add-to-cart codes name the campground’s own provider, not Recreation.gov', () => {
    // `BookingActionService` returns these for any provider's cart, so they
    // must not hardcode Recreation.gov — Campflare's failure should say Campflare.
    expect(settingsErrorCopy('cart_not_added', 'Campflare')).toBe(
      'Campflare would not add it — someone else likely took it. Try again.',
    );
    expect(settingsErrorCopy('credentials_required', 'Campflare')).toBe(
      'Add your Campflare credentials in Settings first.',
    );
  });

  test('the generic add-to-cart codes read neutrally when no provider is known', () => {
    expect(settingsErrorCopy('cart_not_added')).toBe(
      'Could not add it to your cart — someone else likely took it. Try again.',
    );
    expect(settingsErrorCopy('credentials_required')).toBe(
      'Add your booking credentials in Settings first.',
    );
    expect(settingsErrorCopy('cart_not_added')).not.toMatch(/Recreation\.gov/i);
    expect(settingsErrorCopy('credentials_required')).not.toMatch(/Recreation\.gov/i);
  });

  test('an unknown code renders verbatim with its detail, never a generic sentence', () => {
    expect(settingsErrorMessage({ code: 'brand_new_code', detail: 'it broke', status: 502 })).toBe(
      'brand_new_code: it broke · HTTP 502',
    );
    expect(settingsErrorMessage({ error: 'brand_new_code' })).toBe('brand_new_code');
  });

  test('a known code keeps its raw code and detail beside the sentence', () => {
    expect(settingsErrorMessage({ code: 'invalid_field', detail: 'slack_channel must start with #', status: 400 })).toBe(
      'A field was refused. (invalid_field: slack_channel must start with # · HTTP 400)',
    );
  });

  test('a failure with no code keeps the exception class and message', () => {
    expect(settingsErrorMessage(new TypeError('Failed to fetch'))).toBe('TypeError: Failed to fetch');
  });

  test('a missing code has no copy of its own', () => {
    expect(settingsErrorCopy(undefined)).toBeUndefined();
    expect(settingsErrorCopy(null)).toBeUndefined();
    expect(settingsErrorCopy('')).toBeUndefined();
  });

  test('a prototype-shadowing code has no copy and renders verbatim', () => {
    for (const code of ['toString', 'constructor', 'hasOwnProperty', '__proto__']) {
      expect(settingsErrorCopy(code)).toBeUndefined();
      expect(settingsErrorMessage({ error: code })).toBe(code);
    }
  });
});
