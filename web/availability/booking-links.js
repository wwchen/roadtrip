const TEMPLATE_PLACEHOLDERS = ['{start_date}', '{end_date}', '{nights}'];

export function reservationUrlFromTemplate(row, { startDate, endDate } = {}) {
  const template = reservationUrlTemplate(row);
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

export function hasReservationUrlTemplate(row) {
  return !!reservationUrlTemplate(row);
}

function reservationUrlTemplate(row) {
  const raw = row?.reservation_url_template;
  return typeof raw === 'string' ? raw.trim() : '';
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
