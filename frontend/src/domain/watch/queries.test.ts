import { describe, expect, test } from 'vitest';
import { watchListQuery } from './queries';

describe('watchListQuery', () => {
  test('inherits the client retry policy for transient failures', () => {
    expect(watchListQuery({ status: 'active' })).not.toHaveProperty('retry');
  });
});
