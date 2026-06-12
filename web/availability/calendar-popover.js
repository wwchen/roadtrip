// Month calendar popover. Click the week-nav label to open; click a day
// to jump that week into view. Used by availability-week.js for week-level
// navigation; if we get a second consumer it'll move out into a shared
// widget folder.
//
// State held inside the renderer: which month is being viewed (arrows
// inside the popover let you flip months without closing). The parent
// owns "selected day" — the popover just emits onPick(date).

import { escapeHtml } from '../core.js';

const DOW_HEADERS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MS_PER_DAY = 24 * 60 * 60 * 1000;

/**
 * Render the popover into `host`. Returns a controller with `dispose()`.
 *
 * @param {HTMLElement} host
 * @param {object}      args
 * @param {Date}        args.viewMonth  First-of-month for the visible page.
 * @param {Date}        args.today
 * @param {Date|null}   args.selectedDate  Visible week's start, for highlight.
 * @param {Date}        args.maxDate    Last day allowed (provider horizon).
 * @param {(date: Date) => void} args.onPick  Day clicked — parent jumps.
 * @param {() => void}  args.onClose    User dismissed (outside click / esc).
 */
export function mountCalendarPopover(host, args) {
  let viewMonth = startOfMonth(args.viewMonth);
  const { today, selectedDate, maxDate, onPick, onClose } = args;

  function rerender() {
    host.innerHTML = renderPopover({ viewMonth, today, selectedDate, maxDate });
  }

  function onClick(e) {
    const tgt = e.target;
    if (!(tgt instanceof Element)) return;

    if (tgt.closest('.cg-cal-prev')) {
      viewMonth = addMonths(viewMonth, -1);
      rerender();
      return;
    }
    if (tgt.closest('.cg-cal-next')) {
      viewMonth = addMonths(viewMonth, 1);
      rerender();
      return;
    }
    const dayBtn = tgt.closest('.cg-cal-day:not([disabled])');
    if (dayBtn) {
      const iso = dayBtn.getAttribute('data-date');
      onPick(new Date(iso + 'T00:00:00Z'));
    }
  }

  function onDocClick(e) {
    if (host.contains(e.target)) return;
    onClose();
  }

  function onKey(e) {
    if (e.key === 'Escape') onClose();
  }

  rerender();
  host.addEventListener('click', onClick);
  // Defer document-level listeners by a tick so the click that opened the
  // popover doesn't immediately close it.
  setTimeout(() => {
    document.addEventListener('click', onDocClick);
    document.addEventListener('keydown', onKey);
  }, 0);

  return {
    dispose() {
      host.removeEventListener('click', onClick);
      document.removeEventListener('click', onDocClick);
      document.removeEventListener('keydown', onKey);
    },
  };
}

function renderPopover({ viewMonth, today, selectedDate, maxDate }) {
  const title = viewMonth.toLocaleDateString('en-US', { month: 'long', year: 'numeric', timeZone: 'UTC' });
  const cells = monthCells(viewMonth);
  const todayIso = isoDate(today);
  const selectedIso = selectedDate ? isoDate(selectedDate) : null;
  const maxIso = isoDate(maxDate);
  const minIso = isoDate(today);

  const headers = DOW_HEADERS.map((d) => `<div class="cg-cal-head">${d}</div>`).join('');
  const dayCells = cells
    .map((d) => {
      if (!d) return `<div class="cg-cal-cell cg-cal-empty"></div>`;
      const iso = isoDate(d);
      const inMonth = d.getUTCMonth() === viewMonth.getUTCMonth();
      const disabled = iso < minIso || iso > maxIso;
      const classes = [
        'cg-cal-cell',
        'cg-cal-day',
        inMonth ? '' : 'cg-cal-faint',
        iso === todayIso ? 'cg-cal-today' : '',
        iso === selectedIso ? 'cg-cal-selected' : '',
      ]
        .filter(Boolean)
        .join(' ');
      return `<button type="button" class="${classes}" data-date="${iso}" ${disabled ? 'disabled' : ''}>${d.getUTCDate()}</button>`;
    })
    .join('');

  return `
    <div class="cg-cal-popover" role="dialog" aria-label="Pick a week">
      <div class="cg-cal-head-row">
        <button type="button" class="cg-cal-prev" aria-label="Previous month">‹</button>
        <div class="cg-cal-title">${escapeHtml(title)}</div>
        <button type="button" class="cg-cal-next" aria-label="Next month">›</button>
      </div>
      <div class="cg-cal-grid">${headers}${dayCells}</div>
    </div>
  `;
}

/**
 * 6 rows × 7 cols of UTC dates covering the month, padded with the
 * surrounding days so the calendar reads like a normal monthly grid.
 */
function monthCells(viewMonth) {
  const first = startOfMonth(viewMonth);
  const startDow = first.getUTCDay();
  const gridStart = addDays(first, -startDow);
  const out = [];
  for (let i = 0; i < 42; i++) out.push(addDays(gridStart, i));
  return out;
}

function startOfMonth(d) {
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1));
}

function addMonths(d, n) {
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + n, 1));
}

function addDays(d, n) {
  return new Date(d.getTime() + n * MS_PER_DAY);
}

function isoDate(d) {
  return d.toISOString().slice(0, 10);
}
