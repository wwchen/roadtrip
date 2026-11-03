import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const PROBE_DIR = path.join(REPO_ROOT, 'scripts/kotlinx-number-probe');
const BUILD_FILE = path.join(REPO_ROOT, 'backend/build.gradle.kts');
const GRADLE_CACHE = path.join(os.homedir(), '.gradle/caches/modules-2/files-2.1');
const WORKER_DIST = path.join(REPO_ROOT, 'packages/worker/dist/etl');
const TEXT_MODULE = 'kotlin-text.js';
const JSON_MODULE = 'kotlin-json.js';
const ENVELOPE_MODULE = 'envelope.js';
const THROWS = 'THROWS';
const NOT_A_NUMBER_TOKEN = 'SKIP';
const OVERRIDES_SOURCE = path.join(REPO_ROOT, 'packages/worker/src/etl/java-pow10.ts');
const SERIALIZATION_VERSION = /kotlinx-serialization-json:([0-9.]+)/;
const DEFAULT_CASES = 20000;
const DEFAULT_SEED = 1;
const MAX_REPORTED = 25;
const STRICT_JSON_NUMBER = /^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/;
const FIELD_SEPARATOR = '\t';
const SEED_MIX = 0x6d2b79f5;
const EDGE_LITERALS = [
  '0e100', '0e-65', '-0e100', '00e100', '0e-400', '0e400', '1e', '1E', '1e+', '1e-', '-1e', '12e',
  '10000000000000000000e-18', '102950316600000e-5', '100000e-5', '9000000000000e-11', '200000000000e-11',
  '45300001340703728227e0', '9223372036854775807', '9223372036854775808', '-9223372036854775808',
  '-9223372036854775809', '9223372036854775807e0', '-9223372036854775808e0', '922337203685477580e1',
  '-922337203685477580e1', '1e18446744073709551616', '1e9223372036854775808', '1e-9223372036854775808',
  '1e-9223372036854775809', '1e9223372036854775807', '1e-9223372036854775807', '1e-2000', '1e2000', '1e99999999999',
  '1e-99999999999', '2147483647', '2147483648', '-2147483648', '-2147483649', '21474836470e-1', '21474836480e-1',
  '-21474836480e-1', '25', '25.0', '1e2', '1.0e2', '10e-1', '12e-1', '250e-1',
];

function mulberry32(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state + SEED_MIX) >>> 0;
    let mixed = Math.imul(state ^ (state >>> 15), 1 | state);
    mixed = (mixed + Math.imul(mixed ^ (mixed >>> 7), 61 | mixed)) ^ mixed;
    return ((mixed ^ (mixed >>> 14)) >>> 0) / 4294967296;
  };
}

function findJar(prefix, version) {
  const wanted = `${prefix}-${version}.jar`;
  const stack = [GRADLE_CACHE];
  while (stack.length > 0) {
    const dir = stack.pop();
    for (const name of readdirSync(dir)) {
      const full = path.join(dir, name);
      if (statSync(full).isDirectory()) stack.push(full);
      else if (name === wanted) return full;
    }
  }
  throw new Error(`${wanted} is not in ${GRADLE_CACHE}; build the backend once so gradle caches it`);
}

function classpath() {
  const version = SERIALIZATION_VERSION.exec(readFileSync(BUILD_FILE, 'utf8'))?.[1];
  if (version === undefined) throw new Error(`no kotlinx-serialization-json version in ${BUILD_FILE}`);
  const stdlib = readdirSync(path.join(GRADLE_CACHE, 'org.jetbrains.kotlin/kotlin-stdlib')).sort().at(-1);
  return {
    version,
    jars: [
      findJar('kotlinx-serialization-json-jvm', version),
      findJar('kotlinx-serialization-core-jvm', version),
      findJar('kotlin-stdlib', stdlib),
    ],
  };
}

function digits(random, length) {
  let text = String(1 + Math.floor(random() * 9));
  for (let index = 1; index < length; index += 1) text += Math.floor(random() * 10);
  return text;
}

function generate(count, seed) {
  const random = mulberry32(seed);
  const cases = new Set(EDGE_LITERALS);
  const pick = (limit) => Math.floor(random() * limit);
  while (cases.size < count) {
    const mantissa = digits(random, 1 + pick(26));
    const sign = pick(3) === 0 ? '-' : '';
    const exponentSign = ['', '+', '-'][pick(3)];
    const exponent = pick(4) === 0 ? pick(700) - 350 : pick(60) - 30;
    const wide = pick(40) === 0 ? digits(random, 18 + pick(4)) : String(Math.abs(exponent));
    cases.add(`${sign}${mantissa}`);
    cases.add(`${sign}${mantissa}e${exponentSign}${pick(10) === 0 ? '' : Math.abs(exponent)}`);
    cases.add(`${sign}${mantissa}e${exponentSign}${wide}`);
    const zeros = '0'.repeat(pick(18));
    cases.add(`${sign}${mantissa}${zeros}e-${Math.max(0, zeros.length - pick(3))}`);
  }
  return [...cases];
}

function java(jars, scratch, mode, input) {
  const classes = path.join(scratch, 'classes');
  const classpathText = jars.join(path.delimiter);
  execFileSync('javac', ['-cp', classpathText, '-d', classes, path.join(PROBE_DIR, 'NumberProbe.java')], {
    stdio: ['ignore', 'ignore', 'inherit'],
  });
  return execFileSync('java', ['-cp', `${classpathText}${path.delimiter}${classes}`, 'NumberProbe', mode, ...(input ? [input] : [])], {
    encoding: 'utf8',
    maxBuffer: 1 << 28,
  });
}

function lines(output) {
  return output.replace(/\n$/, '').split('\n');
}

function bitsOf(value) {
  return new BigInt64Array(new Float64Array([value]).buffer)[0];
}

function readOverrides() {
  const entries = new Map();
  for (const match of readFileSync(OVERRIDES_SOURCE, 'utf8').matchAll(/\[(-?\d+), ([^\]]+)\]/g)) {
    entries.set(Number(match[1]), Number(match[2]));
  }
  return entries;
}

function powTable(output) {
  return output
    .trim()
    .split('\n')
    .map((line) => line.split(FIELD_SEPARATOR))
    .map(([exponent, bits, text]) => ({ exponent: Number(exponent), bits: BigInt(bits), text }));
}

function checkOverrides(rows) {
  const table = readOverrides();
  const problems = [];
  const expectedKeys = new Set();
  for (const { exponent, bits } of rows) {
    const correctlyRounded = Number(`1e${exponent}`);
    if (bitsOf(correctlyRounded) !== bits) expectedKeys.add(exponent);
    const used = table.has(exponent) ? table.get(exponent) : correctlyRounded;
    if (bitsOf(used) !== bits) problems.push(`pow(10, ${exponent}): java ${bits}, table ${bitsOf(used)}`);
  }
  for (const key of table.keys()) {
    if (!expectedKeys.has(key) && rows.some((row) => row.exponent === key)) problems.push(`spurious override ${key}`);
  }
  return { problems, checked: rows.length, overrides: table.size, expected: expectedKeys.size };
}

function printOverrides(rows) {
  const lines = rows
    .filter(({ exponent, bits }) => Math.abs(exponent) <= 400 && bitsOf(Number(`1e${exponent}`)) !== bits)
    .map(({ exponent, text }) => `  [${exponent}, ${text.replace('E', 'e')}],`);
  return `export const JAVA_POW10_OVERRIDES: ReadonlyMap<number, number> = new Map<number, number>([\n${lines.join('\n')}\n]);\n`;
}

const STDLIB_EDGE_LITERALS = [
  '', '+', '-', ' 25', '25 ', '+25', '-25', '+-1', '-+1', '--1', '0x10', '1_0', '-0', '+0', '00000000000000000000025',
  '2147483647', '+2147483647', '2147483648', '-2147483648', '-2147483649', '+9223372036854775807',
  '\u0663', '\u0661\u0662\u0663', '25\u0663', '\uff12\uff15', '\u{1d7d3}', '\u00b2', '\u066b3', '\u0660', '\u06f5',
  '\u0966\u0967', '\u1810', '\ua9d5', '\u17e5', '\u1bb5', '\u1c43', '\u19d9', '\u0e55', '\u0ed9', '\u0f23',
];

const RINT_EDGE_LITERALS = [
  '0.5', '1.5', '2.5', '3.5', '-0.5', '-1.5', '-2.5', '-0.0', '0.0', '0.3', '-0.3', '0.49999999999999994',
  '0.5000000000000001', '4503599627370495.5', '4503599627370496', '4503599627370497', '-4503599627370495.5',
  '1e300', '-1e300', '1e-300', '433.5', '434.5', '12.5', '37.5', '62.5', '87.5',
];

function stdlibLiterals(cases) {
  return [...new Set([...STDLIB_EDGE_LITERALS, ...cases])];
}

function rintLiterals(random, count) {
  const literals = new Set(RINT_EDGE_LITERALS);
  while (literals.size < count) {
    const whole = Math.floor(random() * 2000) - 1000;
    literals.add(String(whole + 0.5));
    literals.add(String(Number((whole + random()).toFixed(1 + Math.floor(random() * 6)))));
    literals.add(String((random() - 0.5) * 10 ** Math.floor(random() * 18)));
    literals.add(String(whole / 8));
  }
  return [...literals];
}

function compareStdlib(rows, numberModule, textModule) {
  const problems = [];
  for (const line of rows) {
    const [literal, intValue, longValue, booleanValue] = line.split(FIELD_SEPARATOR);
    const int = renderResult(numberModule.kotlinStringToIntOrNull(literal));
    const long = renderResult(numberModule.kotlinStringToLongOrNull(literal));
    const strict = renderResult(textModule.kotlinStringToBooleanStrictOrNull(literal));
    if (int !== intValue || long !== longValue || strict !== booleanValue) {
      problems.push(
        `String.toIntOrNull/toLongOrNull/toBooleanStrictOrNull ${JSON.stringify(literal)}: ` +
          `java int=${intValue} long=${longValue} boolean=${booleanValue}; port int=${int} long=${long} boolean=${strict}`,
      );
    }
  }
  return problems;
}

const CASE_STRING_LITERALS = [
  '\u03a3', '\u03a3\u03a3', '\u03a3\u039f\u03a3', '\u03a3\u039f\u03a3 \u03a3\u039f\u03a3', '\u03a3\u039f\u03a3.',
  '\u03c3\u03bf\u03c2', '\u0391\u03a3', '\u0391\u03a3\u0391', "O\u03a3'", '\u03a3-\u03a3', '\u03a31',
  '\u00df', '\u00dfx', '\u0130', '\u0131', '\u017f', '\ufb00', '\u01f1', '\u01f2', '\u01f3',
  'State Park', 'STATE PARK', 'wa', 'WA', ' wa ', '\u0130STANBUL', 'i\u0307', '\u{1df95}',
];

const ASCII_ALPHANUMERIC = /[a-zA-Z0-9]/;
const SIGMA = '\u03a3';
const NON_NORMALIZED = /[^a-z0-9 ]/g;
const SPACE = ' ';
const ASCII_REACHING = 'ascii';
const SIGMA_CONTEXT_FLOOR = 0x10000;
const MINIMUM_SIGMA_DIVERGENCES = 1000;
const NON_ASCII = 'non-ascii';

const JAVA_CASE_DIVERGENCES = new Map([
  ['uppercase codePoint U+0277', 'non-ascii'],
  ['uppercase codePoint U+027c', 'non-ascii'],
  ['lowercase codePoint U+a7dd', 'non-ascii'],
  ['lowercase codePoint U+a7e2', 'non-ascii'],
  ['uppercase codePoint U+ab4b', 'non-ascii'],
  ['uppercase codePoint U+ab4c', 'non-ascii'],
  ['lowercase codePoint U+ab6c', 'non-ascii'],
  ['lowercase codePoint U+ab6d', 'non-ascii'],
  ['lowercase codePoint U+1df40', 'non-ascii'],
  ['uppercase codePoint U+1df41', 'non-ascii'],
  ['lowercase codePoint U+1df48', 'non-ascii'],
  ['uppercase codePoint U+1df49', 'non-ascii'],
  ['lowercase codePoint U+1df4a', 'non-ascii'],
  ['uppercase codePoint U+1df4b', 'non-ascii'],
  ['lowercase codePoint U+1df4d', 'non-ascii'],
  ['uppercase codePoint U+1df4e', 'non-ascii'],
  ['lowercase codePoint U+1df51', 'non-ascii'],
  ['uppercase codePoint U+1df52', 'non-ascii'],
  ['lowercase codePoint U+1df68', 'non-ascii'],
  ['uppercase codePoint U+1df69', 'non-ascii'],
  ['lowercase codePoint U+1df6a', 'non-ascii'],
  ['uppercase codePoint U+1df6b', 'non-ascii'],
  ['lowercase codePoint U+1df6c', 'non-ascii'],
  ['uppercase codePoint U+1df6d', 'non-ascii'],
  ['lowercase codePoint U+1df6e', 'non-ascii'],
  ['uppercase codePoint U+1df6f', 'non-ascii'],
  ['lowercase codePoint U+1df72', 'non-ascii'],
  ['uppercase codePoint U+1df73', 'non-ascii'],
  ['lowercase codePoint U+1df74', 'non-ascii'],
  ['uppercase codePoint U+1df75', 'non-ascii'],
  ['lowercase codePoint U+1df76', 'non-ascii'],
  ['uppercase codePoint U+1df77', 'non-ascii'],
  ['lowercase codePoint U+1df78', 'non-ascii'],
  ['uppercase codePoint U+1df79', 'non-ascii'],
  ['lowercase codePoint U+1df7a', 'non-ascii'],
  ['uppercase codePoint U+1df7b', 'non-ascii'],
  ['lowercase codePoint U+1df7c', 'non-ascii'],
  ['uppercase codePoint U+1df7d', 'non-ascii'],
  ['lowercase codePoint U+1df7e', 'non-ascii'],
  ['uppercase codePoint U+1df7f', 'non-ascii'],
  ['uppercase codePoint U+1df95', 'ascii'],
  ['uppercase string "\u{1df95}"', ASCII_REACHING],
  ['lowercase string "\u03a3-\u03a3"', NON_ASCII],
]);

function unitCodesOf(text) {
  return Array.from({ length: text.length }, (_, index) => text.charCodeAt(index)).join(',');
}

function textOfCodes(codes) {
  return codes
    .split(',')
    .map((code) => String.fromCharCode(Number(code)))
    .join('');
}

function asciiAlnumOf(text) {
  return [...text]
    .filter((character) => ASCII_ALPHANUMERIC.test(character))
    .sort()
    .join('');
}

function normalizeProjection(text) {
  return text.replace(NON_NORMALIZED, SPACE);
}

function caseProblem(key, javaCodes, portCodes, seen) {
  if (portCodes === javaCodes) return [];
  const javaText = textOfCodes(javaCodes);
  const portText = textOfCodes(portCodes);
  const reach = asciiAlnumOf(javaText) === asciiAlnumOf(portText) ? NON_ASCII : ASCII_REACHING;
  const projected = normalizeProjection(javaText) !== normalizeProjection(portText);
  seen.push({ key, reach, projected });
  const allowed = JAVA_CASE_DIVERGENCES.get(key);
  if (allowed === undefined) {
    return [`unlisted case divergence ${key}: java [${javaCodes}], port [${portCodes}] (${reach})`];
  }
  if (allowed !== reach) {
    return [`case divergence ${key} is listed as ${allowed} but now measures ${reach}: java [${javaCodes}], port [${portCodes}]`];
  }
  return [];
}

function caseProblems(label, shown, javaLower, javaUpper, source, seen) {
  return [
    ...caseProblem(`lowercase ${label} ${shown}`, javaLower, unitCodesOf(source.toLowerCase()), seen),
    ...caseProblem(`uppercase ${label} ${shown}`, javaUpper, unitCodesOf(source.toUpperCase()), seen),
  ];
}

function compareCase(codePointRows, stringRows) {
  const problems = [];
  const seen = [];
  let codePoints = 0;
  for (const line of codePointRows) {
    if (line === '') continue;
    codePoints += 1;
    const [codePoint, javaLower, javaUpper] = line.split(FIELD_SEPARATOR);
    const shown = `U+${Number(codePoint).toString(16).padStart(4, '0')}`;
    problems.push(...caseProblems('codePoint', shown, javaLower, javaUpper, String.fromCodePoint(Number(codePoint)), seen));
  }
  let strings = 0;
  for (const line of stringRows) {
    if (line === '') continue;
    strings += 1;
    const [literal, javaLower, javaUpper] = line.split(FIELD_SEPARATOR);
    problems.push(...caseProblems('string', JSON.stringify(literal), javaLower, javaUpper, literal, seen));
  }
  const keys = new Set(seen.map((divergence) => divergence.key));
  for (const key of JAVA_CASE_DIVERGENCES.keys()) {
    if (!keys.has(key)) problems.push(`stale case divergence allowance: java and the port now agree on ${key}`);
  }
  const reachingAscii = seen.filter((divergence) => divergence.reach === ASCII_REACHING);
  const changingNormalize = seen.filter((divergence) => divergence.projected);
  for (const divergence of changingNormalize) {
    problems.push(`case divergence ${divergence.key} survives normalize's [^a-z0-9 ] projection`);
  }
  return {
    problems,
    codePoints,
    strings,
    divergences: keys.size,
    reachingAscii: reachingAscii.length,
    survivingNormalize: changingNormalize.length,
  };
}

function compareSigmaContexts(rows) {
  const problems = [];
  const surviving = [];
  let compared = 0;
  let raw = 0;
  for (const line of rows) {
    if (line === '') continue;
    compared += 1;
    const [unit, javaLower] = line.split(FIELD_SEPARATOR);
    const context = `${SIGMA}${String.fromCharCode(Number(unit))}${SIGMA}`;
    const portLower = unitCodesOf(context.toLowerCase());
    if (portLower === javaLower) continue;
    raw += 1;
    if (normalizeProjection(textOfCodes(javaLower)) !== normalizeProjection(textOfCodes(portLower))) {
      surviving.push(
        `final-sigma context U+${Number(unit).toString(16).padStart(4, '0')} survives normalize's projection: ` +
          `java [${javaLower}], port [${portLower}]`,
      );
    }
  }
  problems.push(...surviving);
  if (compared < SIGMA_CONTEXT_FLOOR) {
    problems.push(
      `the final-sigma sweep compared ${compared} contexts, below the floor of ${SIGMA_CONTEXT_FLOOR}; ` +
        'a truncated sweep would report no surviving divergence whatever the port did',
    );
  }
  if (raw < MINIMUM_SIGMA_DIVERGENCES) {
    problems.push(
      `the final-sigma sweep found ${raw} raw divergence(s), below the floor of ${MINIMUM_SIGMA_DIVERGENCES}; ` +
        'a port side that trivially agrees would otherwise pass the projection check vacuously',
    );
  }
  return { problems, compared, raw, survivingNormalize: surviving.length };
}

function compareRint(rows, numberModule) {
  const problems = [];
  for (const line of rows) {
    const [literal, javaBits] = line.split(FIELD_SEPARATOR);
    const bits = bitsOf(numberModule.kotlinRint(Number(literal)));
    if (String(bits) !== javaBits) problems.push(`rint(${literal}): java bits ${javaBits}, port bits ${bits}`);
  }
  return problems;
}

function compareIgnoreCase(rows, textModule) {
  const javaPairs = new Set(rows.filter((row) => row !== '').map((row) => row.replace(FIELD_SEPARATOR, ':')));
  const problems = [];
  const portPairs = new Set();
  for (let unit = 0; unit < 0x10000; unit += 1) {
    const candidate = String.fromCharCode(unit);
    for (let letter = 97; letter <= 122; letter += 1) {
      if (textModule.kotlinEqualsIgnoreCase(candidate, String.fromCharCode(letter))) portPairs.add(`${unit}:${String.fromCharCode(letter)}`);
    }
  }
  for (const pair of javaPairs) if (!portPairs.has(pair)) problems.push(`equalsIgnoreCase: java equates ${pair}, port does not`);
  for (const pair of portPairs) if (!javaPairs.has(pair)) problems.push(`equalsIgnoreCase: port equates ${pair}, java does not`);
  return { problems, pairs: javaPairs.size };
}

function renderResult(value) {
  return value === null ? 'null' : String(value);
}

function renderThrowing(read, literal) {
  try {
    return String(read(literal));
  } catch {
    return THROWS;
  }
}

function compareIntegral(rows, numberModule, jsonModule, envelopeModule) {
  const problems = [];
  let getterThrows = 0;
  let getterReads = 0;
  let unquotedThrows = 0;
  let unquotedReads = 0;
  for (const line of rows) {
    const [literal, intValue, longValue, strict, getter, unquotedGetter] = line.split(FIELD_SEPARATOR);
    const int = renderResult(numberModule.kotlinIntFromText(literal));
    const long = renderResult(numberModule.kotlinLongFromText(literal));
    const portGetter = renderThrowing(jsonModule.longOf, literal);
    if (getter === THROWS) getterThrows += 1;
    else getterReads += 1;
    if (portGetter !== getter) {
      problems.push(`${literal}: java JsonPrimitive.long=${getter}; port longOf=${portGetter}`);
    }
    if (STRICT_JSON_NUMBER.test(literal)) {
      if (unquotedGetter === NOT_A_NUMBER_TOKEN) {
        problems.push(`${literal}: java did not read it as an unquoted number token`);
      } else {
        const portUnquoted = renderThrowing(jsonModule.longOf, envelopeModule.parseKeepingDigits(literal));
        if (unquotedGetter === THROWS) unquotedThrows += 1;
        else unquotedReads += 1;
        if (portUnquoted !== unquotedGetter) {
          problems.push(`${literal}: java unquoted JsonPrimitive.long=${unquotedGetter}; port longOf=${portUnquoted}`);
        }
      }
    }
    const mismatch = int !== intValue || long !== longValue;
    const strictMismatch =
      STRICT_JSON_NUMBER.test(literal) && (strict === 'THROWS' ? int !== 'null' : strict !== int);
    if (mismatch || strictMismatch) {
      problems.push(`${literal}: java int=${intValue} long=${longValue} strict=${strict}; port int=${int} long=${long}`);
    }
  }
  if (unquotedThrows === 0 || unquotedReads === 0) {
    problems.push(`unquoted JsonPrimitive.long probe saw ${unquotedThrows} throwing and ${unquotedReads} reading literals; it proves nothing unless both occur`);
  }
  if (getterThrows === 0 || getterReads === 0) {
    problems.push(`JsonPrimitive.long probe saw ${getterThrows} throwing and ${getterReads} reading literals; it proves nothing unless both occur`);
  }
  return problems;
}

async function main() {
  const { values } = parseArgs({
    options: {
      cases: { type: 'string' },
      seed: { type: 'string' },
      'print-overrides': { type: 'boolean' },
    },
  });
  const count = Number(values.cases ?? DEFAULT_CASES);
  const seed = Number(values.seed ?? DEFAULT_SEED);
  const { version, jars } = classpath();
  const scratch = mkdtempSync(path.join(os.tmpdir(), 'kotlinx-number-fuzz-'));
  try {
    const powRows = powTable(java(jars, scratch, 'pow'));
    if (values['print-overrides']) {
      process.stdout.write(printOverrides(powRows));
      return;
    }
    const overrides = checkOverrides(powRows);
    const numberModule = await import(pathToFileURL(path.join(WORKER_DIST, 'kotlin-number.js')).href);
    const cases = generate(count, seed);
    const casesFile = path.join(scratch, 'cases.txt');
    writeFileSync(casesFile, `${cases.join('\n')}\n`);
    const textModule = await import(pathToFileURL(path.join(WORKER_DIST, TEXT_MODULE)).href);
    const jsonModule = await import(pathToFileURL(path.join(WORKER_DIST, JSON_MODULE)).href);
    const envelopeModule = await import(pathToFileURL(path.join(WORKER_DIST, ENVELOPE_MODULE)).href);
    const stdlibFile = path.join(scratch, 'stdlib.txt');
    const stdlibCases = stdlibLiterals(cases);
    writeFileSync(stdlibFile, `${stdlibCases.join('\n')}\n`);
    const rintFile = path.join(scratch, 'rint.txt');
    const rintCases = rintLiterals(mulberry32(seed + 1), Math.max(1000, Math.floor(count / 4)));
    writeFileSync(rintFile, `${rintCases.join('\n')}\n`);
    const ignoreCase = compareIgnoreCase(lines(java(jars, scratch, 'ignorecase')), textModule);
    const caseFile = path.join(scratch, 'case-strings.txt');
    writeFileSync(caseFile, `${CASE_STRING_LITERALS.join('\n')}\n`);
    const caseMapping = compareCase(
      lines(java(jars, scratch, 'case')),
      lines(java(jars, scratch, 'casestrings', caseFile)),
    );
    const sigma = compareSigmaContexts(lines(java(jars, scratch, 'sigma')));
    const problems = [
      ...overrides.problems,
      ...compareIntegral(lines(java(jars, scratch, 'parse', casesFile)), numberModule, jsonModule, envelopeModule),
      ...compareStdlib(lines(java(jars, scratch, 'stdlib', stdlibFile)), numberModule, textModule),
      ...compareRint(lines(java(jars, scratch, 'rint', rintFile)), numberModule),
      ...ignoreCase.problems,
      ...caseMapping.problems,
      ...sigma.problems,
    ];
    console.log(
      `kotlinx-serialization-json ${version}: ${overrides.checked} exponents checked against ${overrides.overrides} overrides ` +
        `(${overrides.expected} differ from correctly rounded), ${cases.length} literals (seed ${seed}), ` +
        `${stdlibCases.length} String.toIntOrNull/toLongOrNull literals, ${rintCases.length} rint operands, ` +
        `${ignoreCase.pairs} equalsIgnoreCase pairs over every UTF-16 unit, ` +
        `${caseMapping.codePoints} case-mapped code points and ${caseMapping.strings} case-mapped strings ` +
        `(${caseMapping.divergences} enumerated Java divergences, ${caseMapping.reachingAscii} reaching ASCII, ` +
        `${caseMapping.survivingNormalize} surviving normalize's projection), ${sigma.compared} final-sigma contexts ` +
        `(${sigma.raw} raw divergences, ${sigma.survivingNormalize} surviving normalize's projection), ` +
        `${problems.length} mismatch(es)`,
    );
    for (const problem of problems.slice(0, MAX_REPORTED)) console.error(`  ${problem}`);
    process.exitCode = problems.length === 0 ? 0 : 1;
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

await main();
