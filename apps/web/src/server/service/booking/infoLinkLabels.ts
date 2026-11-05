import type { TenantRegistry } from '@roadtrip/domain';

const SCHEME_SEPARATOR = '://';
const HOST_TERMINATORS = /[/?#]/;
const PORT_SEPARATOR = ':';
const HOST_WWW_PREFIX = 'www.';
const UNNAMED_LINK_LABEL = 'Visit website';

const AGENCY_LABELS: readonly (readonly [readonly string[], string])[] = [
  [['fs.usda.gov'], 'Park info on fs.usda.gov'],
  [['nps.gov'], 'Park info on nps.gov'],
  [['blm.gov'], 'Park info on blm.gov'],
  [['fws.gov'], 'Park info on fws.gov'],
  [['usace.army.mil'], 'Park info on usace.army.mil'],
  [['usbr.gov'], 'Park info on usbr.gov'],
  [['tva.gov'], 'Park info on tva.gov'],
  [['bcparks.ca'], 'Park info on bcparks.ca'],
  [['albertaparks.ca'], 'Park info on albertaparks.ca'],
  [['pc.gc.ca', 'parks.canada.ca'], 'Park info on parks.canada.ca'],
  [['planetfitness.com'], 'Visit planetfitness.com'],
  [['tesla.com'], 'View on tesla.com'],
];

export function urlHost(url: string): string | null {
  const schemeIndex = url.indexOf(SCHEME_SEPARATOR);
  if (schemeIndex <= 0) return null;
  const afterScheme = url.slice(schemeIndex + SCHEME_SEPARATOR.length);
  const end = afterScheme.search(HOST_TERMINATORS);
  const hostPort = end < 0 ? afterScheme : afterScheme.slice(0, end);
  const host = hostPort.split(PORT_SEPARATOR)[0]!.toLowerCase().trim();
  if (host.length === 0) return null;
  return host.startsWith(HOST_WWW_PREFIX) ? host.slice(HOST_WWW_PREFIX.length) : host;
}

export function infoLinkLabel(tenants: TenantRegistry, url: string): string {
  const host = urlHost(url);
  if (host === null) return UNNAMED_LINK_LABEL;
  const vendor = tenants.linkLabel(host);
  if (vendor !== null) return vendor;
  const agency = AGENCY_LABELS.find(([suffixes]) => suffixes.some((suffix) => host.endsWith(suffix)));
  return agency?.[1] ?? `Visit ${host}`;
}
