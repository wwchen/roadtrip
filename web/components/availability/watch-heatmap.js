// Watch detail heatmap: groups of (reservable × target_date) cells colored
// by latest snapshot status.

const STATUS_CLASS = {
  available: 'cell-available',
  partial: 'cell-partial',
  booked: 'cell-booked',
  closed: 'cell-closed',
};

export function renderWatchHeatmap(rootEl, response) {
  const dates = response.target_dates;
  const headerRow = `
    <tr>
      <th class="rowhead">site</th>
      ${dates.map((d) => `<th>${escapeHtml(formatShortDate(d))}</th>`).join('')}
    </tr>
  `;
  const groupsHtml = response.groups.map((g) => renderGroup(g, headerRow)).join('');
  rootEl.innerHTML = `
    <div class="heatmap-legend">
      <span class="legend-swatch cell-available"></span> available
      <span class="legend-swatch cell-booked"></span> booked
      <span class="legend-swatch cell-closed"></span> closed
      <span class="legend-swatch cell-empty"></span> no snapshot
    </div>
    ${groupsHtml}
  `;
}

function renderGroup(group, headerRow) {
  const loopLabel = group.loop ? escapeHtml(group.loop) : '<span class="muted">(no loop)</span>';
  const rows = group.rows.map(renderRow).join('');
  return `
    <section class="heatmap-group">
      <h3 class="heatmap-group-title">${loopLabel}</h3>
      <table class="data-table heatmap-table">
        <thead>${headerRow}</thead>
        <tbody>${rows}</tbody>
      </table>
    </section>
  `;
}

function renderRow(row) {
  const label = row.name ? `${escapeHtml(row.name)} <span class="muted">${escapeHtml(row.reservable_rid)}</span>` : escapeHtml(row.reservable_rid);
  const cells = row.cells.map(renderCell).join('');
  return `
    <tr>
      <td class="rowhead">
        <a href="/availability?tab=snapshots&reservable_rid=${encodeURIComponent(row.reservable_rid)}">${label}</a>
      </td>
      ${cells}
    </tr>
  `;
}

function renderCell(cell) {
  const cls = cell.status ? STATUS_CLASS[cell.status] || 'cell-unknown' : 'cell-empty';
  const title = cell.observed_at
    ? `${cell.status || 'unknown'} as of ${formatTimestamp(cell.observed_at)}`
    : 'no snapshot';
  return `<td class="heatmap-cell ${cls}" title="${escapeHtml(title)}"></td>`;
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function formatShortDate(iso) {
  const [_, m, d] = iso.split('-');
  return `${parseInt(m, 10)}/${d}`;
}

function formatTimestamp(iso) {
  return iso.replace('T', ' ').replace(/\.\d+/, '').replace(/Z$/, '');
}
