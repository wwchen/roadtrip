import { useEffect, useRef } from 'react';
import {
  Chart,
  CategoryScale,
  LinearScale,
  LineController,
  LineElement,
  PointElement,
  TimeScale,
  Tooltip,
  type ChartDataset,
} from 'chart.js';
import 'chartjs-adapter-date-fns';
import { seriesColor } from '@tokens';
import type { AvailabilityChange } from '@/api/availability-dashboard-api';

/**
 * Chart.js, from npm and tree-shaken.
 *
 * The legacy page pulled `chart.umd.min.js` and the date adapter from jsDelivr as
 * two blocking `<script>` tags, then guarded every use with
 * `typeof Chart === 'undefined'` because a CDN can simply not answer. Bundling
 * removes the runtime dependency on a third party, pins the version in the
 * lockfile, and makes that guard unnecessary — an import either resolves at build
 * time or the build fails.
 *
 * Registered piecewise rather than via `Chart.register(...registerables)`: this is
 * one stepped line chart on a time axis, and the auto-registration pulls in every
 * controller Chart.js has (bar, pie, radar, scatter…).
 */
Chart.register(
  LineController,
  LineElement,
  PointElement,
  LinearScale,
  CategoryScale,
  TimeScale,
  Tooltip,
);

/**
 * Availability status → y position.
 *
 * Not evenly spaced, and that is deliberate from the original: `first_come` sits
 * at 1.5, between `reserved` and `available`, because it is a weaker form of
 * "you can camp here" rather than a rung of its own. `past` shares `unknown`'s
 * position — neither is a state anyone acts on.
 */
const STATUS_Y: Readonly<Record<string, number>> = {
  available: 2,
  first_come: 1.5,
  reserved: 1,
  closed: 0,
  unknown: -1,
  past: -1,
};

/** The y value a status we do not recognise is drawn at. */
const UNKNOWN_Y = -1;

/** Tick and tooltip labels, keyed by y value. */
const Y_LABELS: Readonly<Record<string, string>> = {
  2: 'available',
  1.5: 'first_come',
  1: 'reserved',
  0: 'closed',
  '-1': 'unknown',
};

const Y_MIN = -1;
const Y_MAX = 2.5;
const Y_STEP = 0.5;

const BORDER_WIDTH = 2;
const POINT_RADIUS = 4;

const TOOLTIP_TIME_FORMAT = 'yyyy-MM-dd HH:mm';

export interface ChangesChartProps {
  changes: AvailabilityChange[];
}

type Point = { x: number; y: number };

/**
 * One dataset per campsite/date pair, chronological.
 *
 * Exported for tests: the grouping key and the stepping are the substance of this
 * chart, and asserting them through a canvas is not possible in jsdom.
 */
export function buildDatasets(changes: AvailabilityChange[]): ChartDataset<'line', Point[]>[] {
  const groups = new Map<string, AvailabilityChange[]>();
  for (const change of changes) {
    const key = `${change.campsite_name || change.campsite_id} @ ${change.target_date}`;
    const group = groups.get(key);
    if (group) group.push(change);
    else groups.set(key, [change]);
  }

  return [...groups].map(([label, rows], index) => ({
    label,
    data: rows
      .slice()
      .sort((a, b) => Date.parse(a.observed_at) - Date.parse(b.observed_at))
      .map((row) => ({
        x: Date.parse(row.observed_at),
        y: STATUS_Y[row.to_status] ?? UNKNOWN_Y,
      })),
    // `before`: a status holds from the moment it was observed until the next
    // observation, so the step belongs at the new reading, not interpolated
    // towards it. Drawing it any other way would imply readings we never took.
    stepped: 'before' as const,
    borderColor: seriesColor(index),
    backgroundColor: seriesColor(index),
    borderWidth: BORDER_WIDTH,
    pointRadius: POINT_RADIUS,
    fill: false,
  }));
}

/**
 * The observed-status timeline.
 *
 * Chart.js owns a canvas imperatively, so this is the same escape-hatch shape the
 * plan prescribes for MapLibre: one effect creates the chart, its cleanup destroys
 * it. Without that destroy, Chart.js keeps the old instance attached to the canvas
 * and the next render throws "Canvas is already in use".
 */
export function ChangesChart({ changes }: ChangesChartProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const chart = new Chart(canvas, {
      type: 'line',
      data: { datasets: buildDatasets(changes) },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'nearest', intersect: false },
        scales: {
          x: {
            type: 'time',
            time: { tooltipFormat: TOOLTIP_TIME_FORMAT },
            title: { display: true, text: 'Observed at' },
          },
          y: {
            title: { display: true, text: 'Status' },
            ticks: {
              callback: (value) => Y_LABELS[String(value)] ?? '',
              stepSize: Y_STEP,
            },
            min: Y_MIN,
            max: Y_MAX,
          },
        },
        plugins: {
          tooltip: {
            callbacks: {
              label: (ctx) =>
                `${ctx.dataset.label}: ${Y_LABELS[String(ctx.parsed.y)] ?? ctx.parsed.y}`,
            },
          },
        },
      },
    });

    return () => chart.destroy();
  }, [changes]);

  return (
    <div className="rt-dash-chart">
      <canvas ref={canvasRef} />
    </div>
  );
}
