// The watch trigger form.
//
// The capability gates are the point: a trigger the provider cannot service must not be
// offered, and — the subtler half — a trigger an *existing* watch already uses must not
// be silently dropped by opening its editor.
import { describe, expect, test, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { HttpError } from '@/api/http';
import type { Watch } from '@/api/watches-api';
import { WatchEditor } from './WatchEditor';
import { normalizeWatchCapabilities } from './watch-windows';

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

  // The gate that protects a user's existing configuration: the provider stopped
  // advertising add-to-cart, but this watch uses it, so the control stays visible.
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

  // The other half of that gate: a provider with no add-to-cart and a watch that does
  // not use it gets no row at all, rather than a disabled control explaining itself.
  test('hides add to cart entirely when nothing uses it', () => {
    open({ capabilities: caps(['slack_notify']), watch: watch({ trigger_kinds: ['slack_notify'] }) });

    expect(screen.queryByRole('checkbox', { name: /Add to cart/ })).toBeNull();
  });

  test('a new watch pre-ticks Slack so the form cannot be saved empty', () => {
    open({ capabilities: caps(['slack_notify']) });

    expect(toggle('Slack')).toBeChecked();
  });

  // ...but only when Slack is actually possible, or the default would fail on save.
  // Email takes the default instead on a provider that has no Slack: this form is what
  // the day panel's "Set watch" opens there, and a single toggle that starts off can
  // only fail its own "select at least one trigger" check.
  test('a provider without Slack pre-ticks its only channel instead', () => {
    open({ capabilities: caps(['email_notify']) });

    expect(screen.queryByRole('checkbox', { name: /Slack/ })).toBeNull();
    expect(toggle('Email')).toBeChecked();
    // Ticked, but not yet valid — the address is the next thing to fill in.
    expect(screen.getByRole('textbox')).toHaveValue('');
  });

  // Slack when both are possible: it needs no further input, where email needs an
  // address, so it is the one default that can be saved as it opens.
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
        trigger_config: { email_notify: { to: 'camp@example.com' } },
        stop_when_triggered: false,
      }),
    });

    expect(toggle('Slack')).not.toBeChecked();
    expect(toggle('Email')).toBeChecked();
    expect(screen.getByRole('textbox')).toHaveValue('camp@example.com');
    expect(toggle('Stop when triggered')).not.toBeChecked();
    // An existing watch is saved, not set.
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument();
  });

  // A controlled checkbox, because flipping it has to reveal the address field in the
  // same render — which is why these are not LDS's uncontrolled form controls.
  test('ticking email reveals the address field', async () => {
    open({ capabilities: caps(['slack_notify', 'email_notify']) });
    expect(screen.queryByRole('textbox')).toBeNull();

    await act(async () => {
      toggle('Email').click();
    });

    expect(screen.getByRole('textbox')).toBeInTheDocument();
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

  // The API accepts a watch with no triggers and then silently never notifies anyone,
  // so this is caught here rather than left to the server.
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

  // Email opens pre-ticked on an email-only provider, so this is what saving straight
  // away looks like there.
  test('refuses email with no address', async () => {
    const { onSave } = open({ capabilities: caps(['email_notify']) });

    expect(toggle('Email')).toBeChecked();
    await act(async () => {
      save().click();
    });

    expect(screen.getByRole('alert')).toHaveTextContent('Enter an email address.');
    expect(onSave).not.toHaveBeenCalled();
  });

  test('includes the address once given', async () => {
    const { onSave } = open({ capabilities: caps(['slack_notify', 'email_notify']) });

    await act(async () => {
      toggle('Email').click();
    });
    const input = screen.getByRole('textbox');
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(
        input,
        'camp@example.com',
      );
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await act(async () => {
      save().click();
    });

    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({
        trigger_kinds: ['slack_notify', 'email_notify'],
        trigger_config: { email_notify: { to: 'camp@example.com' } },
      }),
    );
  });

  // A double-tap on a 240px popover is easy; a second save would create a second watch.
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

  // An aborted request is a navigation, not a failure — reporting it would put an
  // error in a popover the user has already dismissed.
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
