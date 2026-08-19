import type { Meta, StoryObj } from '@storybook/react-vite';
import { AvailabilityTable, nightCells, type AvailabilityCell, type AvailabilityNight } from './AvailabilityTable';

// The block that justifies the routed page.
//
// Nothing here fetches: the table takes rows and nights as props, which is what
// lets a park page, a state page and the national-parks group page all fill the
// same `availability` slot from three different sources.
const meta = {
  title: 'POI/Availability table',
  component: AvailabilityTable,
  parameters: {
    layout: 'padded',
    docs: {
      description: {
        component:
          'One row per place, one column per night. Every cell prints a glyph as ' +
          'well as a colour, so the table survives greyscale and a colour-blind ' +
          'reader; the colour only makes it faster to scan.',
      },
    },
  },
} satisfies Meta<typeof AvailabilityTable>;

export default meta;
type Story = StoryObj<typeof meta>;

const DAYS = ['Fri', 'Sat', 'Sun', 'Mon', 'Tue', 'Wed', 'Thu'] as const;

/** Twelve nights from the 3rd, so the story shows the overflow the block is built for. */
const nights: AvailabilityNight[] = Array.from({ length: 12 }, (_, i) => ({
  date: `2026-07-${String(3 + i).padStart(2, '0')}`,
  label: DAYS[(i + 5) % DAYS.length],
  sublabel: `Jul ${3 + i}`,
}));

const row = (
  id: string,
  name: string,
  note: string | undefined,
  states: readonly AvailabilityCell[],
) => ({
  id,
  name,
  ...(note ? { note } : null),
  href: `?poi=${id}`,
  cells: nightCells(states.map((state, i) => [nights[i].date, state] as const)),
});

const open = (n?: number): AvailabilityCell => (n == null ? { status: 'available' } : { status: 'available', open: n });
const reserved: AvailabilityCell = { status: 'reserved' };
const firstCome: AvailabilityCell = { status: 'first_come' };
const closed: AvailabilityCell = { status: 'closed' };
const unknown: AvailabilityCell = { status: 'unknown' };
const past: AvailabilityCell = { status: 'past' };

export const ParkCampgrounds: Story = {
  args: {
    heading: 'Campgrounds by night',
    caption: '5 campgrounds in this park.',
    nights,
    places: [
      row('upper-pines', 'Upper Pines', 'Yosemite Valley', [
        past,
        open(6),
        open(2),
        reserved,
        reserved,
        open(1),
        open(9),
        open(4),
        reserved,
        reserved,
        open(3),
        open(7),
      ]),
      row('lower-pines', 'Lower Pines', 'Yosemite Valley', [
        past,
        reserved,
        reserved,
        reserved,
        reserved,
        reserved,
        open(1),
        open(1),
        reserved,
        reserved,
        reserved,
        open(2),
      ]),
      row('tuolumne', 'Tuolumne Meadows', 'Tioga Road', [
        past,
        firstCome,
        firstCome,
        firstCome,
        firstCome,
        firstCome,
        firstCome,
        firstCome,
        firstCome,
        firstCome,
        firstCome,
        firstCome,
      ]),
      row('bridalveil', 'Bridalveil Creek', 'Glacier Point Road', [
        past,
        closed,
        closed,
        closed,
        closed,
        open(11),
        open(14),
        open(12),
        open(8),
        open(8),
        open(5),
        open(5),
      ]),
      // A row the provider answered for only half the window — the partial case is
      // the one a booleans-and-blanks model gets wrong.
      row('hodgdon', 'Hodgdon Meadow', undefined, [
        past,
        open(3),
        reserved,
        unknown,
        unknown,
        unknown,
        unknown,
        unknown,
        unknown,
        unknown,
        unknown,
        unknown,
      ]),
    ],
  },
};

/** Nothing published yet — the block says so in a line rather than drawing a blank grid. */
export const Empty: Story = {
  args: {
    heading: 'Campgrounds by night',
    nights: [],
    places: [],
    emptyLabel: 'No nights are published for this park yet.',
  },
};
