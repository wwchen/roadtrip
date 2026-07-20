const TEMPLATE_PLACEHOLDERS = ['{start_date}', '{end_date}', '{nights}'];

export function reservationUrlFromTemplate(row, { startDate, endDate, reservationUrlTemplates } = {}) {
  const template = reservationUrlTemplate(row, reservationUrlTemplates);
  if (!template) return '';
  if (!hasTemplatePlaceholders(template)) return template;
  if (!startDate || !endDate) return '';

  const nights = nightsBetween(startDate, endDate);
  if (!Number.isFinite(nights) || nights <= 0) return '';

  return template
    .replaceAll('{start_date}', startDate)
    .replaceAll('{end_date}', endDate)
    .replaceAll('{nights}', String(nights));
}

export function hasReservationUrlTemplate(row, reservationUrlTemplates) {
  return !!reservationUrlTemplate(row, reservationUrlTemplates);
}

function reservationUrlTemplate(row, reservationUrlTemplates) {
  const raw = templateForRow(row, reservationUrlTemplates);
  return typeof raw === 'string' ? raw.trim() : '';
}

function templateForRow(row, reservationUrlTemplates) {
  if (!row || row.id == null || !reservationUrlTemplates) return '';
  if (reservationUrlTemplates instanceof Map) {
    return reservationUrlTemplates.get(String(row.id)) || reservationUrlTemplates.get(row.id) || '';
  }
  if (typeof reservationUrlTemplates === 'object') {
    return reservationUrlTemplates[String(row.id)] || '';
  }
  return '';
}

function hasTemplatePlaceholders(template) {
  return TEMPLATE_PLACEHOLDERS.some((placeholder) => template.includes(placeholder));
}

function nightsBetween(startDate, endDate) {
  const start = Date.parse(`${startDate}T00:00:00Z`);
  const end = Date.parse(`${endDate}T00:00:00Z`);
  if (!Number.isFinite(start) || !Number.isFinite(end)) return NaN;
  return Math.round((end - start) / 86400000);
}

export function bookingLabel(row, reservationUrlTemplates) {
  const agency = agencyLabel(row, reservationUrlTemplates);
  return agency ? `Book on ${agency}` : 'Book';
}

export function agencyLabel(row, reservationUrlTemplates) {
  const template = reservationUrlTemplate(row, reservationUrlTemplates);
  const host = hostFromUrl(template);
  if (host === 'recreation.gov' || host === 'www.recreation.gov') return 'Recreation.gov';
  if (host === 'reservation.pc.gc.ca') return 'Parks Canada';
  if (host === 'camping.bcparks.ca' || host === 'discovercamping.ca') return 'BC Parks';
  if (host === 'washington.goingtocamp.com') return 'Washington State Parks';

  const vendor = String(row?.booking_provider || row?.data_provider || '').toLowerCase();
  if (vendor === 'recgov') return 'Recreation.gov';
  if (vendor === 'campflare') return 'Campflare';
  if (vendor === 'aspira') return 'Aspira';
  return labelFromHost(host) || humanizeAgency(vendor);
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
  return base ? humanizeAgency(base) : '';
}

function humanizeAgency(key) {
  return String(key)
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/\b\w/g, (char) => char.toUpperCase());
}
