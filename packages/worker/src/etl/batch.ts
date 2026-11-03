import type { FlushCounts } from '../repo/bulk.js';

export interface TerminalSink<OUT> {
  readonly batchSize: number;
  write(records: OUT[]): Promise<FlushCounts>;
}

const NO_COUNTS: FlushCounts = { upserted: 0, skipped: 0 };

export class BatchAccumulator<OUT> {
  private records: OUT[] = [];

  constructor(private readonly sink: TerminalSink<OUT>) {}

  async add(record: OUT): Promise<FlushCounts> {
    this.records.push(record);
    return this.records.length >= this.sink.batchSize ? this.flush() : { ...NO_COUNTS };
  }

  async flush(): Promise<FlushCounts> {
    if (this.records.length === 0) return { ...NO_COUNTS };
    const batch = this.records;
    this.records = [];
    return this.sink.write(batch);
  }
}
