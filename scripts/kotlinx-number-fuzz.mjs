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

function renderResult(value) {
  return value === null ? 'null' : String(value);
}

function compareIntegral(rows, numberModule) {
  const problems = [];
  for (const line of rows) {
    const [literal, intValue, longValue, strict] = line.split(FIELD_SEPARATOR);
    const int = renderResult(numberModule.kotlinIntFromText(literal));
    const long = renderResult(numberModule.kotlinLongFromText(literal));
    const mismatch = int !== intValue || long !== longValue;
    const strictMismatch =
      STRICT_JSON_NUMBER.test(literal) && (strict === 'THROWS' ? int !== 'null' : strict !== int);
    if (mismatch || strictMismatch) {
      problems.push(`${literal}: java int=${intValue} long=${longValue} strict=${strict}; port int=${int} long=${long}`);
    }
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
    const problems = [...overrides.problems, ...compareIntegral(java(jars, scratch, 'parse', casesFile).trim().split('\n'), numberModule)];
    console.log(
      `kotlinx-serialization-json ${version}: ${overrides.checked} exponents checked against ${overrides.overrides} overrides ` +
        `(${overrides.expected} differ from correctly rounded), ${cases.length} literals (seed ${seed}), ${problems.length} mismatch(es)`,
    );
    for (const problem of problems.slice(0, MAX_REPORTED)) console.error(`  ${problem}`);
    process.exitCode = problems.length === 0 ? 0 : 1;
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

await main();
