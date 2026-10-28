import type { InputBundle } from './input-bundle.js';
import type { TransformCtx } from './transform-ctx.js';

export type ParseResult<DTO> =
  | { ok: true; dto: DTO }
  | { ok: false; sourceId?: string; errors: string[] };

export type TransformResult<OUT> =
  | { ok: true; record: OUT }
  | { ok: false; sourceId?: string; errors: string[] };

export interface SourceEtl<DTO, OUT> {
  readonly etlSlug: string;
  parse(inputs: InputBundle): Iterable<ParseResult<DTO>>;
  transform(dto: DTO, ctx: TransformCtx): Iterable<TransformResult<OUT>>;
}
