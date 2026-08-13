// One template's timeline, anchored to a user-picked start date.
//
// The date input is a plain <input type="date">: LDS has no date primitive, and
// the availability feature's picker is off-limits across the feature boundary.
// The value round-trips through lib/local-date.ts so the wire format stays a
// local calendar date.
import { useId, useState } from 'react';
import { Banner, Button, EmptyState, Row, Skeleton, Tag } from '@ui';
import type { TimelineDay, TripTemplate } from '@/api/planning-api';
import { localToday, localYmd, parseLocalYmd } from '@/lib/local-date';
import { useTripTimeline } from './usePlanning';
import { bookingStateHue, gradeHue, LIST_SEPARATOR } from './plan-format';

const SUPERCHARGER_PREFIX = '⚡';

export interface TimelineViewProps {
  template: TripTemplate;
  onBack: () => void;
}

/** `{from} → {to}, {miles} mi / {minutes} min · ⚡…` or null on layover days. */
function driveLine(day: TimelineDay): string | null {
  const drive = day.drive;
  if (!drive) return null;
  const chargers = drive.superchargers.length
    ? LIST_SEPARATOR + SUPERCHARGER_PREFIX
      + drive.superchargers.join(LIST_SEPARATOR + SUPERCHARGER_PREFIX)
    : '';
  return `${drive.from} → ${drive.to}, ${drive.miles} mi / ${drive.minutes} min${chargers}`;
}

function DayRow({ day }: { day: TimelineDay }) {
  const drive = driveLine(day);
  return (
    <Row
      title={`Day ${day.day} · ${day.date} — ${day.title}`}
      subtitle={
        <>
          {drive && <div>{drive}</div>}
          {day.stay && <div>Stay: {day.stay.name}</div>}
          {day.highlights.length > 0 && <div>{day.highlights.join(LIST_SEPARATOR)}</div>}
        </>
      }
      trail={
        <span className="rt-plan-tags">
          <Tag hue={gradeHue(day.evStatus)}>EV: {day.evStatus}</Tag>
          {day.stay && (
            <Tag hue={bookingStateHue(day.stay.bookingState)}>{day.stay.bookingState}</Tag>
          )}
        </span>
      }
    />
  );
}

export function TimelineView({ template, onBack }: TimelineViewProps) {
  const [start, setStart] = useState(() => localYmd(localToday()));
  const startValid = !Number.isNaN(parseLocalYmd(start).getTime());
  const { timeline, isPending, error, refetch } = useTripTimeline(
    template.id,
    start,
    startValid,
  );
  const dateInputId = useId();

  return (
    <section>
      <div className="rt-plan-timeline-controls">
        <Button variant="secondary" onClick={onBack}>
          Back to trips
        </Button>
        <label className="rt-plan-date-label" htmlFor={dateInputId}>
          Start date
          <input
            id={dateInputId}
            className="rt-plan-date"
            type="date"
            value={start}
            onChange={(e) => setStart(e.target.value)}
          />
        </label>
      </div>

      <h2 className="rt-plan-timeline-title">{template.name}</h2>

      {!startValid ? (
        <EmptyState
          title="Pick a start date"
          body="Choose the day the trip begins to lay the route onto the calendar."
        />
      ) : error ? (
        <Banner
          status="error"
          actions={
            <button type="button" onClick={refetch}>
              Retry
            </button>
          }
        >
          Could not load the timeline.
        </Banner>
      ) : isPending ? (
        <Skeleton aria-label="Loading timeline" />
      ) : !timeline || timeline.days.length === 0 ? (
        <EmptyState
          title="No days in this timeline"
          body="This template has no scheduled days for the chosen start date."
        />
      ) : (
        <>
          {timeline.warnings.length > 0 && (
            <Banner status="warning" title="Heads up">
              {timeline.warnings.map((warning) => (
                <div key={warning}>{warning}</div>
              ))}
            </Banner>
          )}
          <div className="rt-plan-days">
            {timeline.days.map((day) => (
              <DayRow key={day.day} day={day} />
            ))}
          </div>
        </>
      )}
    </section>
  );
}
