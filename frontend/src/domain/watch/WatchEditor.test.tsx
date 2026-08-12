import { describe, expect, test, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { HttpError } from '@/api/http';
import type { Watch } from '@/api/watches-api';
import { WatchEditor } from './WatchEditor';
import { normalizeWatchCapabilities } from '@/lib/watch-windows';

const caps = (triggerKinds: string[], bookingActions: string[] = []) =>
  normalizeWatchCapabilities({ trigger_kinds: triggerKinds, booking_actions: bookingActions });

const watch = (overrides: Partial<Watch> = {}): Partial<Watch> => ({
  id: 1,
  trigger_kinds: ['slack_notify'],
  trigger_config: {},
  stop_when_triggered: true,
  ...overrides,
});

const open = (props: Partial<React.ComponentProps<typeof WatchEditor>> = {}) => {
  const onSave = vi.fn(async () => {});
  const view = render(
    <WatchEditor
      title="Watch Bowman Bay"
      subtitle="Tue, Aug 11"
      watch={null}
      capabilities={caps(['slack_notify'])}
      onSave={onSave}
      {...props}
    />,
  );
  return { ...view, onSave };
};

const toggle = (name: string) => screen.getByRole('checkbox', { name: new RegExp(name) });
const save = () => screen.getByRole('button', { name: /Set watch|Save/ });

describe('what the form offers', () => {
  test('shows only the triggers the provider supports', () => {
    open({ capabilities: caps(['slack_notify']) });

    expect(toggle('Slack')).toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: /Email/ })).toBeNull();
    expect(screen.queryByRole('checkbox', { name: /Add to cart/ })).toBeNull();
  });

  test('offers email when the provider can send it', () => {
    open({ capabilities: caps(['slack_notify', 'email_notify']) });

    expect(toggle('Email')).toBeInTheDocument();
  });

  test('keeps a trigger an existing watch already uses', () => {
    open({
      capabilities: caps(['slack_notify']),
      watch: watch({ trigger_kinds: ['slack_notify', 'atc'] }),
    });

    const atc = toggle('Add to cart');
    expect(atc).toBeChecked();
    // Enabled, so the user can turn it off — which is the only sensible action on a
    // trigger the provider has stopped servicing. The help text says as much.
    expect(atc).not.toBeDisabled();
    expect(screen.getByText('Unavailable for this watch scope.')).toBeInTheDocument();
  });

  test('hides add to cart entirely when nothing uses it', () => {
    open({ capabilities: caps(['slack_notify']), watch: watch({ trigger_kinds: ['slack_notify'] }) });

    expect(screen.queryByRole('checkbox', { name: /Add to cart/ })).toBeNull();
  });

  test('a new watch pre-ticks Slack so the form cannot be saved empty', () => {
    open({ capabilities: caps(['slack_notify']) });

    expect(toggle('Slack')).toBeChecked();
  });

  test('a provider without Slack pre-ticks its only channel instead', () => {
    open({ capabilities: caps(['email_notify']) });

    expect(screen.queryByRole('checkbox', { name: /Slack/ })).toBeNull();
    expect(toggle('Email')).toBeChecked();
    expect(screen.queryByRole('textbox')).toBeNull();
  });

  test('prefers Slack when the provider has both', () => {
    open({ capabilities: caps(['slack_notify', 'email_notify']) });

    expect(toggle('Slack')).toBeChecked();
    expect(toggle('Email')).not.toBeChecked();
  });

  test('reflects an existing watch exactly', () => {
    open({
      capabilities: caps(['slack_notify', 'email_notify']),
      watch: watch({
        trigger_kinds: ['email_notify'],
        trigger_config: {},
        stop_when_triggered: false,
      }),
    });

    expect(toggle('Slack')).not.toBeChecked();
    expect(toggle('Email')).toBeChecked();
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(toggle('Stop when triggered')).not.toBeChecked();
    // An existing watch is saved, not set.
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument();
  });

  test('ticking email does not offer a watch-level recipient override', async () => {
    open({ capabilities: caps(['slack_notify', 'email_notify']) });
    expect(screen.queryByRole('textbox')).toBeNull();

    await act(async () => {
      toggle('Email').click();
    });

    expect(screen.queryByRole('textbox')).toBeNull();
  });
});

describe('saving', () => {
  test('emits the trigger payload', async () => {
    const { onSave } = open({ capabilities: caps(['slack_notify']) });

    await act(async () => {
      save().click();
    });

    expect(onSave).toHaveBeenCalledWith({
      trigger_kinds: ['slack_notify'],
      trigger_config: {},
      stop_when_triggered: true,
    });
  });

  test('refuses a watch with no triggers', async () => {
    const { onSave } = open({ capabilities: caps(['slack_notify']) });

    await act(async () => {
      toggle('Slack').click();
    });
    await act(async () => {
      save().click();
    });

    expect(screen.getByRole('alert')).toHaveTextContent('Select at least one trigger.');
    expect(onSave).not.toHaveBeenCalled();
  });

  test('saves email intent without a recipient override', async () => {
    const { onSave } = open({ capabilities: caps(['email_notify']) });

    expect(toggle('Email')).toBeChecked();
    await act(async () => {
      save().click();
    });

    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({
        trigger_kinds: ['email_notify'],
        trigger_config: {},
      }),
    );
  });

  test('stays disabled after a successful save', async () => {
    const { onSave } = open({ capabilities: caps(['slack_notify']) });

    await act(async () => {
      save().click();
    });

    expect(save()).toBeDisabled();
    expect(onSave).toHaveBeenCalledTimes(1);
  });

  test('re-enables and explains after a failure', async () => {
    const failing = vi.fn(async () => {
      throw new HttpError('/api/watches', 400);
    });
    open({ capabilities: caps(['slack_notify']), onSave: failing });

    await act(async () => {
      save().click();
    });

    expect(screen.getByRole('alert')).toHaveTextContent('Could not save. Try again.');
    expect(save()).not.toBeDisabled();
  });

  test('names the two failures a user can act on', async () => {
    const withBody = (body: string) =>
      vi.fn(async () => {
        const error = new HttpError('/api/watches', 422);
        error.body = body;
        throw error;
      });

    const first = open({ capabilities: caps(['slack_notify']), onSave: withBody('unsupported_trigger') });
    await act(async () => save().click());
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Add to cart is not available for this watch.',
    );
    first.unmount();

    open({ capabilities: caps(['slack_notify']), onSave: withBody('invalid_trigger_config') });
    await act(async () => save().click());
    expect(screen.getByRole('alert')).toHaveTextContent('Check the trigger settings and try again.');
  });

  test('an aborted save reports nothing', async () => {
    const aborting = vi.fn(async () => {
      const error = new Error('aborted');
      error.name = 'AbortError';
      throw error;
    });
    open({ capabilities: caps(['slack_notify']), onSave: aborting });

    await act(async () => save().click());

    expect(screen.queryByRole('alert')).toBeNull();
  });
});

describe('removing', () => {
  test('is offered only when there is a watch to remove', () => {
    const { unmount } = open({ watch: null, onRemove: null });
    expect(screen.queryByRole('button', { name: 'Remove' })).toBeNull();
    unmount();

    open({ watch: watch(), onRemove: async () => {} });
    expect(screen.getByRole('button', { name: 'Remove' })).toBeInTheDocument();
  });

  test('calls back', async () => {
    const onRemove = vi.fn(async () => {});
    open({ watch: watch(), onRemove });

    await act(async () => {
      screen.getByRole('button', { name: 'Remove' }).click();
    });

    expect(onRemove).toHaveBeenCalledTimes(1);
  });
});
