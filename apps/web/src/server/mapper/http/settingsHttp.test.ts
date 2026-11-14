import { describe, expect, test } from 'vitest';
import type { SettingsView } from '../../service/settings/model';
import { bookingBody, notificationsDto, parseProfilePatch, settingsAnswer } from './settingsHttp';

const VIEW: SettingsView = {
  profile: { id: 7n, email: 'camper@example.com', emailVerified: true, displayName: null, theme: 'system', roles: [] },
  notifications: { notificationEmail: 'camper@example.com', slackChannel: null, slackConfigured: false, slackTokenHint: null },
  booking: { configured: false },
};

test('a notifications row with a channel and a hint shows both, and null fields are left out', () => {
  expect(
    notificationsDto({ notificationEmail: 'alerts@example.com', slackChannel: '#camp', slackConfigured: true, slackTokenHint: 'abcd' }),
  ).toEqual({ notification_email: 'alerts@example.com', slack_channel: '#camp', slack_configured: true, slack_token_hint: 'abcd' });
  expect(
    notificationsDto({ notificationEmail: 'camper@example.com', slackChannel: null, slackConfigured: false, slackTokenHint: null }),
  ).toEqual({ notification_email: 'camper@example.com', slack_configured: false });
});

describe('bookingBody', () => {
  test('a configured booking shows the username', () => {
    expect(bookingBody({ configured: true, username: 'rec@example.com' })).toEqual({ recgov_configured: true, recgov_username: 'rec@example.com' });
  });

  test('an unconfigured booking shows only that', () => {
    expect(bookingBody({ configured: false })).toEqual({ recgov_configured: false });
  });
});

describe('parseProfilePatch', () => {
  test('keeps a display name as given and a known theme', () => {
    expect(parseProfilePatch({ display_name: ' Bill ', theme: 'dark' })).toEqual({ kind: 'ok', value: { displayName: ' Bill ', theme: 'dark' } });
  });

  test('null and absent fields leave the patch empty', () => {
    expect(parseProfilePatch({ display_name: null, theme: null })).toEqual({ kind: 'ok', value: {} });
    expect(parseProfilePatch({})).toEqual({ kind: 'ok', value: {} });
  });

  test.each([[undefined], [null], [[]], ['x'], [{ display_name: 7 }], [{ theme: 7 }]])('%j is invalid_body', (body) => {
    expect(parseProfilePatch(body)).toMatchObject({
      kind: 'invalid',
      answer: { status: 400, body: { error: 'invalid_body', detail: 'Request body is not valid JSON for this endpoint' } },
    });
  });

  test('a blank display name is invalid_field', () => {
    expect(parseProfilePatch({ display_name: '  ' })).toMatchObject({
      kind: 'invalid',
      answer: { status: 400, body: { error: 'invalid_field', detail: 'display_name must not be blank' } },
    });
  });

  test('an unknown theme is invalid_field', () => {
    expect(parseProfilePatch({ theme: 'neon' })).toMatchObject({
      kind: 'invalid',
      answer: { status: 400, body: { error: 'invalid_field', detail: 'theme must be one of light, dark, system' } },
    });
  });
});

describe('settingsAnswer', () => {
  test('a null display name leaves display_name out', () => {
    expect(settingsAnswer(VIEW)).toEqual({
      status: 200,
      body: {
        profile: { login_email: 'camper@example.com', is_email_verified: true, roles: [], theme: 'system' },
        notifications: { notification_email: 'camper@example.com', slack_configured: false },
        booking: { recgov_configured: false },
      },
    });
  });
});
