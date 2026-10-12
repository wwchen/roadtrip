// The filter block under the search row: the pill, the three local filters, the
// date window, and the availability check over it. Filter state lives in the
// store, and every control writes it as it changes — the dates included, once
// both ends are picked; only "is the block open" is local. The check is the one
// thing that waits for a click, because it is the one that asks the providers.
import { Button, Chip, Icon, SegmentedControl } from '@ui';
import type { CampsiteKind } from '@/api/generated/api-types';
import { FILTER_AMENITIES, FILTER_SITE_TYPES } from '@/lib/campground-vocab';
import { filterCopy, stayCopy } from '@/lib/strings';
import { selectActiveFilterCount, useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { DateWindowFields } from './DateWindowFields';
import { useStayCheck, type StayCheckControl } from './useStayCheck';
import { GroupSizeStepper } from './GroupSizeStepper';

const ANY_VALUE = 'any';
const SITE_TYPE_OPTIONS = [
  { value: ANY_VALUE, label: filterCopy.anySiteType },
  ...FILTER_SITE_TYPES.map((option) => ({ value: option.value, label: option.label })),
];

export interface FilterPanelProps {
  open: boolean;
  onToggle: () => void;
  totalInBoundary: number;
  totalMatching: number;
  /** The campgrounds in view that can be checked online: what the check asks about. */
  checkableIds: readonly number[];
}

export function FilterPanel({ open, onToggle, totalInBoundary, totalMatching, checkableIds }: FilterPanelProps) {
  const siteType = useCampgroundFilterStore((s) => s.siteType);
  const groupSize = useCampgroundFilterStore((s) => s.groupSize);
  const amenities = useCampgroundFilterStore((s) => s.amenities);
  const dateWindow = useCampgroundFilterStore((s) => s.dateWindow);
  const activeCount = useCampgroundFilterStore(selectActiveFilterCount);
  const setSiteType = useCampgroundFilterStore((s) => s.setSiteType);
  const setGroupSize = useCampgroundFilterStore((s) => s.setGroupSize);
  const toggleAmenity = useCampgroundFilterStore((s) => s.toggleAmenity);
  const setDateWindow = useCampgroundFilterStore((s) => s.setDateWindow);
  // Here rather than under the dates, so a running check outlives the block being
  // folded away.
  const stayCheck = useStayCheck(checkableIds);

  const count = activeCount > 0 ? filterCopy.matching(totalMatching, totalInBoundary) : filterCopy.inView(totalInBoundary);

  return (
    <div className="tb-filters" id="tb-filters">
      <div className="tb-filters-pill-row">
        <Button variant="secondary" size="sm" iconStart={<Icon name="filter" aria-hidden="true" />} aria-expanded={open} aria-controls="tb-filters-body" onClick={onToggle}>
          {filterCopy.pill}
          {activeCount > 0 ? <span className="tb-filters-badge">{activeCount}</span> : null}
        </Button>
        <span className="tb-filters-hint">{filterCopy.pillHint}</span>
      </div>

      {open ? (
        <div className="tb-filters-body" id="tb-filters-body">
          <div className="tb-filters-head">
            <span className="tb-filters-heading">{filterCopy.heading}</span>
            <span className="tb-filters-count">{count}</span>
          </div>
          <div className="tb-filters-row">
            <SegmentedControl
              size="sm"
              name="site_type"
              label={filterCopy.siteType}
              options={SITE_TYPE_OPTIONS}
              value={siteType ?? ANY_VALUE}
              onChange={(next) => setSiteType(next === ANY_VALUE ? null : (next as CampsiteKind))}
            />
            <GroupSizeStepper value={groupSize} onChange={setGroupSize} />
          </div>
          <div className="tb-filters-row tb-filters-chips">
            {FILTER_AMENITIES.map((option) => (
              <Chip key={option.key} size="sm" selected={amenities.includes(option.key)} onClick={() => toggleAmenity(option.key)}>
                {option.label}
              </Chip>
            ))}
          </div>

          <DateWindowFields
            initial={dateWindow}
            onChange={setDateWindow}
            actions={
              dateWindow ? (
                <Button
                  variant={stayCheck.checked ? 'secondary' : 'primary'}
                  size="sm"
                  disabled={!stayCheck.canCheck || stayCheck.running}
                  onClick={stayCheck.check}
                >
                  {checkLabel(stayCheck)}
                </Button>
              ) : null
            }
          />
        </div>
      ) : null}
    </div>
  );
}

/** The check button's word: what pressing it does now. */
function checkLabel({ running, checked }: StayCheckControl): string {
  if (running) return stayCopy.checking;
  return checked ? stayCopy.checkAgain : stayCopy.check;
}
