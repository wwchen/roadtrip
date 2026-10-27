import type { AvailabilityDay } from '@/api/availability-api';
import { nightsCopy } from '@/lib/strings';
import { Button, Tag } from '@ui';
import { nightOf, type NightState } from './night-state';
import './nights-strip.css';

export interface NightSitesProps {
  day: AvailabilityDay | null;
  names?: ReadonlyMap<number, string>;
  onPick?: (campsiteId: number) => void;
}

const NO_SITES_LINE = {
  full: nightsCopy.noneOpen,
  first_come: nightsCopy.firstCome,
  closed: nightsCopy.closedNight,
  past: nightsCopy.noDetail,
  unknown: nightsCopy.noDetail,
} satisfies Record<Exclude<NightState, 'open'>, string>;

export function NightSites({ day, names, onPick }: NightSitesProps) {
  if (day === null) return null;
  const night = nightOf(day);

  if (night.state !== 'open') {
    return <p className="rt-night-sites__none">{NO_SITES_LINE[night.state]}</p>;
  }

  return (
    <ul className="rt-night-sites">
      {night.openIds.map((id) => {
        const label = names?.get(id) ?? nightsCopy.site(id);
        return (
          <li key={id}>
            {onPick ? (
              <Button variant="tertiary" size="sm" onClick={() => onPick(id)}>
                {label}
              </Button>
            ) : (
              <Tag hue="gray">{label}</Tag>
            )}
          </li>
        );
      })}
    </ul>
  );
}
