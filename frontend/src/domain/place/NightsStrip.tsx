import type { AvailabilityDay } from '@/api/availability-api';
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

const STATE_WORDS = {
  open: (night: Night) => nightsCopy.open(night.openIds.length),
  full: () => nightsCopy.full,
  unknown: () => nightsCopy.unknown,
} satisfies Record<NightState, (night: Night) => string>;

const STATE_COUNT = {
  open: (night: Night) => nightsCopy.openCount(night.openIds.length),
  full: () => nightsCopy.fullCount,
  unknown: () => nightsCopy.unknownCount,
} satisfies Record<NightState, (night: Night) => string>;

export function NightsStrip({ days, dates, selected, onSelect }: NightsStripProps) {
  const dayByDate = new Map(days.map((day) => [day.date, day]));

  return (
    <div className="rt-nights" role="group" aria-label={nightsCopy.group}>
      {dates.map((date) => {
        const night = nightOf(dayByDate.get(date));
        const label = `${longDayLabel(date)}, ${STATE_WORDS[night.state](night)}`;
        return (
          <Chip
            key={date}
            className="rt-nights__night"
            selected={date === selected}
            disabled={night.state === 'unknown'}
            data-state={night.state}
            aria-label={label}
            onClick={() => onSelect(date)}
          >
            <span className="rt-nights__dow">{dowLabel(date)}</span>
            <span className="rt-nights__day">{dayOfMonthLabel(date)}</span>
            <span className="rt-nights__count">{STATE_COUNT[night.state](night)}</span>
          </Chip>
        );
      })}
    </div>
  );
}
