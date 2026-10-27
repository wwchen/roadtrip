import type { AvailabilityDay } from '@/api/availability-api';
import { nightsCopy } from '@/lib/strings';
import { Button, Tag } from '@ui';
import { nightOf } from './night-state';
import './nights-strip.css';

export interface NightSitesProps {
  day: AvailabilityDay | null;
  onPick?: (campsiteId: number) => void;
}

export function NightSites({ day, onPick }: NightSitesProps) {
  if (day === null) return null;
  const night = nightOf(day);

  if (night.openIds.length === 0) {
    return (
      <p className="rt-night-sites__none">
        {night.state === 'full' ? nightsCopy.noneOpen : nightsCopy.noDetail}
      </p>
    );
  }

  return (
    <ul className="rt-night-sites">
      {night.openIds.map((id) => (
        <li key={id}>
          {onPick ? (
            <Button variant="tertiary" size="sm" onClick={() => onPick(id)}>
              {nightsCopy.site(id)}
            </Button>
          ) : (
            <Tag hue="gray">{nightsCopy.site(id)}</Tag>
          )}
        </li>
      ))}
    </ul>
  );
}
