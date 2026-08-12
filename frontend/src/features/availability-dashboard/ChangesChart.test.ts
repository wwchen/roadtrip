import { describe, expect, test } from 'vitest';
import type { AvailabilityChange } from '@/api/availability-dashboard-api';
import { buildDatasets } from './ChangesChart';

const change = (fields: Partial<AvailabilityChange> = {}): AvailabilityChange => ({
  campsite_id: 1,
  campsite_name: 'Loop A 001',
  target_date: '2026-07-08',
  observed_at: '2026-07-01T00:00:00Z',
  from_status: null,
  to_status: 'available',
  ...fields,
});

describe('buildDatasets', () => {
  test('one dataset per campsite and target date', () => {
    const datasets = buildDatasets([
      change({ campsite_name: 'Loop A 001', target_date: '2026-07-08' }),
      change({ campsite_name: 'Loop A 001', target_date: '2026-07-09' }),
      change({ campsite_name: 'Loop B 002', target_date: '2026-07-08' }),
    ]);

    expect(datasets.map((d) => d.label)).toEqual([
      'Loop A 001 @ 2026-07-08',
      'Loop A 001 @ 2026-07-09',
      'Loop B 002 @ 2026-07-08',
    ]);
  });

  test('falls back to the campsite id when there is no name', () => {
    const [dataset] = buildDatasets([change({ campsite_name: null, campsite_id: 42 })]);
    expect(dataset.label).toBe('42 @ 2026-07-08');
  });

  test('sorts each series chronologically', () => {
    const [dataset] = buildDatasets([
      change({ observed_at: '2026-07-03T00:00:00Z', to_status: 'reserved' }),
      change({ observed_at: '2026-07-01T00:00:00Z', to_status: 'available' }),
      change({ observed_at: '2026-07-02T00:00:00Z', to_status: 'closed' }),
    ]);

    expect(dataset.data.map((p) => p.x)).toEqual([
      Date.parse('2026-07-01T00:00:00Z'),
      Date.parse('2026-07-02T00:00:00Z'),
      Date.parse('2026-07-03T00:00:00Z'),
    ]);
  });

  test('maps statuses onto their y positions', () => {
    const datasets = buildDatasets([
      change({ target_date: 'a', to_status: 'available' }),
      change({ target_date: 'b', to_status: 'first_come' }),
      change({ target_date: 'c', to_status: 'reserved' }),
      change({ target_date: 'd', to_status: 'closed' }),
    ]);
    expect(datasets.map((d) => d.data[0].y)).toEqual([2, 1.5, 1, 0]);
  });

  test('places first_come between reserved and available', () => {
    const [[fc], [reserved], [available]] = [
      buildDatasets([change({ to_status: 'first_come' })]),
      buildDatasets([change({ to_status: 'reserved' })]),
      buildDatasets([change({ to_status: 'available' })]),
    ];
    expect(reserved.data[0].y).toBeLessThan(fc.data[0].y);
    expect(fc.data[0].y).toBeLessThan(available.data[0].y);
  });

  test('an unrecognised status drops to the unknown row', () => {
    const [dataset] = buildDatasets([
      change({ to_status: 'something_new' as AvailabilityChange['to_status'] }),
    ]);
    expect(dataset.data[0].y).toBe(-1);
  });

  test('steps before each reading', () => {
    const [dataset] = buildDatasets([change()]);
    expect(dataset.stepped).toBe('before');
  });

  test('no changes means no datasets', () => {
    expect(buildDatasets([])).toEqual([]);
  });
});
