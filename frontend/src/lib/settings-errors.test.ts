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

  // Total by construction: call sites write settingsErrorMessage(err.code) with no
  // guard, so every input has to yield a string.
  test('falls back for an unknown code', () => {
    expect(settingsErrorMessage('brand_new_code')).toBe(DEFAULT);
  });

  test('falls back for a missing code', () => {
    expect(settingsErrorMessage(undefined)).toBe(DEFAULT);
    expect(settingsErrorMessage(null)).toBe(DEFAULT);
    expect(settingsErrorMessage('')).toBe(DEFAULT);
  });

  // A code colliding with an Object.prototype member must not leak a function or
  // a stray truthy value into the UI — the original's plain-object lookup was
  // exposed to this, and a message is the only acceptable return.
  test('a prototype-shadowing code still yields the fallback', () => {
    for (const code of ['toString', 'constructor', 'hasOwnProperty', '__proto__']) {
      expect(settingsErrorMessage(code)).toBe(DEFAULT);
    }
  });
});
