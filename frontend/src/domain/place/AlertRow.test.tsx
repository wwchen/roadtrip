import { describe, expect, test, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { alertCopy, dayCopy, gateCopy } from '@/lib/strings';
import { AlertRow } from './AlertRow';

const NIGHT = '2026-10-27';
const NIGHT_LABEL = 'Tue, Oct 27';
const noop = () => undefined;

describe('AlertRow', () => {
  test('offers the alert when the gate is ready', async () => {
    const onCreate = vi.fn();
    render(<AlertRow night={NIGHT} gate="ready" onCreate={onCreate} />);

    await userEvent.click(screen.getByRole('button', { name: /alert me/i }));
    expect(onCreate).toHaveBeenCalledWith(NIGHT);
  });

  test('keeps its shape when signed out and offers the unlocking step', async () => {
    const onSignIn = vi.fn();
    const onCreate = vi.fn();
    render(<AlertRow night={NIGHT} gate="signed-out" onCreate={onCreate} onSignIn={onSignIn} />);

    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    expect(onSignIn).toHaveBeenCalled();
    expect(onCreate).not.toHaveBeenCalled();
    expect(screen.getByText(gateCopy.watchSignedOut)).toBeInTheDocument();
  });

  test('a signed-out row cannot be built without the step that unlocks it', () => {
    // @ts-expect-error a signed-out row requires onSignIn
    const missing = <AlertRow night={NIGHT} gate="signed-out" onCreate={noop} />;
    // @ts-expect-error only a signed-out row takes onSignIn
    const surplus = <AlertRow night={NIGHT} gate="ready" onCreate={noop} onSignIn={noop} />;

    expect([missing, surplus]).toHaveLength(2);
  });

  test('falls back to inert text only when blocked', () => {
    render(<AlertRow night={NIGHT} gate="blocked" onCreate={noop} />);

    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(screen.getByText(dayCopy.unsupported)).toBeInTheDocument();
  });

  test('names the night it would watch, in the calendar day it was given', () => {
    render(<AlertRow night={NIGHT} gate="ready" onCreate={noop} />);

    expect(screen.getByText(NIGHT_LABEL)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: alertCopy.createFor(NIGHT_LABEL) })).toBeInTheDocument();
  });

  test('pending disables the button and keeps the night in the in-flight label', async () => {
    const onCreate = vi.fn();
    render(<AlertRow night={NIGHT} gate="ready" pending onCreate={onCreate} />);

    expect(screen.getByText(alertCopy.creating)).toBeInTheDocument();
    const button = screen.getByRole('button', { name: alertCopy.creatingFor(NIGHT_LABEL) });
    expect(button).toBeDisabled();
    await userEvent.click(button);
    expect(onCreate).not.toHaveBeenCalled();
  });

  test('pending changes nothing about a signed-out row', async () => {
    const onSignIn = vi.fn();
    render(<AlertRow night={NIGHT} gate="signed-out" pending onCreate={noop} onSignIn={onSignIn} />);

    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    expect(onSignIn).toHaveBeenCalled();
  });
});
