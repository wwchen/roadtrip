export function localToday() {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), now.getDate());
}

export function localYmd(date) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

export function parseLocalYmd(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(value || ''));
  if (!match) return new Date(Number.NaN);
  const [, y, m, d] = match;
  return new Date(Number(y), Number(m) - 1, Number(d));
}

export function addLocalDays(date, days) {
  const next = new Date(date);
  next.setDate(date.getDate() + days);
  return next;
}

export function startOfLocalMonth(date) {
  return new Date(date.getFullYear(), date.getMonth(), 1);
}

export function addLocalMonths(date, months) {
  return new Date(date.getFullYear(), date.getMonth() + months, 1);
}

export function sameLocalDay(a, b) {
  return localYmd(a) === localYmd(b);
}
