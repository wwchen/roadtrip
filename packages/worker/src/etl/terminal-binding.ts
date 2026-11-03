import type { Db } from '@roadtrip/db';
import type { TerminalSink } from './batch.js';
import type { SourceEtl } from './source-etl.js';

export interface TerminalEtlBinding<DTO, OUT> {
  etl: SourceEtl<DTO, OUT>;
  sink(db: Db): TerminalSink<OUT>;
  naturalKeyOf(record: OUT): string;
}

export type AnyTerminalEtlBinding = TerminalEtlBinding<unknown, unknown>;
