import { describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { StayAvailabilityStatus } from '@/api/availability-api';
import { stayCopy } from '@/lib/strings';
import { PlaceVerdict } from './PlaceVerdict';
import { driveTimeLabel } from './drive-origin';
import { summary } from './stay-fixtures';

describe('PlaceVerdict', () => {
  test.each([
    ['open', 3, '3 sites open'],
    ['few_left', 1, '1 site left'],
    ['full', 0, 'Full'],
    ['closed_for_season', 0, 'Closed for the season'],
    ['no_data', 0, 'No data for these dates'],
  ] as const)('renders the %s status from stayCopy', (status, open, expected) => {
    render(<PlaceVerdict name="Upper Pines" answer={summary({ status, open_sites: open })} origin={null} />);

    expect(screen.getByText(expected)).toBeInTheDocument();
  });

  test('a refusal says why, not how many', () => {
    render(
      <PlaceVerdict
        name="Upper Pines"
        origin={null}
        answer={{ poi_id: 1, outcome: 'refused', reason: 'beyond_booking_horizon' }}
      />,
    );

    expect(screen.getByText('Not bookable this far ahead yet')).toBeInTheDocument();
  });

  test('an unknown status falls back rather than rendering blank', () => {
    render(
      <PlaceVerdict
        name="Upper Pines"
        origin={null}
        answer={summary({ status: 'teleported' as StayAvailabilityStatus })}
      />,
    );

    expect(screen.getByText(stayCopy.error)).toBeInTheDocument();
  });

  test('names the origin of the drive time', () => {
    render(<PlaceVerdict name="Upper Pines" answer={summary()} origin={{ label: 'here', minutes: 250 }} />);

    expect(screen.getByText(/4h 10m from here/)).toBeInTheDocument();
  });

  test('omits the freshness line when a cached answer has no observed_at', () => {
    render(<PlaceVerdict name="Upper Pines" answer={summary({ from_cache: true })} origin={null} />);

    expect(screen.queryByText(/^Checked/)).not.toBeInTheDocument();
  });

  test('shows the freshness line for a cached answer that has one', () => {
    render(
      <PlaceVerdict
        name="Upper Pines"
        origin={null}
        answer={summary({ from_cache: true, observed_at: '2026-10-27T15:47:00Z' })}
      />,
    );

    expect(screen.getByText(/^Checked/)).toBeInTheDocument();
  });

  test('a closed season says when it reopens', () => {
    render(
      <PlaceVerdict
        name="Upper Pines"
        origin={null}
        reopensOn="2027-05-14"
        answer={summary({ status: 'closed_for_season', open_sites: 0 })}
      />,
    );

    expect(screen.getByText('Reopens May 14')).toBeInTheDocument();
  });

  test('a pending check shows the name and that it is checking', () => {
    render(<PlaceVerdict name="Upper Pines" answer={null} origin={null} />);

    expect(screen.getByText('Upper Pines')).toBeInTheDocument();
    expect(screen.getByText(stayCopy.pending)).toBeInTheDocument();
  });

  test('a reopening date does not appear on a campground that is not closed', () => {
    render(<PlaceVerdict name="Upper Pines" origin={null} reopensOn="2027-05-14" answer={summary()} />);

    expect(screen.queryByText(/reopens/i)).not.toBeInTheDocument();
  });

  test('lists the reasons it was ranked', () => {
    render(
      <PlaceVerdict name="Upper Pines" origin={null} answer={summary()} reasons={['Near water', 'Shade']} />,
    );

    expect(screen.getByText('Near water')).toBeInTheDocument();
    expect(screen.getByText('Shade')).toBeInTheDocument();
  });
});

describe('driveTimeLabel', () => {
  test.each([
    [{ label: 'here', minutes: 250 }, '4h 10m from here'],
    [{ label: 'home', minutes: 45 }, '45m from home'],
    [{ label: 'home', minutes: null }, 'home'],
  ])('%j reads %s', (origin, expected) => {
    expect(driveTimeLabel(origin)).toBe(expected);
  });
});
