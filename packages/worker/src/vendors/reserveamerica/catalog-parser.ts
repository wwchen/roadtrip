import { kotlinTrim } from '../../etl/kotlin-text.js';

export interface CatalogSite {
  parkId: string;
  siteId: string;
  name: string;
}

const SITE_LABEL = /<div class='siteListLabel'>/g;
const SITE_ID = /siteId=(\d+)/;
const PARK_ID = /parkId=(\d+)/;
const LABEL_TEXT = />([^<]+)<\/a>/;
const FIRST_GROUP = 1;

export function firstGroup(pattern: RegExp, text: string): string | null {
  return pattern.exec(text)?.[FIRST_GROUP] ?? null;
}

export function siteRows(html: string): string[] {
  const starts = [...html.matchAll(SITE_LABEL)].map((match) => match.index);
  return starts.map((start, position) => html.substring(start, starts[position + 1] ?? html.length));
}

export function parseCatalog(html: string): CatalogSite[] {
  const sites: CatalogSite[] = [];
  for (const row of siteRows(html)) {
    const siteId = firstGroup(SITE_ID, row);
    if (siteId === null) continue;
    const parkId = firstGroup(PARK_ID, row);
    if (parkId === null) continue;
    const label = firstGroup(LABEL_TEXT, row);
    const name = label === null ? '' : kotlinTrim(label);
    sites.push({ parkId, siteId, name: name === '' ? siteId : name });
  }
  return sites;
}
