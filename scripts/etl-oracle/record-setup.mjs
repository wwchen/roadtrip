import { appendFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, expect } from 'vitest';
import { agencyDescriptor, caseOf, resultOf } from './case-shape.mjs';
import manifest from './adapters.json' with { type: 'json' };

const WORKER_SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../packages/worker/src');
const OUT = process.env.ETL_ORACLE_OUT;
if (OUT === undefined) throw new Error('ETL_ORACLE_OUT names the file the recorded cases are written to');

const recorded = [];
const byDto = new WeakMap();

function inputsOf(bundle) {
  const inputs = {};
  for (const slug of bundle.dataSourceSlugs()) {
    inputs[slug] = bundle.envelopes(slug).map((envelope) => ({
      url: envelope.request.url,
      fetchedAt: envelope.fetchedAt,
      part: envelope.part ?? null,
      payload: JSON.stringify(envelope.payload),
    }));
  }
  return inputs;
}

function recordingCtx(ctx, slug, registry) {
  return new Proxy(ctx, {
    get(target, property) {
      if (property === 'argFor') {
        return (etlSlug, key) => {
          const value = target.argFor(etlSlug, key);
          if (etlSlug === slug && value !== undefined) registry.args[key] = value;
          return value;
        };
      }
      const member = target[property];
      return typeof member === 'function' ? member.bind(target) : member;
    },
  });
}

async function patch(name, entry) {
  const exported = (await import(path.join(WORKER_SRC, `${entry.module}.ts`)))[entry.export];
  const originalParse = exported.prototype.parse;
  const originalTransform = exported.prototype.transform;
  exported.prototype.parse = function* (inputs) {
    const current = {
      ...caseOf(name, this, inputsOf(inputs)),
      test: expect.getState().currentTestName ?? '',
      tsParse: 'ok',
      tsParseBad: [],
      tsTransformRan: false,
      tsTransform: 'ok',
      tsResults: [],
    };
    recorded.push(current);
    try {
      for (const result of originalParse.call(this, inputs)) {
        if (result.ok) byDto.set(result.dto, current);
        else current.tsParseBad.push(result.errors);
        yield result;
      }
    } catch (error) {
      current.tsParse = 'throws';
      throw error;
    }
  };
  exported.prototype.transform = function* (dto, ctx) {
    const current = byDto.get(dto);
    let seen = ctx;
    if (current !== undefined) current.tsTransformRan = true;
    if (current !== undefined && ctx !== undefined) {
      current.registry.subcategory = ctx.subcategoryFor(this.etlSlug) ?? null;
      current.registry.agency = agencyDescriptor(ctx.agencyFor(this.etlSlug));
      seen = recordingCtx(ctx, this.etlSlug, current.registry);
    }
    try {
      for (const result of originalTransform.call(this, dto, seen)) {
        current?.tsResults.push(resultOf(result, entry.opaqueFields));
        yield result;
      }
    } catch (error) {
      if (current !== undefined) current.tsTransform = 'throws';
      throw error;
    }
  };
}

for (const [name, entry] of Object.entries(manifest.adapters)) await patch(name, entry);

afterAll(() => {
  for (const current of recorded) appendFileSync(OUT, `${JSON.stringify(current)}\n`);
});
