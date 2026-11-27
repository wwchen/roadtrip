import { describe, expect, test, vi } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MultiSelect, type MultiSelectOption, type MultiSelectProps } from './MultiSelect';

const OPTIONS: MultiSelectOption[] = [
  { value: 'A', label: 'Alpha', count: 21 },
  { value: 'B', label: 'Bravo', count: 14 },
  { value: 'C', label: 'Charlie', count: 9 },
];

const MANY: MultiSelectOption[] = Array.from({ length: 11 }, (_, i) => ({
  value: `v${i}`,
  label: i === 3 ? 'Oregon State Parks' : `Agency ${i}`,
  count: 20 - i,
}));

function renderSelect(props: Partial<MultiSelectProps> = {}) {
  const handlers = { onToggle: vi.fn(), onClear: vi.fn() };
  render(
    <MultiSelect
      id="agency"
      label="Agency"
      options={OPTIONS}
      selected={[]}
      allLabel="All agencies"
      triggerText="All agencies"
      searchLabel="Find an agency"
      noMatchText="No agency matches"
      emptyText="Nothing in view yet"
      {...handlers}
      {...props}
    />,
  );
  return handlers;
}

const trigger = () => screen.getByRole('button', { name: /Agency/ });
const menu = () => screen.getByRole('group', { name: 'Agency' });

describe('MultiSelect', () => {
  test('the trigger is labelled by the field label and shows the summary', () => {
    renderSelect({ triggerText: 'Alpha +1' });

    expect(trigger()).toHaveTextContent('Alpha +1');
    expect(trigger()).toHaveAttribute('aria-expanded', 'false');
  });

  test('opening lists every option in the order given, with its count', async () => {
    renderSelect();

    await userEvent.click(trigger());

    expect(trigger()).toHaveAttribute('aria-expanded', 'true');
    const rows = within(menu()).getAllByRole('checkbox').map((box) => box.closest('label')?.textContent);
    expect(rows).toEqual(['All agencies', 'Alpha', 'Bravo', 'Charlie']);
    expect(within(menu()).getByText('21')).toBeInTheDocument();
  });

  test('ticking an option reports it', async () => {
    const { onToggle } = renderSelect();
    await userEvent.click(trigger());

    await userEvent.click(within(menu()).getByLabelText('Bravo'));

    expect(onToggle).toHaveBeenCalledWith('B');
  });

  test('the All row is ticked with nothing selected, and clears a selection', async () => {
    const { onClear } = renderSelect({ selected: ['A'] });
    await userEvent.click(trigger());

    expect(within(menu()).getByLabelText('All agencies')).not.toBeChecked();
    expect(within(menu()).getByLabelText('Alpha')).toBeChecked();

    await userEvent.click(within(menu()).getByLabelText('All agencies'));

    expect(onClear).toHaveBeenCalledTimes(1);
  });

  test('a short list has no search box', async () => {
    renderSelect();
    await userEvent.click(trigger());

    expect(screen.queryByRole('textbox', { name: 'Find an agency' })).not.toBeInTheDocument();
  });

  test('a long list gets a search box that narrows the rows', async () => {
    renderSelect({ options: MANY });
    await userEvent.click(trigger());

    await userEvent.type(screen.getByRole('textbox', { name: 'Find an agency' }), 'oregon');

    const rows = within(menu()).getAllByRole('checkbox').map((box) => box.closest('label')?.textContent);
    expect(rows).toEqual(['All agencies', 'Oregon State Parks']);
  });

  test('a search with no match says so', async () => {
    renderSelect({ options: MANY });
    await userEvent.click(trigger());

    await userEvent.type(screen.getByRole('textbox', { name: 'Find an agency' }), 'zzz');

    expect(within(menu()).getByText('No agency matches')).toBeInTheDocument();
  });

  test('with no options and no search, it says nothing is in view rather than that a search missed', async () => {
    renderSelect({ options: [] });
    await userEvent.click(trigger());

    expect(within(menu()).getByText('Nothing in view yet')).toBeInTheDocument();
    expect(within(menu()).queryByText('No agency matches')).toBeNull();
  });

  test('the menu follows its trigger in tab order, and tabbing out of it closes it', async () => {
    render(
      <>
        <MultiSelect
          id="agency"
          label="Agency"
          options={OPTIONS}
          selected={[]}
          onToggle={vi.fn()}
          onClear={vi.fn()}
          allLabel="All agencies"
          triggerText="All agencies"
          searchLabel="Find an agency"
          noMatchText="No agency matches"
          emptyText="Nothing in view yet"
        />
        <button type="button">Next field</button>
      </>,
    );
    trigger().focus();
    await userEvent.keyboard('{Enter}');

    await userEvent.tab();
    expect(within(menu()).getByLabelText('All agencies')).toHaveFocus();

    for (let i = 0; i < OPTIONS.length; i += 1) await userEvent.tab();
    await userEvent.tab();

    expect(screen.getByRole('button', { name: 'Next field' })).toHaveFocus();
    expect(screen.queryByRole('group', { name: 'Agency' })).not.toBeInTheDocument();
  });

  test('Escape closes the menu and puts focus back on the trigger', async () => {
    renderSelect();
    await userEvent.click(trigger());

    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    await userEvent.keyboard('{Escape}');

    expect(screen.queryByRole('group', { name: 'Agency' })).not.toBeInTheDocument();
    expect(trigger()).toHaveFocus();
  });

  test('a click outside closes the menu', async () => {
    renderSelect();
    await userEvent.click(trigger());

    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    await userEvent.click(document.body);

    expect(screen.queryByRole('group', { name: 'Agency' })).not.toBeInTheDocument();
  });

  test('a click inside the menu keeps it open', async () => {
    renderSelect();
    await userEvent.click(trigger());
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });

    await userEvent.click(within(menu()).getByLabelText('Alpha'));

    expect(menu()).toBeInTheDocument();
  });
});
