const documentOrders = new WeakMap<object, readonly string[]>();

const OPEN_BRACE = 0x7b;
const CLOSE_BRACE = 0x7d;
const OPEN_BRACKET = 0x5b;
const CLOSE_BRACKET = 0x5d;
const QUOTE = 0x22;
const BACKSLASH = 0x5c;
const COMMA = 0x2c;
const SPACE = 0x20;
const TAB = 0x09;
const LINE_FEED = 0x0a;
const CARRIAGE_RETURN = 0x0d;
const DIGIT_ZERO = 0x30;
const DIGIT_NINE = 0x39;

const INDEX_KEY_CANDIDATE = /"(?:0|[1-9][0-9]*)"[ \t\n\r]*:|\\u003[0-9]/;

interface Frame {
  readonly target: Readonly<Record<string, unknown>> | readonly unknown[] | null;
  readonly isObject: boolean;
  readonly keys: string[];
  currentKey: string;
  index: number;
  mayReorder: boolean;
}

function isPlainObject(value: unknown): value is Readonly<Record<string, unknown>> {
  return typeof value === 'object' && value !== null && Object.getPrototypeOf(value) === Object.prototype;
}

function childOf(frame: Frame): unknown {
  const { target } = frame;
  if (target === null) return null;
  if (frame.isObject) {
    const record = target as Readonly<Record<string, unknown>>;
    return Object.hasOwn(record, frame.currentKey) ? record[frame.currentKey] : null;
  }
  return (target as readonly unknown[])[frame.index] ?? null;
}

function openFrame(counterpart: unknown, isObject: boolean): Frame {
  const matching = isObject ? isPlainObject(counterpart) : Array.isArray(counterpart);
  return {
    target: matching ? (counterpart as Frame['target']) : null,
    isObject,
    keys: [],
    currentKey: '',
    index: 0,
    mayReorder: false,
  };
}

function settle(frame: Frame): void {
  if (!frame.isObject || frame.target === null) return;
  const target = frame.target as Readonly<Record<string, unknown>>;
  documentOrders.delete(target);
  if (!frame.mayReorder) return;
  const unique = [...new Set(frame.keys)];
  const natural = Object.keys(target);
  if (unique.length !== natural.length || !unique.every((key) => Object.hasOwn(target, key))) return;
  if (unique.some((key, position) => key !== natural[position])) documentOrders.set(target, unique);
}

export function recordDocumentOrder(text: string, root: unknown): void {
  if (!INDEX_KEY_CANDIDATE.test(text)) return;
  const end = text.length;
  const stack: Frame[] = [];
  let position = 0;
  let counterpart = root;

  const skipSpace = (): void => {
    while (position < end) {
      const code = text.charCodeAt(position);
      if (code !== SPACE && code !== TAB && code !== LINE_FEED && code !== CARRIAGE_RETURN) return;
      position += 1;
    }
  };

  const skipString = (): number => {
    const start = position;
    position += 1;
    while (text.charCodeAt(position) !== QUOTE) position += text.charCodeAt(position) === BACKSLASH ? 2 : 1;
    position += 1;
    return start;
  };

  const readKey = (frame: Frame): void => {
    skipSpace();
    const start = skipString();
    const literal = text.slice(start, position);
    const key = literal.includes('\\') ? (JSON.parse(literal) as string) : literal.slice(1, -1);
    skipSpace();
    position += 1;
    frame.currentKey = key;
    frame.keys.push(key);
    const first = key.charCodeAt(0);
    if (first >= DIGIT_ZERO && first <= DIGIT_NINE) frame.mayReorder = true;
  };

  const skipScalar = (): void => {
    if (text.charCodeAt(position) === QUOTE) {
      skipString();
      return;
    }
    while (position < end) {
      const code = text.charCodeAt(position);
      if (code === COMMA || code === CLOSE_BRACE || code === CLOSE_BRACKET) return;
      if (code === SPACE || code === TAB || code === LINE_FEED || code === CARRIAGE_RETURN) return;
      position += 1;
    }
  };

  const enterMember = (frame: Frame): void => {
    if (frame.isObject) readKey(frame);
    counterpart = childOf(frame);
  };

  for (;;) {
    skipSpace();
    const code = text.charCodeAt(position);
    let completed = true;
    if (code === OPEN_BRACE || code === OPEN_BRACKET) {
      const isObject = code === OPEN_BRACE;
      position += 1;
      const frame = openFrame(counterpart, isObject);
      stack.push(frame);
      skipSpace();
      const close = isObject ? CLOSE_BRACE : CLOSE_BRACKET;
      if (text.charCodeAt(position) === close) {
        position += 1;
        settle(frame);
        stack.pop();
      } else {
        enterMember(frame);
        completed = false;
      }
    } else {
      skipScalar();
    }
    while (completed) {
      const frame = stack[stack.length - 1];
      if (frame === undefined) return;
      skipSpace();
      const separator = text.charCodeAt(position);
      position += 1;
      if (separator === COMMA) {
        frame.index += 1;
        enterMember(frame);
        completed = false;
      } else {
        settle(frame);
        stack.pop();
      }
    }
  }
}

export function documentKeys(object: Readonly<Record<string, unknown>>): readonly string[] {
  return documentOrders.get(object) ?? Object.keys(object);
}

export function documentEntries(object: Readonly<Record<string, unknown>>): [string, unknown][] {
  return documentKeys(object).map((key) => [key, object[key]]);
}

export function documentValues(object: Readonly<Record<string, unknown>>): unknown[] {
  return documentKeys(object).map((key) => object[key]);
}

function serializeValue(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(serializeValue).join(',')}]`;
  if (isPlainObject(value)) {
    const members = documentKeys(value).map((key) => `${JSON.stringify(key)}:${serializeValue(value[key])}`);
    return `{${members.join(',')}}`;
  }
  return JSON.stringify(value);
}

export function stringifyInDocumentOrder(value: unknown): string {
  return serializeValue(value);
}
