const MAX_CAUSE_DEPTH = 8;
const CAUSE_SEPARATOR = ' <- ';

export const TOPOLOGY_FAULT = 'TopologyException';

export const GEOMETRY_PARSE_FAULTS: readonly string[] = [
  'are not an array',
  'are not sufficiently nested',
  'Too few ordinates in GeoJSON',
  'invalid GeoJson representation',
];

const GEOMETRY_PARSE_FAULTS_LOWERCASE = GEOMETRY_PARSE_FAULTS.map((message) => message.toLowerCase());

export function causeChain(error: unknown): string {
  const parts: string[] = [];
  const seen = new Set<unknown>();
  let current: unknown = error;
  while (current !== undefined && current !== null && parts.length < MAX_CAUSE_DEPTH && !seen.has(current)) {
    seen.add(current);
    if (current instanceof Error) {
      parts.push(`${current.name}: ${current.message}`);
      current = current.cause;
    } else {
      parts.push(String(current));
      current = undefined;
    }
  }
  return parts.join(CAUSE_SEPARATOR);
}

export function isTopologyFault(error: unknown): boolean {
  return causeChain(error).includes(TOPOLOGY_FAULT);
}

export function isGeometryParseFault(error: unknown): boolean {
  const chain = causeChain(error).toLowerCase();
  return GEOMETRY_PARSE_FAULTS_LOWERCASE.some((message) => chain.includes(message));
}
