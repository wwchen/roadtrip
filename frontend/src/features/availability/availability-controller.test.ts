import { describe, expect, test } from 'vitest';
import {
  availabilityViewReducer,
  createAvailabilityViewState,
  type AvailabilityViewAction,
} from './availability-controller';

const week = new Date(2026, 7, 9);
const armed = { campsiteId: '7', date: '2026-08-10' };

describe('availability view transitions', () => {
  test('changing week clears state that belongs to the previous week', () => {
    const state = {
      ...createAvailabilityViewState(week),
      selectedDate: '2026-08-10',
      selectedSiteId: '7',
      sitesExpanded: true,
      armedBook: armed,
      calendarOpen: true,
      watchTarget: { anchor: document.body, date: '2026-08-10' },
    };
    const nextWeek = new Date(2026, 7, 16);

    const next = availabilityViewReducer(state, { type: 'weekChanged', weekStart: nextWeek });

    expect(next).toMatchObject({
      weekStart: nextWeek,
      selectedDate: null,
      selectedSiteId: null,
      sitesExpanded: false,
      armedBook: null,
      calendarOpen: false,
      watchTarget: null,
    });
  });

  test('selecting a date opens its sites and selecting it again closes them', () => {
    const selected = availabilityViewReducer(createAvailabilityViewState(week), {
      type: 'dateSelected',
      date: '2026-08-10',
    });
    const cleared = availabilityViewReducer(selected, {
      type: 'dateSelected',
      date: '2026-08-10',
    });

    expect(selected).toMatchObject({ selectedDate: '2026-08-10', sitesExpanded: true });
    expect(cleared).toMatchObject({ selectedDate: null, sitesExpanded: false });
  });

  test.each<AvailabilityViewAction>([
    { type: 'filtersChanged', filters: { query: 'lake', loop: '', type: '', sort: 'site' } },
    { type: 'siteSelected', campsiteId: '9' },
    { type: 'dateSelected', date: '2026-08-11' },
  ])('$type disarms a booking whose visual position may change', (action) => {
    const state = { ...createAvailabilityViewState(week), armedBook: armed };
    expect(availabilityViewReducer(state, action).armedBook).toBeNull();
  });
});
