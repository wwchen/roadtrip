import type { ChangeEvent } from 'react';
import { describe, expect, test, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { TextField } from '@ui';

describe('TextField', () => {
  test('a date field reports a pick through onChange', () => {
    const onChange = vi.fn((event: ChangeEvent<HTMLInputElement>) => event.target.value);
    render(<TextField id="tf-date" label="Start date" type="date" onChange={onChange} />);

    fireEvent.input(screen.getByLabelText('Start date'), { target: { value: '2026-09-11' } });

    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange.mock.results[0]?.value).toBe('2026-09-11');
  });

  test('the change event that follows the same pick is not reported twice', () => {
    const onChange = vi.fn();
    render(<TextField id="tf-date" label="Start date" type="date" onChange={onChange} />);
    const input = screen.getByLabelText('Start date');

    fireEvent.input(input, { target: { value: '2026-09-11' } });
    fireEvent.change(input, { target: { value: '2026-09-11' } });
    fireEvent.change(input, { target: { value: '2026-09-12' } });

    expect(onChange).toHaveBeenCalledTimes(2);
  });

  test('a text field reports once per keystroke', () => {
    const onChange = vi.fn();
    render(<TextField id="tf-text" label="POI ID" onChange={onChange} />);

    fireEvent.input(screen.getByLabelText('POI ID'), { target: { value: '4' } });

    expect(onChange).toHaveBeenCalledTimes(1);
  });
});
