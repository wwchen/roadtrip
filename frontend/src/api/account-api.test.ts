// Ports web/api/account-api.test.mjs to Vitest, assertion for assertion, plus
// coverage for sendEmailTest (which the node suite did not reach).
import { afterEach, describe, expect, test, vi } from 'vitest';
import {
  clearSlack,
  fetchSettings,
  sendEmailTest,
  sendSlackTest,
  updateNotifications,
  updateProfile,
} from './account-api';
import { jsonResponse, noContentResponse, stubFetch, textResponse } from '@/test/fetch-stub';

afterEach(() => vi.unstubAllGlobals());

describe('fetchSettings', () => {
  test('issues GET /api/settings', async () => {
    const fetchStub = stubFetch(jsonResponse({ profile: { display_name: 'Alice' } }));

    const result = await fetchSettings();

    expect(fetchStub.last.url).toBe('/api/settings');
    expect(fetchStub.last.init.method).toBeUndefined(); // GET is the default
    expect(result).toEqual({ profile: { display_name: 'Alice' } });
  });
});

describe('updateProfile', () => {
  test('issues PUT /api/settings/profile with display_name', async () => {
    const fetchStub = stubFetch(jsonResponse({}));

    await updateProfile({ display_name: 'Bob', theme: 'dark' });

    expect(fetchStub.last.url).toBe('/api/settings/profile');
    expect(fetchStub.last.method).toBe('PUT');
    expect(fetchStub.last.body).toEqual({ display_name: 'Bob', theme: 'dark' });
    expect(fetchStub.last.init.credentials).toBe('same-origin');
  });
});

describe('updateNotifications', () => {
  test('issues PUT /api/settings/notifications', async () => {
    const fetchStub = stubFetch(jsonResponse({}));

    await updateNotifications({
      notification_email: 'a@b.com',
      slack_channel: '#alerts',
      slack_token: 'xoxb-123',
    });

    expect(fetchStub.last.url).toBe('/api/settings/notifications');
    expect(fetchStub.last.method).toBe('PUT');
    expect(fetchStub.last.body).toEqual({
      notification_email: 'a@b.com',
      slack_channel: '#alerts',
      slack_token: 'xoxb-123',
    });
  });

  // The backend reads a missing slack_token as "unchanged". Sending null would
  // be a write, so the key has to be absent, not present-and-null.
  test('drops a null slack_token', async () => {
    const fetchStub = stubFetch(jsonResponse({}));

    await updateNotifications({
      notification_email: 'a@b.com',
      slack_channel: '#alerts',
      slack_token: null,
    });

    expect(fetchStub.last.body).not.toHaveProperty('slack_token');
    expect(fetchStub.last.body).toEqual({
      notification_email: 'a@b.com',
      slack_channel: '#alerts',
    });
  });

  test('drops an undefined slack_token', async () => {
    const fetchStub = stubFetch(jsonResponse({}));

    await updateNotifications({ slack_channel: '#alerts' });

    expect(fetchStub.last.body).not.toHaveProperty('slack_token');
    expect(fetchStub.last.body).toEqual({ slack_channel: '#alerts' });
  });

  test('sends an empty body when given no fields', async () => {
    const fetchStub = stubFetch(jsonResponse({}));

    await updateNotifications();

    expect(fetchStub.last.body).toEqual({});
  });

  // An empty string is a real value — clearing the notification email — and must
  // survive, unlike null/undefined.
  test('keeps an empty-string field', async () => {
    const fetchStub = stubFetch(jsonResponse({}));

    await updateNotifications({ notification_email: '' });

    expect(fetchStub.last.body).toEqual({ notification_email: '' });
  });
});

describe('clearSlack', () => {
  test('issues DELETE /api/settings/notifications/slack and resolves null on 204', async () => {
    const fetchStub = stubFetch(noContentResponse());

    const result = await clearSlack();

    expect(fetchStub.last.url).toBe('/api/settings/notifications/slack');
    expect(fetchStub.last.method).toBe('DELETE');
    expect(result).toBeNull();
  });
});

describe('sendSlackTest', () => {
  test('issues POST .../slack/test with the channel', async () => {
    const fetchStub = stubFetch(jsonResponse({ sent: true }));

    await sendSlackTest('#general');

    expect(fetchStub.last.url).toBe('/api/settings/notifications/slack/test');
    expect(fetchStub.last.method).toBe('POST');
    expect(fetchStub.last.body).toEqual({ channel: '#general' });
  });

  test.each([[null], [undefined]])(
    'omits the channel when %j, so the server uses the stored one',
    async (channel) => {
      const fetchStub = stubFetch(jsonResponse({ sent: true }));

      await sendSlackTest(channel);

      expect(fetchStub.last.body).not.toHaveProperty('channel');
    },
  );
});

describe('sendEmailTest', () => {
  test('issues POST .../email/test with an empty body', async () => {
    const fetchStub = stubFetch(jsonResponse({ sent: true, recipient: 'a@b.com' }));

    const result = await sendEmailTest();

    expect(fetchStub.last.url).toBe('/api/settings/notifications/email/test');
    expect(fetchStub.last.method).toBe('POST');
    expect(fetchStub.last.body).toEqual({});
    expect(result).toEqual({ sent: true, recipient: 'a@b.com' });
  });
});

describe('error code surfacing', () => {
  test('a failing call surfaces .code from the error response body', async () => {
    stubFetch(jsonResponse({ error: 'invalid_field', detail: 'bad input' }, 422));

    await expect(updateProfile({ display_name: '', theme: 'system' })).rejects.toMatchObject({
      name: 'HttpError',
      status: 422,
      code: 'invalid_field',
    });
  });

  test('a failing call with a non-JSON body leaves .code undefined', async () => {
    stubFetch(textResponse('<html>500</html>', 500));

    const error = await fetchSettings().catch((e: unknown) => e);

    expect(error).toMatchObject({ name: 'HttpError', status: 500 });
    expect((error as { code?: string }).code).toBeUndefined();
  });
});
