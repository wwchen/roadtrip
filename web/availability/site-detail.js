// Site detail panel for reservable rows. Rendering stays pure; callers can use
// fetchSiteDetail to load the detail payload and merge it with the catalog row.

import { escapeHtml } from '../core.js';
import { fetchReservableDetails } from '../api/reservable-api.js';
import { reservationUrlFromTemplate } from './booking-links.js';

const MAX_FEATURES = 12;

export async function fetchSiteDetail(rid, { signal } = {}) {
  const body = await fetchReservableDetails(rid, { signal });
  const detail = objectValue(body?.reservable);
  const poiIds = Array.isArray(body?.poi_ids) ? body.poi_ids : [];
  if (!Array.isArray(detail.poi_ids) || detail.poi_ids.length === 0) {
    return { ...detail, poi_ids: poiIds };
  }
  return detail;
}

export function mergeSiteDetail(baseSite, detailSite) {
  const base = objectValue(baseSite);
  const detail = objectValue(detailSite);
  const merged = { ...base, ...detail };
  if (base.reservation_url_template && !detail.reservation_url_template) {
    merged.reservation_url_template = base.reservation_url_template;
  }
  if (base.raw && !detail.raw) {
    merged.raw = base.raw;
  }
  if (Array.isArray(base.poi_ids) && base.poi_ids.length > 0) {
    const detailPoiIds = Array.isArray(detail.poi_ids) ? detail.poi_ids : [];
    if (detailPoiIds.length === 0) merged.poi_ids = base.poi_ids;
  }
  return merged;
}

export function renderSiteDetail({ site, selectedDate = null, selectedEndDate = null } = {}) {
  if (!site) return '';
  const raw = objectValue(site.raw);
  const tags = objectValue(site.tags);
  const name = siteName(site);
  const imageUrl = findImageUrl(site);
  const description = descriptionText(site.description ?? raw.description ?? raw.campsite_description);
  const facts = detailFacts(site, raw, tags);
  const features = featureLabels(raw, tags);
  const url = reservationUrlFromTemplate(site, { startDate: selectedDate, endDate: selectedEndDate });
  const bookLabel = bookingLabel(site, url);
  const subtitle = selectedDate || '';

  return `
    <section class="cg-site-detail" aria-label="Site details">
      <div class="cg-site-detail-head">
        <div class="cg-site-detail-title-wrap">
          <div class="cg-site-detail-title" title="${escapeHtml(name)}">${escapeHtml(name)}</div>
          ${subtitle ? `<div class="cg-site-detail-subtitle">${escapeHtml(subtitle)}</div>` : ''}
        </div>
        <button type="button" class="cg-site-detail-close" data-site-detail-close aria-label="Close site details">Close</button>
      </div>
      ${imageUrl ? `<div class="cg-site-detail-media"><img src="${escapeHtml(imageUrl)}" alt="${escapeHtml(name)}"></div>` : ''}
      ${description ? `<p class="cg-site-detail-description">${escapeHtml(description)}</p>` : ''}
      ${renderFacts(facts)}
      ${renderFeatures(features)}
      ${
        url
          ? `<a class="cg-site-detail-book" href="${escapeHtml(url)}" target="_blank" rel="noreferrer">${escapeHtml(bookLabel)}</a>`
          : ''
      }
    </section>
  `;
}

function detailFacts(site, raw, tags) {
  const facts = [];
  addFact(facts, 'Loop', site.loop || raw.loop || raw._parent_leaf_name);
  addFact(facts, 'Type', site.site_type || raw.site_type || raw.campsite_type);
  addFact(facts, 'Capacity', capacityLabel(site, raw, tags));
  addFact(facts, 'Reserve', firstString(tags.reserve_type, raw.campsite_reserve_type, raw.reserve_type, raw.reserveType));
  addFact(facts, 'Use', firstString(tags.use, raw.type_of_use, raw.typeOfUse));
  addFact(facts, 'Equipment', equipmentLabel(raw, tags));
  addFact(facts, 'Provider', site.vendor);
  addFact(facts, 'Provider ID', site.vendor_id || site.vendorId);
  return facts;
}

function bookingLabel(site, url) {
  const agency = agencyLabel(site, url);
  return agency ? `Book on ${agency}` : 'Book';
}

function agencyLabel(site, url) {
  const host = hostFromUrl(site.reservation_url_template || url);
  if (host === 'recreation.gov' || host === 'www.recreation.gov') return 'Recreation.gov';
  if (host === 'reservation.pc.gc.ca') return 'Parks Canada';
  if (host === 'camping.bcparks.ca' || host === 'discovercamping.ca') return 'BC Parks';
  if (host === 'washington.goingtocamp.com') return 'Washington State Parks';

  const vendor = String(site.vendor || '').toLowerCase();
  if (vendor === 'recgov') return 'Recreation.gov';
  if (vendor === 'aspira_pc') return 'Parks Canada';
  if (vendor === 'aspira_bc') return 'BC Parks';
  if (vendor === 'aspira_wa') return 'Washington State Parks';
  if (vendor.startsWith('aspira_')) return 'Aspira';
  return labelFromHost(host) || humanize(vendor);
}

function hostFromUrl(url) {
  if (!url || typeof url !== 'string') return '';
  try {
    return new URL(url).hostname.toLowerCase();
  } catch {
    return '';
  }
}

function labelFromHost(host) {
  const base = String(host || '').replace(/^www\./, '').split('.')[0];
  return base ? humanize(base) : '';
}

function addFact(facts, label, value) {
  const text = compactText(formatValue(value));
  if (text) facts.push({ label, value: text });
}

function renderFacts(facts) {
  if (!facts.length) return '';
  const rows = facts
    .map(
      (fact) => `
        <div class="cg-site-detail-fact">
          <span>${escapeHtml(fact.label)}</span>
          <strong>${escapeHtml(fact.value)}</strong>
        </div>
      `,
    )
    .join('');
  return `<div class="cg-site-detail-facts">${rows}</div>`;
}

function renderFeatures(features) {
  if (!features.length) return '';
  const tags = features
    .slice(0, MAX_FEATURES)
    .map((feature) => `<span class="cg-site-detail-feature">${escapeHtml(feature)}</span>`)
    .join('');
  return `<div class="cg-site-detail-features">${tags}</div>`;
}

function siteName(site) {
  if (site.name) return site.name;
  if (site.vendor_id) return `Site #${site.vendor_id}`;
  return site.rid || '(unknown)';
}

function capacityLabel(site, raw, tags = {}) {
  const tagCapacity = objectValue(tags.capacity);
  const min = numberValue(
    tagCapacity.min ??
      site.min_capacity ??
      site.minCapacity ??
      raw.min_capacity ??
      raw.minCapacity ??
      raw.min_num_people ??
      raw.minNumPeople,
  );
  const max = numberValue(
    tagCapacity.max ??
      site.max_capacity ??
      site.maxCapacity ??
      raw.max_capacity ??
      raw.maxCapacity ??
      raw.max_num_people ??
      raw.maxNumPeople ??
      raw.capacity_rating,
  );
  if (min != null && max != null && min !== max) return `${min}-${max} people`;
  if (max != null) return `Up to ${max} people`;
  if (min != null) return `${min}+ people`;
  return '';
}

function equipmentLabel(raw, tags = {}) {
  return itemList(tags.equipment ?? raw.allowed_equipment ?? raw.allowedEquipment ?? raw.equipment)
    .slice(0, 4)
    .join(', ');
}

function featureLabels(raw, tags = {}) {
  const labels = [
    ...tagAttributeLabels(tags.attributes),
    ...attributeLabels(raw.defined_attributes ?? raw.definedAttributes),
    ...attributeLabels(raw.attributes),
    ...attributeLabels(raw.campsite_rules ?? raw.campsiteRules),
    ...attributeLabels(raw.supplemental_camping ?? raw.supplementalCamping),
  ];
  return unique(labels.map((label) => truncateText(compactText(label), 84)).filter(Boolean));
}

function tagAttributeLabels(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return [];
  return Object.entries(value)
    .map(([key, entryValue]) => {
      const formatted = formatValue(entryValue);
      return formatted ? `${humanize(key)}: ${formatted}` : humanize(key);
    })
    .filter(Boolean);
}

function attributeLabels(value) {
  if (Array.isArray(value)) {
    return value.flatMap((item) => attributeLabels(item));
  }
  if (value && typeof value === 'object') {
    const name = firstString(
      value.name,
      value.label,
      value.title,
      value.display_name,
      value.displayName,
      value.attribute_name,
      value.attributeName,
      value.description,
    );
    const rawValue =
      value.value ??
      value.values ??
      value.attribute_value ??
      value.attributeValue ??
      value.boolean_value ??
      value.booleanValue;
    const formatted = formatValue(rawValue);
    if (name && formatted && formatted !== 'true') return [`${name}: ${formatted}`];
    if (name) return [name];
    if (isUnresolvedAttribute(value)) return [];
    return Object.entries(value)
      .filter(([key]) => !/^id$/i.test(key))
      .map(([key, entryValue]) => `${humanize(key)}: ${formatValue(entryValue)}`)
      .filter((label) => !label.endsWith(': '));
  }
  const text = formatValue(value);
  return text ? [text] : [];
}

function isUnresolvedAttribute(value) {
  return (
    value.definition_id != null ||
    value.definitionId != null ||
    value.attribute_definition_id != null ||
    value.attributeDefinitionId != null
  );
}

function itemList(value) {
  if (Array.isArray(value)) {
    return value.flatMap((item) => itemList(item));
  }
  if (value && typeof value === 'object') {
    const label = firstString(
      value.name,
      value.label,
      value.title,
      value.description,
      value.equipment_name,
      value.equipmentName,
    );
    return label ? [label] : [];
  }
  const text = formatValue(value);
  return text ? [text] : [];
}

function formatValue(value) {
  if (value == null || value === false) return '';
  if (value === true) return 'true';
  if (typeof value === 'number') return Number.isFinite(value) ? String(value) : '';
  if (typeof value === 'string') return stripHtml(value);
  if (Array.isArray(value)) return value.map(formatValue).filter(Boolean).join(', ');
  if (typeof value === 'object') {
    return firstString(value.name, value.label, value.title, value.description, value.value);
  }
  return '';
}

function firstString(...values) {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) return stripHtml(value);
    if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  }
  return '';
}

function descriptionText(value) {
  const text = compactText(formatValue(value));
  if (!text) return '';
  return text.length > 260 ? `${text.slice(0, 257).trim()}...` : text;
}

function stripHtml(value) {
  return String(value)
    .replace(/<[^>]*>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function compactText(value) {
  return typeof value === 'string' ? value.replace(/\s+/g, ' ').trim() : '';
}

function truncateText(value, maxLength) {
  if (!value || value.length <= maxLength) return value;
  return `${value.slice(0, maxLength - 3).trim()}...`;
}

function humanize(key) {
  return String(key)
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/\b\w/g, (char) => char.toUpperCase());
}

function numberValue(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return null;
}

function objectValue(value) {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
}

function unique(values) {
  const seen = new Set();
  const out = [];
  for (const value of values) {
    const key = value.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(value);
  }
  return out;
}

function findImageUrl(site) {
  const urls = [];
  collectImageUrls(site.raw, urls);
  collectImageUrls(site, urls);
  return urls[0] || '';
}

function collectImageUrls(value, urls, key = '', seen = new WeakSet(), depth = 0) {
  if (urls.length > 0 || value == null || depth > 6) return;
  if (typeof value === 'string') {
    if (isPlausibleImageUrl(value, key)) urls.push(value);
    return;
  }
  if (Array.isArray(value)) {
    for (const item of value) collectImageUrls(item, urls, key, seen, depth + 1);
    return;
  }
  if (typeof value !== 'object') return;
  if (seen.has(value)) return;
  seen.add(value);
  for (const [childKey, childValue] of Object.entries(value)) {
    collectImageUrls(childValue, urls, childKey, seen, depth + 1);
    if (urls.length > 0) return;
  }
}

function isPlausibleImageUrl(value, key) {
  if (!/^https?:\/\//i.test(value)) return false;
  const normalizedKey = String(key).toLowerCase();
  if (normalizedKey.includes('map')) return false;
  const keyLooksLikeImage = /(image|img|photo|media|thumbnail|thumb)/i.test(normalizedKey);
  const urlLooksLikeImage = /\.(avif|gif|jpe?g|png|webp)(\?|#|$)/i.test(value);
  return keyLooksLikeImage || urlLooksLikeImage;
}
