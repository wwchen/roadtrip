import { isDeepStrictEqual } from 'node:util';

const MAX_SHOWN = 1500;

export function strip(value) {
  if (value === null || value === undefined) return undefined;
  if (Array.isArray(value)) {
    const items = value.map(strip).filter((item) => item !== undefined);
    return items.length === 0 ? undefined : items;
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value)
      .map(([key, entry]) => [key, strip(entry)])
      .filter(([, entry]) => entry !== undefined);
    return entries.length === 0 ? undefined : Object.fromEntries(entries);
  }
  return value;
}

export function canonical(value) {
  return JSON.parse(JSON.stringify(strip(value) ?? null));
}

function shown(value) {
  return (JSON.stringify(value) ?? 'undefined').slice(0, MAX_SHOWN);
}

export function compareEtlCase(ts, kotlin) {
  const problems = [];
  if (!isDeepStrictEqual(ts.fetchedAt, kotlin.fetchedAt)) {
    problems.push(`the envelopes' fetched_at differ between the two sides: ts ${shown(ts.fetchedAt)}, kotlin ${shown(kotlin.fetchedAt)}`);
  }
  if (ts.tsParse !== kotlin.parse) problems.push(`parse: ts ${ts.tsParse}, kotlin ${kotlin.parse}`);
  if (ts.tsParse !== 'ok') return problems;
  if (!isDeepStrictEqual(canonical(ts.tsParseBad), canonical(kotlin.parseBad))) {
    problems.push(`parse-level errors differ:\n  ts     ${shown(ts.tsParseBad)}\n  kotlin ${shown(kotlin.parseBad)}`);
  }
  if (!ts.tsTransformRan) return problems;
  if (ts.tsTransform !== kotlin.transform) {
    problems.push(`transform: ts ${ts.tsTransform}, kotlin ${kotlin.transform}`);
  } else if (ts.tsTransform === 'ok' && !isDeepStrictEqual(canonical(ts.tsResults), canonical(kotlin.results))) {
    problems.push(`results differ:\n  ts     ${shown(canonical(ts.tsResults))}\n  kotlin ${shown(canonical(kotlin.results))}`);
  }
  return problems;
}

export function compareCallCase(ts, kotlin) {
  const same = ts.threw === kotlin.threw && isDeepStrictEqual(canonical(ts.value), canonical(kotlin.value));
  return same ? [] : [`call: ts ${shown(ts)}, kotlin ${shown(kotlin)}`];
}

export function summarise(cases, kotlinOutputs, compare) {
  if (cases.length !== kotlinOutputs.length) {
    throw new Error(`${cases.length} cases were sent to the JVM but ${kotlinOutputs.length} answers came back`);
  }
  const failures = [];
  cases.forEach((current, index) => {
    const problems = compare(current, kotlinOutputs[index]);
    if (problems.length > 0) failures.push({ index, case: current, problems });
  });
  return { compared: cases.length, failures };
}

export function floorProblems(recorded, adapters, calls, callCasesByName) {
  const problems = [];
  const counts = {};
  for (const name of Object.keys(adapters)) counts[name] = { cases: 0, transformed: 0 };
  for (const current of recorded) {
    const count = counts[current.adapter];
    if (count === undefined) continue;
    count.cases += 1;
    if (current.tsParse === 'ok' && current.tsTransformRan) count.transformed += 1;
  }
  for (const [name, entry] of Object.entries(adapters)) {
    const { cases, transformed } = counts[name];
    if (!Number.isInteger(entry.minimumCases) || !Number.isInteger(entry.minimumTransformed) || entry.minimumCases < 1 || entry.minimumTransformed < 1) {
      problems.push(`${name}: adapters.json must set minimumCases and minimumTransformed to integers of at least 1`);
      continue;
    }
    if (cases < entry.minimumCases) problems.push(`${name}: ${cases} recorded case(s), below the floor of ${entry.minimumCases}; no unit test under src/vendors exercises this adapter enough for a green run to mean anything`);
    if (transformed < entry.minimumTransformed) problems.push(`${name}: ${transformed} case(s) compared through transform, below the floor of ${entry.minimumTransformed}`);
  }
  for (const [name, entry] of Object.entries(calls)) {
    const count = callCasesByName[name] ?? 0;
    if (!Number.isInteger(entry.minimumCases) || entry.minimumCases < 1) problems.push(`call ${name}: adapters.json must set minimumCases to an integer of at least 1`);
    else if (count < entry.minimumCases) problems.push(`call ${name}: ${count} committed case(s), below the floor of ${entry.minimumCases}`);
  }
  return { problems, counts };
}
