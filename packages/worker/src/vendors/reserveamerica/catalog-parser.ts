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

export function siteRows(html: string): string[] {
  const starts = [...html.matchAll(SITE_LABEL)].map((match) => match.index);
  return starts.map((start, position) => html.substring(start, starts[position + 1] ?? html.length));
}

export function parseCatalog(html: string): CatalogSite[] {
  const sites: CatalogSite[] = [];
  for (const row of siteRows(html)) {
    const siteId = SITE_ID.exec(row)?.[FIRST_GROUP];
    if (siteId === undefined) continue;
    const parkId = PARK_ID.exec(row)?.[FIRST_GROUP];
    if (parkId === undefined) continue;
    const label = LABEL_TEXT.exec(row)?.[FIRST_GROUP];
    const name = label === undefined ? '' : kotlinTrim(label);
    sites.push({ parkId, siteId, name: name === '' ? siteId : name });
  }
  return sites;
}
