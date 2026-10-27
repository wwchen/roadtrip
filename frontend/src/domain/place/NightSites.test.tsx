import { describe, expect, test, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { AvailabilityDay } from '@/api/availability-api';
import { nightsCopy } from '@/lib/strings';
import { NightSites } from './NightSites';
import { dayFull, dayOpen } from './stay-fixtures';

describe('NightSites', () => {
  test('names the sites open on the selected night', () => {
    render(<NightSites day={dayOpen('2026-10-25', 3)} />);

    expect(screen.getAllByRole('listitem')).toHaveLength(3);
  });

  test('renders nothing when no night is selected', () => {
    const { container } = render(<NightSites day={null} />);

    expect(container).toBeEmptyDOMElement();
  });

  test('lists only the available cells, by their own campsite ids', () => {
    const mixed: AvailabilityDay = {
      date: '2026-10-25',
      status: 'available',
      watchable: false,
      cells: {
        '12': { status: 'available', watchable: false },
        '7': { status: 'reserved', watchable: false },
        '30': { status: 'available', watchable: false },
      },
    };
    render(<NightSites day={mixed} />);

    expect(screen.getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      nightsCopy.site(12),
      nightsCopy.site(30),
    ]);
  });

  test('picking a site reports its campsite id', async () => {
    const onPick = vi.fn();
    render(<NightSites day={dayOpen('2026-10-25', 3)} onPick={onPick} />);

    await userEvent.click(screen.getByRole('button', { name: nightsCopy.site(2) }));
    expect(onPick).toHaveBeenCalledWith(2);
  });

  test('a full night says nothing is open rather than listing sites', () => {
    render(<NightSites day={dayFull('2026-10-25')} />);

    expect(screen.queryByRole('listitem')).not.toBeInTheDocument();
    expect(screen.getByText(nightsCopy.noneOpen)).toBeInTheDocument();
  });

  test('a night with no cells says it has no detail rather than that it is full', () => {
    render(<NightSites day={{ ...dayOpen('2026-10-25', 0), cells: {} }} />);

    expect(screen.getByText(nightsCopy.noDetail)).toBeInTheDocument();
    expect(screen.queryByText(nightsCopy.noneOpen)).not.toBeInTheDocument();
  });
});
