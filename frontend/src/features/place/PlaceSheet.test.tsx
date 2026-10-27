import { describe, expect, test, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { POI_BLOCK_ORDER, type PoiBlockId } from '@/domain/poi/blocks';
import type { PoiBlockSlots } from '@/domain/poi/PoiPageShell';
import { dayFull, dayOpen, summary } from '@/domain/place/stay-fixtures';
import { matrixCopy, stayCopy } from '@/lib/strings';
import { PlaceSheet, type PlaceSheetProps } from './PlaceSheet';

const NAME = 'Upper Pines';
const SHEET = 'place-sheet';
const WEEK = ['2026-10-25', '2026-10-26'];
const OPEN_SITES = 3;
const SITE_NAMES = new Map([
  [1, 'Loop A / 12'],
  [2, ''],
]);

const noop = () => undefined;

const blockTestId = (id: PoiBlockId) => `block-${id}`;

function slotsOf(ids: readonly PoiBlockId[]): PoiBlockSlots {
  const slots: PoiBlockSlots = {};
  for (const id of ids) slots[id] = <div data-testid={blockTestId(id)} />;
  return slots;
}

const HERO: PoiBlockId = 'hero';
const IDENTITY: PoiBlockId = 'identity';
const SHEET_SKIPS: readonly PoiBlockId[] = [HERO, IDENTITY];
const SUBTITLE = 'Yosemite National Park';

const everyBlock = slotsOf(POI_BLOCK_ORDER);
const expectedOrderFromBlocks = () =>
  POI_BLOCK_ORDER.filter((id) => !SHEET_SKIPS.includes(id)).map(blockTestId);

const verdict = { name: NAME, answer: summary(), origin: null };

const props: PlaceSheetProps = {
  name: NAME,
  arrival: 'from-map',
  verdict,
  nights: {
    days: [dayOpen(WEEK[0], OPEN_SITES), dayFull(WEEK[1])],
    dates: WEEK,
    selected: null,
    onSelect: noop,
  },
  blocks: {},
};

const grabber = () => screen.getByRole('button', { name: NAME });
const raise = (times: number) => {
  for (let step = 0; step < times; step += 1) fireEvent.keyDown(grabber(), { key: 'ArrowUp' });
};

describe('PlaceSheet', () => {
  test('arriving from the map opens at half, not peek', () => {
    render(<PlaceSheet {...props} arrival="from-map" />);
    expect(screen.getByTestId(SHEET)).toHaveAttribute('data-detent', 'half');
  });

  test('arriving cold opens at peek', () => {
    render(<PlaceSheet {...props} arrival="cold" />);
    expect(screen.getByTestId(SHEET)).toHaveAttribute('data-detent', 'peek');
  });

  test('the verdict ignores the grid when the stay says full', () => {
    render(
      <PlaceSheet
        {...props}
        arrival="cold"
        verdict={{ ...verdict, answer: summary({ status: 'full', open_sites: 0 }) }}
        nights={{ ...props.nights, days: [dayOpen(WEEK[0], OPEN_SITES), dayOpen(WEEK[1], OPEN_SITES)] }}
      />,
    );
    expect(screen.getByText('Full')).toBeInTheDocument();
    expect(screen.queryByText(/sites open/)).not.toBeInTheDocument();
  });

  test('renders body blocks in POI_BLOCK_GROUPS order', () => {
    render(<PlaceSheet {...props} arrival="cold" blocks={everyBlock} />);
    raise(2);
    const rendered = screen.getAllByTestId(/^block-/).map((el) => el.dataset.testid);
    expect(rendered).toEqual(expectedOrderFromBlocks());
  });

  test('peek carries the one action it names, and says nothing about which nights', () => {
    render(<PlaceSheet {...props} arrival="cold" blocks={everyBlock} />);
    expect(screen.getByTestId(blockTestId('actions'))).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: /nights/i })).not.toBeInTheDocument();
    expect(screen.queryByTestId(blockTestId('availability'))).not.toBeInTheDocument();
    expect(screen.queryByTestId(blockTestId('glance'))).not.toBeInTheDocument();
  });

  test('the sheet is a heading at every height, so the place it is about is one', () => {
    const { container } = render(<PlaceSheet {...props} arrival="cold" blocks={everyBlock} />);
    expect(container.querySelector('h2')).toHaveTextContent(NAME);

    raise(1);
    expect(container.querySelector('h2')).toHaveTextContent(NAME);

    raise(1);
    expect(container.querySelector('h2')).toHaveTextContent(NAME);
  });

  test('a shut grid states the reason the server gave instead of a strip of nights', () => {
    const failure = <span data-testid="nights-failure" />;
    render(<PlaceSheet {...props} arrival="from-map" nightsFailure={failure} />);
    expect(screen.getByTestId('nights-failure')).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: /nights/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/no data/i)).not.toBeInTheDocument();
  });

  test('a place with nothing but a failed grid still opens its stay section', () => {
    const failure = <span data-testid="nights-failure" />;
    const { container } = render(
      <PlaceSheet
        {...props}
        arrival="from-map"
        nights={{ ...props.nights, days: [], dates: [] }}
        nightsFailure={failure}
      />,
    );
    expect(container.querySelector('.rt-place-sheet__stay')).not.toBeNull();
    expect(screen.getByTestId('nights-failure')).toBeInTheDocument();
  });

  test('a dismissable sheet offers a close control and answers Escape', async () => {
    const onClose = vi.fn();
    render(<PlaceSheet {...props} arrival="cold" onClose={onClose} />);

    const close = screen.getByRole('button', { name: /close/i });
    fireEvent.click(close);
    expect(onClose).toHaveBeenCalledTimes(1);

    fireEvent.keyDown(close, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  test('a sheet with nowhere to close to draws no close control', () => {
    render(<PlaceSheet {...props} arrival="cold" />);
    expect(screen.queryByRole('button', { name: /close/i })).not.toBeInTheDocument();
  });

  test('the sheet never draws the blocks its header replaces, at any height', () => {
    render(<PlaceSheet {...props} arrival="cold" blocks={everyBlock} />);
    raise(2);
    expect(screen.getByTestId(SHEET)).toHaveAttribute('data-detent', 'full');
    expect(screen.getByTestId(blockTestId('actions'))).toBeInTheDocument();
    expect(screen.queryByTestId(blockTestId(HERO))).not.toBeInTheDocument();
    expect(screen.queryByTestId(blockTestId(IDENTITY))).not.toBeInTheDocument();
  });

  test('names the place once at every height, whatever the caller puts in the identity block', () => {
    const named: PoiBlockSlots = { identity: <div data-testid={blockTestId(IDENTITY)}>{NAME}</div> };
    render(<PlaceSheet {...props} arrival="cold" blocks={named} />);
    expect(screen.getByTestId(SHEET)).toHaveAttribute('data-detent', 'peek');
    expect(screen.getAllByText(NAME)).toHaveLength(1);

    raise(1);
    expect(screen.getByTestId(SHEET)).toHaveAttribute('data-detent', 'half');
    expect(screen.getAllByText(NAME)).toHaveLength(1);

    raise(1);
    expect(screen.getByTestId(SHEET)).toHaveAttribute('data-detent', 'full');
    expect(screen.getAllByText(NAME)).toHaveLength(1);
  });

  test('names the place once at every height when there is no verdict to carry', () => {
    render(<PlaceSheet {...props} arrival="cold" verdict={undefined} />);
    expect(screen.getAllByText(NAME)).toHaveLength(1);

    raise(1);
    expect(screen.getAllByText(NAME)).toHaveLength(1);

    raise(1);
    expect(screen.getAllByText(NAME)).toHaveLength(1);
  });

  test('the header carries the secondary line at every height', () => {
    render(<PlaceSheet {...props} arrival="cold" subtitle={SUBTITLE} blocks={everyBlock} />);
    expect(screen.getByText(SUBTITLE)).toBeInTheDocument();

    raise(1);
    expect(screen.getByText(SUBTITLE)).toBeInTheDocument();

    raise(1);
    expect(screen.getByText(SUBTITLE)).toBeInTheDocument();
  });

  test('what it is like on arrival waits for full', () => {
    render(<PlaceSheet {...props} arrival="from-map" blocks={everyBlock} />);
    expect(screen.getByTestId(blockTestId('actions'))).toBeInTheDocument();
    expect(screen.queryByTestId(blockTestId('gettingThere'))).not.toBeInTheDocument();
    expect(screen.queryByTestId(blockTestId('goodToKnow'))).not.toBeInTheDocument();

    raise(1);
    expect(screen.getByTestId(blockTestId('gettingThere'))).toBeInTheDocument();
    expect(screen.getByTestId(blockTestId('goodToKnow'))).toBeInTheDocument();
  });

  test('the stay answer is on screen at peek, half and full', () => {
    render(
      <PlaceSheet
        {...props}
        arrival="cold"
        verdict={{ ...verdict, answer: summary({ status: 'full', open_sites: 0 }) }}
        nights={{ ...props.nights, days: [dayOpen(WEEK[0], OPEN_SITES), dayOpen(WEEK[1], OPEN_SITES)] }}
      />,
    );
    expect(screen.getAllByText(stayCopy.result.full())).toHaveLength(1);

    raise(1);
    expect(screen.getByRole('group', { name: /nights/i })).toBeInTheDocument();
    expect(screen.getAllByText(stayCopy.result.full())).toHaveLength(1);

    raise(1);
    expect(screen.getAllByText(stayCopy.result.full())).toHaveLength(1);
  });

  test('a stay that could not be answered states why at every height', () => {
    const refused = { poi_id: 1, outcome: 'refused', reason: 'beyond_booking_horizon' } as const;
    const line = stayCopy.errorFor(refused.outcome, refused.reason);
    render(<PlaceSheet {...props} arrival="cold" verdict={{ ...verdict, answer: refused }} />);
    expect(screen.getAllByText(line)).toHaveLength(1);

    raise(1);
    expect(screen.getAllByText(line)).toHaveLength(1);

    raise(1);
    expect(screen.getAllByText(line)).toHaveLength(1);
  });

  test('the deciding facts stay at peek, one fact at one height', () => {
    render(
      <PlaceSheet
        {...props}
        arrival="cold"
        verdict={{
          ...verdict,
          origin: { label: 'here', minutes: 250 },
          priceFrom: 'From $28',
          fits: 'Fits 8 people',
          reasons: ['Near water'],
          answer: summary({ from_cache: true, observed_at: '2026-10-27T15:47:00Z' }),
        }}
      />,
    );
    const facts = [/4h 10m from here/, /From \$28/, /Fits 8 people/, 'Near water', /^Checked/];
    for (const fact of facts) expect(screen.getByText(fact)).toBeInTheDocument();

    raise(1);
    for (const fact of facts) expect(screen.queryByText(fact)).not.toBeInTheDocument();

    raise(1);
    for (const fact of facts) expect(screen.queryByText(fact)).not.toBeInTheDocument();
  });

  test('the sites it names are the selected night\'s own, looked up in the days it was given', () => {
    render(
      <PlaceSheet
        {...props}
        nights={{ ...props.nights, selected: WEEK[0] }}
        siteNames={SITE_NAMES}
      />,
    );
    expect(screen.getByText('Loop A / 12')).toBeInTheDocument();
    expect(screen.getByText('Site #2')).toBeInTheDocument();
    expect(screen.getByText('Site #3')).toBeInTheDocument();
  });

  test('a night with no open sites names none, whatever the stay answer said', () => {
    render(<PlaceSheet {...props} nights={{ ...props.nights, selected: WEEK[1] }} />);
    expect(screen.getByText(/nothing open this night/i)).toBeInTheDocument();
  });

  test('offers the alert row at half, and not at peek', () => {
    const alert = { night: WEEK[1], gate: 'ready', onCreate: vi.fn() } as const;
    render(<PlaceSheet {...props} arrival="cold" alert={alert} />);
    expect(screen.queryByRole('button', { name: /alert me/i })).not.toBeInTheDocument();

    raise(1);
    expect(screen.getByRole('button', { name: /alert me/i })).toBeInTheDocument();
  });

  test('a dropped pin draws no availability band, no block shell and no hairline', () => {
    const { container } = render(
      <PlaceSheet {...props} arrival="from-map" nights={{ ...props.nights, days: [], dates: [] }} />,
    );
    raise(1);
    expect(screen.queryByRole('group', { name: /nights/i })).not.toBeInTheDocument();
    expect(container.querySelector('.rt-place-sheet__stay')).toBeNull();
    expect(container.querySelector('.rt-poi')).toBeNull();
    expect(container.querySelectorAll('.rt-poi-rule')).toHaveLength(0);
  });

  test('a place with no nights in view still offers an alert when there is one', () => {
    const alert = { night: WEEK[1], gate: 'ready', onCreate: vi.fn() } as const;
    const { container } = render(
      <PlaceSheet {...props} arrival="from-map" nights={{ ...props.nights, days: [], dates: [] }} alert={alert} />,
    );
    expect(container.querySelector('.rt-place-sheet__stay')).not.toBeNull();
    expect(screen.getByRole('button', { name: /alert me/i })).toBeInTheDocument();
  });

  test('a place with nothing above the fold draws no fold rule', () => {
    const { container } = render(
      <PlaceSheet {...props} arrival="from-map" blocks={slotsOf(['specs', 'nearby'])} />,
    );
    raise(1);
    expect(screen.getByTestId(blockTestId('specs'))).toBeInTheDocument();
    expect(screen.getByTestId(blockTestId('nearby'))).toBeInTheDocument();
    expect(container.querySelectorAll('.rt-poi-rule')).toHaveLength(1);
    expect(container.querySelectorAll('.rt-poi-rule--fold')).toHaveLength(0);
  });

  test('a place with no stay to check names itself and claims nothing', () => {
    render(<PlaceSheet {...props} arrival="cold" verdict={undefined} subtitle={SUBTITLE} />);
    expect(screen.getAllByText(NAME)).toHaveLength(1);
    expect(screen.getByText(SUBTITLE)).toBeInTheDocument();
    expect(screen.queryByText(/sites open/)).not.toBeInTheDocument();
  });

  test('a notice the stay check needs read stays up at peek', () => {
    const notice = <span data-testid="stay-notice" />;
    render(<PlaceSheet {...props} arrival="cold" notice={notice} />);
    expect(screen.getByTestId('stay-notice')).toBeInTheDocument();
  });

  test('full replaces the nights strip with the grid, and keeps the place detail under it', () => {
    const { container } = render(<PlaceSheet {...props} arrival="from-map" blocks={everyBlock} />);
    expect(screen.getByRole('group', { name: /nights/i })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: matrixCopy.group })).not.toBeInTheDocument();

    raise(1);
    expect(screen.getByTestId(SHEET)).toHaveAttribute('data-detent', 'full');
    expect(screen.queryByRole('group', { name: /nights/i })).not.toBeInTheDocument();
    const grid = screen.getByRole('region', { name: matrixCopy.group });
    expect(grid).toBeInTheDocument();

    const detail = screen.getByTestId(blockTestId('gettingThere'));
    expect(grid.compareDocumentPosition(detail) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    const order = [...container.querySelectorAll('[data-testid^="block-"]')].map(
      (el) => (el as HTMLElement).dataset.testid,
    );
    expect(order).toEqual(expectedOrderFromBlocks());
  });

  test('the grid at full draws a cell per site per night, and claims nothing about the stay', () => {
    render(
      <PlaceSheet
        {...props}
        arrival="from-map"
        verdict={{ ...verdict, answer: summary({ status: 'full', open_sites: 0 }) }}
        nights={{ ...props.nights, days: [dayOpen(WEEK[0], OPEN_SITES), dayOpen(WEEK[1], OPEN_SITES)] }}
      />,
    );
    raise(1);

    expect(screen.getAllByText(stayCopy.result.full())).toHaveLength(1);
    expect(screen.queryByText(/sites open/)).not.toBeInTheDocument();
    expect(screen.getAllByRole('rowheader')).toHaveLength(OPEN_SITES);
    expect(screen.getAllByLabelText(/available$/)).toHaveLength(OPEN_SITES * WEEK.length);
  });

  test('a shut grid states the reason at full too, in place of the matrix', () => {
    const failure = <span data-testid="nights-failure" />;
    render(<PlaceSheet {...props} arrival="from-map" nightsFailure={failure} />);
    raise(1);

    expect(screen.getByTestId('nights-failure')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: matrixCopy.group })).not.toBeInTheDocument();
  });

  test('the alert for a shut night still rides beside the grid at full', () => {
    const alert = { night: WEEK[1], gate: 'ready', onCreate: vi.fn() } as const;
    render(<PlaceSheet {...props} arrival="from-map" alert={alert} />);
    raise(1);

    expect(screen.getByRole('region', { name: matrixCopy.group })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /alert me/i })).toBeInTheDocument();
  });

  test('a place with no nights in view draws no grid at full either', () => {
    render(
      <PlaceSheet {...props} arrival="from-map" nights={{ ...props.nights, days: [], dates: [] }} />,
    );
    raise(1);

    expect(screen.queryByRole('region', { name: matrixCopy.group })).not.toBeInTheDocument();
  });

  test('the page rule still marks its own fold, now inside full', () => {
    const { container } = render(
      <PlaceSheet {...props} arrival="from-map" blocks={slotsOf(['goodToKnow', 'specs'])} />,
    );
    raise(1);
    expect(container.querySelectorAll('.rt-poi-rule--fold')).toHaveLength(1);
  });
});
