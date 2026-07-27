import assert from 'node:assert/strict';
import test from 'node:test';

import { settingsErrorMessage } from './settings-errors.js';

test('invalid_field returns a check-fields message', () => {
  assert.equal(settingsErrorMessage('invalid_field'), 'Please check the highlighted fields.');
});

test('slack_invalid_auth returns a token-rejected message', () => {
  assert.equal(settingsErrorMessage('slack_invalid_auth'), 'Slack rejected this token.');
});

test('slack_not_configured returns a no-token message', () => {
  assert.equal(settingsErrorMessage('slack_not_configured'), 'No Slack token is set.');
});

test('slack_send_failed returns a send-failed message', () => {
  assert.equal(settingsErrorMessage('slack_send_failed'), "Couldn't send to Slack.");
});

test('encryption_unavailable returns a server-config message', () => {
  assert.equal(settingsErrorMessage('encryption_unavailable'), "Secret storage isn't configured on the server.");
});

test('unknown code returns the default message', () => {
  const msg = settingsErrorMessage('totally_unknown_code');
  assert.equal(typeof msg, 'string');
  assert.ok(msg.length > 0);
});

test('undefined returns the default message', () => {
  const msg = settingsErrorMessage(undefined);
  assert.equal(typeof msg, 'string');
  assert.ok(msg.length > 0);
});
