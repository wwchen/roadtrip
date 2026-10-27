import { describe, expect, test, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { POI_BLOCK_ORDER, type PoiBlockId } from '@/domain/poi/blocks';
import type { PoiBlockSlots } from '@/domain/poi/PoiPageShell';
import { dayFull, dayOpen, summary } from '@/domain/place/stay-fixtures';
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

const everyBlock = slotsOf(POI_BLOCK_ORDER);
const expectedOrderFromBlocks = () => POI_BLOCK_ORDER.map(blockTestId);

const props: PlaceSheetProps = {
  name: NAME,
  arrival: 'from-map',
  verdict: { name: NAME, answer: summary(), origin: null },
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
        verdict={{ ...props.verdict, answer: summary({ status: 'full', open_sites: 0 }) }}
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

  test('peek answers whether to go, and says nothing about which nights', () => {
    render(<PlaceSheet {...props} arrival="cold" blocks={everyBlock} />);
    expect(screen.queryByRole('group', { name: /nights/i })).not.toBeInTheDocument();
    expect(screen.queryByTestId(blockTestId('hero'))).not.toBeInTheDocument();
  });

  test('raising to half replaces the verdict with the nights rather than repeating it', () => {
    render(
      <PlaceSheet
        {...props}
        arrival="cold"
        verdict={{ ...props.verdict, answer: summary({ status: 'full', open_sites: 0 }) }}
        nights={{ ...props.nights, days: [dayOpen(WEEK[0], OPEN_SITES), dayOpen(WEEK[1], OPEN_SITES)] }}
      />,
    );
    raise(1);
    expect(screen.getByRole('group', { name: /nights/i })).toBeInTheDocument();
    expect(screen.queryByText('Full')).not.toBeInTheDocument();
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

  test('a dropped pin draws no block shell and no hairline', () => {
    const { container } = render(<PlaceSheet {...props} arrival="from-map" />);
    raise(1);
    expect(container.querySelector('.rt-poi')).toBeNull();
    expect(container.querySelectorAll('.rt-poi-rule')).toHaveLength(0);
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
});
