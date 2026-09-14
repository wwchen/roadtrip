// The filter block under the search row: the pill, the three local filters, and
// the one gated step (dates). Filter state lives in the store; only "is the block
// open" and "are the date fields showing" are local.
import { useState } from 'react';
import { Banner, Button, Chip, Icon, SegmentedControl } from '@ui';
import type { CampsiteKind } from '@/api/generated/api-types';
import { FILTER_AMENITIES, FILTER_SITE_TYPES } from '@/lib/campground-vocab';
import { filterCopy } from '@/lib/strings';
import { selectActiveFilterCount, useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { DateWindowFields, formatDateShort, nightsBetween } from './DateWindowFields';
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
}

export function FilterPanel({ open, onToggle, totalInBoundary, totalMatching }: FilterPanelProps) {
  const siteType = useCampgroundFilterStore((s) => s.siteType);
  const groupSize = useCampgroundFilterStore((s) => s.groupSize);
  const amenities = useCampgroundFilterStore((s) => s.amenities);
  const dateWindow = useCampgroundFilterStore((s) => s.dateWindow);
  const activeCount = useCampgroundFilterStore(selectActiveFilterCount);
  const setSiteType = useCampgroundFilterStore((s) => s.setSiteType);
  const setGroupSize = useCampgroundFilterStore((s) => s.setGroupSize);
  const toggleAmenity = useCampgroundFilterStore((s) => s.toggleAmenity);
  const setDateWindow = useCampgroundFilterStore((s) => s.setDateWindow);
  const [editingDates, setEditingDates] = useState(false);

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

          {editingDates ? (
            <DateWindowFields
              initial={dateWindow}
              onSave={(next) => {
                setDateWindow(next);
                setEditingDates(false);
              }}
              onCancel={() => setEditingDates(false)}
            />
          ) : dateWindow ? (
            <div className="tb-dates-summary">
              <Icon name="calendar" aria-hidden="true" />
              <span>{filterCopy.dateSummary(formatDateShort(dateWindow.start), formatDateShort(dateWindow.end), nightsBetween(dateWindow.start, dateWindow.end))}</span>
              <Button variant="tertiary" size="sm" onClick={() => setEditingDates(true)}>
                {filterCopy.editDates}
              </Button>
              <Button variant="tertiary" size="sm" onClick={() => setDateWindow(null)}>
                {filterCopy.clearDates}
              </Button>
            </div>
          ) : (
            <Banner
              icon={<Icon name="calendar" aria-hidden="true" />}
              title={filterCopy.checkTitle}
              actions={
                <Button variant="primary" size="sm" onClick={() => setEditingDates(true)}>
                  {filterCopy.addDates}
                </Button>
              }
            >
              {filterCopy.checkBody}
            </Banner>
          )}
        </div>
      ) : null}
    </div>
  );
}
