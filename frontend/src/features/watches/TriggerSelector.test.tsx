import { describe, expect, test, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { TriggerState } from '@/lib/watch-triggers';
import { TriggerSelector } from './TriggerSelector';

const STATE: TriggerState = {
  slackNotify: true,
  slackChannel: '#alerts',
  emailNotify: false,
  addToCart: false,
  stopWhenTriggered: false,
};

describe('TriggerSelector', () => {
  test('follows a value the form changes outside it', () => {
    const { rerender } = render(<TriggerSelector value={STATE} onChange={vi.fn()} />);

    rerender(<TriggerSelector value={{ ...STATE, emailNotify: true, slackChannel: '#ops' }} onChange={vi.fn()} />);

    expect(screen.getByLabelText('Email')).toBeChecked();
    expect(screen.getByLabelText('Channel')).toHaveValue('#ops');
  });
});
