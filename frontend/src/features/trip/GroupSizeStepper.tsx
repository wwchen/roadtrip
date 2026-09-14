// The group-size stepper: party size from 2 (no constraint) to the maximum. Off turns off the filter.
import { Button, Icon } from '@ui';
import { filterCopy } from '@/lib/strings';
import { MAX_GROUP_SIZE, MIN_GROUP_SIZE } from '@/stores/campgroundFilterStore';

export interface GroupSizeStepperProps {
  value: number | null;
  onChange: (next: number | null) => void;
}

export function GroupSizeStepper({ value, onChange }: GroupSizeStepperProps) {
  const decrement = () => onChange(value == null || value <= MIN_GROUP_SIZE ? null : value - 1);
  const increment = () => onChange(value == null ? MIN_GROUP_SIZE + 1 : Math.min(value + 1, MAX_GROUP_SIZE));
  return (
    <div className="tb-stepper" role="group" aria-label={filterCopy.groupSize}>
      <Button variant="tertiary" size="sm" iconOnly aria-label={filterCopy.fewerPeople} disabled={value == null} onClick={decrement}>
        <Icon name="minus" aria-hidden="true" />
      </Button>
      <span className="tb-stepper-value" aria-live="polite">
        {value ?? MIN_GROUP_SIZE}
      </span>
      <Button variant="tertiary" size="sm" iconOnly aria-label={filterCopy.morePeople} disabled={value === MAX_GROUP_SIZE} onClick={increment}>
        <Icon name="add" aria-hidden="true" />
      </Button>
    </div>
  );
}
