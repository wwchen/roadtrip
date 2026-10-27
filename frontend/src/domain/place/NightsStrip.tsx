import type { AvailabilityDay } from '@/api/availability-api';
import { availabilityStatusMeta, type AvailabilityStatus } from '@/lib/availability-status';
import { nightsCopy } from '@/lib/strings';
import { dayOfMonthLabel, dowLabel, longDayLabel } from '@/lib/week-labels';
import { Chip } from '@ui';
import { nightOf, type Night, type NightState } from './night-state';
import './nights-strip.css';

export interface NightsStripProps {
  days: readonly AvailabilityDay[];
  dates: readonly string[];
  selected: string | null;
  onSelect: (date: string) => void;
}

interface NightAppearance {
  words: (night: Night) => string;
  count: (night: Night) => string;
  selectable: boolean;
}

function fromStatus(status: AvailabilityStatus, selectable: boolean): NightAppearance {
  const meta = availabilityStatusMeta(status);
  return { words: () => meta.text, count: () => meta.label, selectable };
}

const APPEARANCE = {
  open: {
    words: (night) => nightsCopy.open(night.openIds.length),
    count: (night) => nightsCopy.openCount(night.openIds.length),
    selectable: true,
  },
  full: { words: () => nightsCopy.full, count: () => nightsCopy.fullCount, selectable: true },
  first_come: fromStatus('first_come', true),
  closed: fromStatus('closed', true),
  past: fromStatus('past', false),
  unknown: { words: () => nightsCopy.unknown, count: () => nightsCopy.unknownCount, selectable: false },
} satisfies Record<NightState, NightAppearance>;

export function NightsStrip({ days, dates, selected, onSelect }: NightsStripProps) {
  const dayByDate = new Map(days.map((day) => [day.date, day]));

  return (
    <div className="rt-nights" role="group" aria-label={nightsCopy.group}>
      {dates.map((date) => {
        const night = nightOf(dayByDate.get(date));
        const appearance = APPEARANCE[night.state];
        const label = `${longDayLabel(date)}, ${appearance.words(night)}`;
        return (
          <Chip
            key={date}
            className="rt-nights__night"
            selected={date === selected}
            disabled={!appearance.selectable}
            data-state={night.state}
            aria-label={label}
            onClick={() => onSelect(date)}
          >
            <span className="rt-nights__dow">{dowLabel(date)}</span>
            <span className="rt-nights__day">{dayOfMonthLabel(date)}</span>
            <span className="rt-nights__count">{appearance.count(night)}</span>
          </Chip>
        );
      })}
    </div>
  );
}
