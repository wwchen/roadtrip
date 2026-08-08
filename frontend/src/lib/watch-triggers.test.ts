import { describe, expect, test } from 'vitest';
import type { Watch } from '@/api/watches-api';
import {
  buildTriggerPayload,
  TRIGGER_KIND_ATC,
  TRIGGER_KIND_EMAIL_NOTIFY,
  TRIGGER_KIND_SLACK_NOTIFY,
  triggerStateOf,
  watchEmailTo,
  watchHasTrigger,
  watchSlackChannel,
  watchStopWhenTriggered,
  type TriggerState,
} from './watch-triggers';

const watch = (fields: Partial<Watch>): Partial<Watch> => fields;

const state = (fields: Partial<TriggerState> = {}): TriggerState => ({
  slackNotify: false,
  slackChannel: '',
  emailNotify: false,
  emailTo: '',
  addToCart: false,
  stopWhenTriggered: true,
  ...fields,
});

describe('wire values', () => {
  test('match the backend trigger kinds', () => {
    expect(TRIGGER_KIND_SLACK_NOTIFY).toBe('slack_notify');
    expect(TRIGGER_KIND_EMAIL_NOTIFY).toBe('email_notify');
    expect(TRIGGER_KIND_ATC).toBe('atc');
  });
});

describe('watchHasTrigger', () => {
  test('finds a listed kind', () => {
    expect(watchHasTrigger(watch({ trigger_kinds: ['slack_notify'] }), 'slack_notify')).toBe(true);
  });

  test('is false for an unlisted kind', () => {
    expect(watchHasTrigger(watch({ trigger_kinds: ['slack_notify'] }), 'atc')).toBe(false);
  });

  test.each([[null], [undefined], [{}]])('is false for %j', (w) => {
    expect(watchHasTrigger(w as Partial<Watch>, 'slack_notify')).toBe(false);
  });
});

describe('watchSlackChannel', () => {
  test('reads the namespaced config', () => {
    expect(
      watchSlackChannel(watch({ trigger_config: { slack_notify: { channel: '#alerts' } } })),
    ).toBe('#alerts');
  });

  // Watches created before the config was namespaced per kind still carry a
  // top-level `channel`; dropping it would silently clear the channel on save.
  test('falls back to a legacy top-level channel', () => {
    expect(watchSlackChannel(watch({ trigger_config: { channel: '#old' } }))).toBe('#old');
  });

  test('prefers the namespaced value over the legacy one', () => {
    expect(
      watchSlackChannel(
        watch({ trigger_config: { channel: '#old', slack_notify: { channel: '#new' } } }),
      ),
    ).toBe('#new');
  });

  test.each([
    [{}],
    [{ slack_notify: {} }],
    [{ slack_notify: { channel: '   ' } }],
    [{ slack_notify: 'not an object' }],
    [{ channel: 42 }],
  ])('is empty for the config %j', (config) => {
    expect(watchSlackChannel(watch({ trigger_config: config }))).toBe('');
  });

  test('reads the camelCase form the availability week used internally', () => {
    const w = { triggerConfig: { slack_notify: { channel: '#camel' } } } as unknown as Partial<Watch>;
    expect(watchSlackChannel(w)).toBe('#camel');
  });
});

describe('watchEmailTo', () => {
  test('reads and trims the address', () => {
    expect(watchEmailTo(watch({ trigger_config: { email_notify: { to: '  a@b.test ' } } }))).toBe(
      'a@b.test',
    );
  });

  test.each([[{}], [{ email_notify: {} }], [{ email_notify: { to: '  ' } }]])(
    'is empty for the config %j',
    (config) => {
      expect(watchEmailTo(watch({ trigger_config: config }))).toBe('');
    },
  );

  // Unlike Slack there is no legacy top-level fallback, matching the original.
  test('does not read a top-level to', () => {
    expect(watchEmailTo(watch({ trigger_config: { to: 'a@b.test' } }))).toBe('');
  });
});

describe('watchStopWhenTriggered', () => {
  test('returns the fallback for no watch', () => {
    expect(watchStopWhenTriggered(null)).toBe(true);
    expect(watchStopWhenTriggered(null, false)).toBe(false);
  });

  test('returns the fallback when the field is absent', () => {
    expect(watchStopWhenTriggered(watch({}), false)).toBe(false);
  });

  test.each([
    [true, true],
    [false, false],
  ])('reads %j', (value, expected) => {
    expect(watchStopWhenTriggered(watch({ stop_when_triggered: value }))).toBe(expected);
  });

  test('reads the camelCase form', () => {
    const w = { stopWhenTriggered: false } as unknown as Partial<Watch>;
    expect(watchStopWhenTriggered(w)).toBe(false);
  });
});

describe('triggerStateOf', () => {
  // Slack defaults on for a new watch: it is the trigger every user has set up,
  // and a watch with no trigger cannot notify anyone.
  test('defaults a new watch to Slack on, email off, stop on', () => {
    expect(triggerStateOf(null)).toEqual({
      slackNotify: true,
      slackChannel: '',
      emailNotify: false,
      emailTo: '',
      addToCart: false,
      stopWhenTriggered: true,
    });
  });

  test('reads an existing watch faithfully', () => {
    expect(
      triggerStateOf(
        watch({
          trigger_kinds: ['email_notify', 'atc'],
          trigger_config: { email_notify: { to: 'a@b.test' } },
          stop_when_triggered: false,
        }),
      ),
    ).toEqual({
      slackNotify: false,
      slackChannel: '',
      emailNotify: true,
      emailTo: 'a@b.test',
      addToCart: true,
      stopWhenTriggered: false,
    });
  });
});

describe('buildTriggerPayload', () => {
  test('lists enabled kinds in a stable order', () => {
    expect(
      buildTriggerPayload(state({ slackNotify: true, emailNotify: true, addToCart: true }))
        .trigger_kinds,
    ).toEqual(['slack_notify', 'email_notify', 'atc']);
  });

  test('nests each config under its kind', () => {
    expect(
      buildTriggerPayload(
        state({
          slackNotify: true,
          slackChannel: '#alerts',
          emailNotify: true,
          emailTo: 'a@b.test',
        }),
      ).trigger_config,
    ).toEqual({
      slack_notify: { channel: '#alerts' },
      email_notify: { to: 'a@b.test' },
    });
  });

  test('trims config values', () => {
    expect(
      buildTriggerPayload(state({ slackNotify: true, slackChannel: '  #alerts  ' }))
        .trigger_config,
    ).toEqual({ slack_notify: { channel: '#alerts' } });
  });

  // The kind is the user's intent; a blank channel lets the backend fall back to
  // the account's stored default. Only the config entry is dropped.
  test('keeps the kind but omits the config when the field is blank', () => {
    const payload = buildTriggerPayload(state({ slackNotify: true, slackChannel: '   ' }));

    expect(payload.trigger_kinds).toEqual(['slack_notify']);
    expect(payload.trigger_config).toEqual({});
  });

  test('omits config for a kind that is switched off', () => {
    const payload = buildTriggerPayload(state({ slackNotify: false, slackChannel: '#alerts' }));

    expect(payload.trigger_kinds).toEqual([]);
    expect(payload.trigger_config).toEqual({});
  });

  test('carries stop_when_triggered through', () => {
    expect(buildTriggerPayload(state({ stopWhenTriggered: false })).stop_when_triggered).toBe(false);
  });

  test('round-trips a watch through read and rebuild', () => {
    const original = watch({
      trigger_kinds: ['slack_notify', 'email_notify'],
      trigger_config: { slack_notify: { channel: '#alerts' }, email_notify: { to: 'a@b.test' } },
      stop_when_triggered: false,
    });

    expect(buildTriggerPayload(triggerStateOf(original))).toEqual({
      trigger_kinds: ['slack_notify', 'email_notify'],
      trigger_config: { slack_notify: { channel: '#alerts' }, email_notify: { to: 'a@b.test' } },
      stop_when_triggered: false,
    });
  });
});
