import { describe, expect, test } from 'vitest';
import { BatchAccumulator } from './batch.js';
import type { FlushCounts } from '../repo/bulk.js';

const NONE: FlushCounts = { upserted: 0, skipped: 0 };

function recording(batchSize: number, counts: (size: number) => FlushCounts = () => NONE) {
  const batches: number[][] = [];
  const accumulator = new BatchAccumulator<number>({
    batchSize,
    write: async (records) => {
      batches.push([...records]);
      return counts(records.length);
    },
  });
  return { accumulator, batches };
}

describe('BatchAccumulator', () => {
  test('the accumulator flushes at the sink batch size and once more at the end', async () => {
    const { accumulator, batches } = recording(2);
    for (const record of [1, 2, 3]) await accumulator.add(record);
    await accumulator.flush();
    expect(batches.map((batch) => batch.length)).toEqual([2, 1]);
  });

  test('a final flush with nothing buffered does not call the sink', async () => {
    const { accumulator, batches } = recording(2);
    for (const record of [1, 2]) await accumulator.add(record);
    await accumulator.flush();
    expect(batches).toHaveLength(1);
  });

  test('add returns the counts of the flush it triggered and zeroes otherwise', async () => {
    const { accumulator } = recording(2, (size) => ({ upserted: size, skipped: 1 }));
    expect(await accumulator.add(1)).toEqual({ upserted: 0, skipped: 0 });
    expect(await accumulator.add(2)).toEqual({ upserted: 2, skipped: 1 });
    expect(await accumulator.flush()).toEqual({ upserted: 0, skipped: 0 });
  });

  test('records are handed to the sink in arrival order', async () => {
    const { accumulator, batches } = recording(3);
    for (const record of [5, 4, 3, 2, 1]) await accumulator.add(record);
    await accumulator.flush();
    expect(batches).toEqual([
      [5, 4, 3],
      [2, 1],
    ]);
  });
});
