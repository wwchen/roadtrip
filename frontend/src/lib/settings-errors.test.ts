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
      'Recreation.gov rejected these credentials.',
    );
    expect(settingsErrorMessage('mfa_invalid')).toBe(
      'That code was rejected. Start the login again for a new one.',
    );
    expect(settingsErrorMessage('captcha_required')).toBe(
      'Recreation.gov showed a challenge we cannot solve. Try again in a moment.',
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
