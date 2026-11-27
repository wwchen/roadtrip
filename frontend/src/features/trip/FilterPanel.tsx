// The filter block under the search row: the pill with the count, the agency
// pick, the catalog filters, the date window, and the availability check over
// it. Filter state lives in the store, and every control writes it as it
// changes — the dates included, once both ends are picked; only "is the block
// open" is local. The check is the one thing that waits for a click, because it
// is the one that asks the providers.
import { Button, Chip, Icon, LinkButton, MultiSelect, SegmentedControl } from '@ui';
import type { CampsiteKind } from '@/api/generated/api-types';
import { FILTER_AMENITIES, FILTER_SITE_TYPES } from '@/lib/campground-vocab';
import { filterCopy, stayCopy } from '@/lib/strings';
import { agenciesByCount } from '@/map/agencies';
import { selectActiveFilterCount, useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { DateWindowFields } from './DateWindowFields';
import { useStayCheck, type StayCheckControl } from './useStayCheck';
import { GroupSizeStepper } from './GroupSizeStepper';

const ANY_VALUE = 'any';
const AGENCY_FIELD_ID = 'tb-agency';
const AMENITIES_LABEL_ID = 'tb-amenities-label';
const SITE_TYPE_OPTIONS = [
  { value: ANY_VALUE, label: filterCopy.anySiteType },
  ...FILTER_SITE_TYPES.map((option) => ({ value: option.value, label: option.label })),
];

export interface FilterPanelProps {
  open: boolean;
  onToggle: () => void;
  totalInBoundary: number;
  totalMatching: number;
  agencyCounts: ReadonlyMap<string, number>;
  /** The campgrounds in view that can be checked online, or null while the list is not ready. */
  checkableIds: readonly number[] | null;
}

export function FilterPanel({ open, onToggle, totalInBoundary, totalMatching, agencyCounts, checkableIds }: FilterPanelProps) {
  const siteType = useCampgroundFilterStore((s) => s.siteType);
  const groupSize = useCampgroundFilterStore((s) => s.groupSize);
  const amenities = useCampgroundFilterStore((s) => s.amenities);
  const agencies = useCampgroundFilterStore((s) => s.agencies);
  const dateWindow = useCampgroundFilterStore((s) => s.dateWindow);
  const activeCount = useCampgroundFilterStore(selectActiveFilterCount);
  const setSiteType = useCampgroundFilterStore((s) => s.setSiteType);
  const setGroupSize = useCampgroundFilterStore((s) => s.setGroupSize);
  const toggleAmenity = useCampgroundFilterStore((s) => s.toggleAmenity);
  const toggleAgency = useCampgroundFilterStore((s) => s.toggleAgency);
  const clearAgencies = useCampgroundFilterStore((s) => s.clearAgencies);
  const clearFilters = useCampgroundFilterStore((s) => s.clearFilters);
  const setDateWindow = useCampgroundFilterStore((s) => s.setDateWindow);
  // Here rather than under the dates, so a running check outlives the block being
  // folded away.
  const stayCheck = useStayCheck(checkableIds);

  const count = activeCount > 0 ? filterCopy.matching(totalMatching, totalInBoundary) : filterCopy.inView(totalInBoundary);
  const agencyRows = agenciesByCount(agencyCounts, agencies);
  const firstPicked = agencyRows.find((row) => agencies.includes(row.agency))?.agency;
  const agencyText = firstPicked ? filterCopy.agencyPick(firstPicked, agencies.length) : filterCopy.allAgencies;

  return (
    <div className="tb-filters" id="tb-filters">
      <div className="tb-filters-pill-row">
        <Button
          variant="secondary"
          size="sm"
          iconStart={<Icon name="filter" aria-hidden="true" />}
          aria-label={activeCount > 0 ? filterCopy.pillActive(activeCount) : filterCopy.pill}
          aria-expanded={open}
          aria-controls="tb-filters-body"
          onClick={onToggle}
        >
          {filterCopy.pill}
          {activeCount > 0 ? (
            <span className="tb-filters-badge" aria-hidden="true">
              {activeCount}
            </span>
          ) : null}
        </Button>
        <span className="tb-filters-count">{count}</span>
        {activeCount > 0 ? (
          <LinkButton className="tb-filters-clear" aria-label={filterCopy.clearFilters} onClick={clearFilters}>
            {filterCopy.clear}
          </LinkButton>
        ) : null}
      </div>

      {open ? (
        <div className="tb-filters-body" id="tb-filters-body">
          <MultiSelect
            id={AGENCY_FIELD_ID}
            label={filterCopy.agency}
            options={agencyRows.map((row) => ({ value: row.agency, label: row.agency, count: row.count }))}
            selected={agencies}
            onToggle={toggleAgency}
            onClear={clearAgencies}
            allLabel={filterCopy.allAgencies}
            triggerText={agencyText}
            searchLabel={filterCopy.findAgency}
            noMatchText={filterCopy.noAgencyMatch}
            emptyText={filterCopy.noAgenciesInView}
          />
          <div className="tb-filters-grid">
            <div className="rt-field">
              <span className="rt-field__label" aria-hidden="true">
                {filterCopy.siteType}
              </span>
              <SegmentedControl
                size="sm"
                full
                name="site_type"
                label={filterCopy.siteType}
                options={SITE_TYPE_OPTIONS}
                value={siteType ?? ANY_VALUE}
                onChange={(next) => setSiteType(next === ANY_VALUE ? null : (next as CampsiteKind))}
              />
            </div>
            <div className="rt-field">
              <span className="rt-field__label" aria-hidden="true">
                {filterCopy.groupSize}
              </span>
              <GroupSizeStepper value={groupSize} onChange={setGroupSize} />
            </div>
          </div>
          <div className="rt-field" role="group" aria-labelledby={AMENITIES_LABEL_ID}>
            <span className="rt-field__label" id={AMENITIES_LABEL_ID}>
              {filterCopy.amenities}
            </span>
            <div className="tb-filters-row tb-filters-chips">
              {FILTER_AMENITIES.map((option) => (
                <Chip key={option.key} size="sm" selected={amenities.includes(option.key)} onClick={() => toggleAmenity(option.key)}>
                  {option.label}
                </Chip>
              ))}
            </div>
          </div>
          <div className="tb-filters-divider" role="separator" />

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
          {/* Beside the button it explains, so a folded results list cannot hide why it is off;
              with no dates there is no button, and nothing to explain. */}
          {dateWindow ? <CheckNote control={stayCheck} /> : null}
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

/**
 * Why the check button is off, or why the last check stopped — red when a check
 * stopped or must wait, muted when there is simply nothing to ask about.
 */
function CheckNote({ control: { running, coolingDown, failure, failureDetail, nothingToCheck } }: { control: StayCheckControl }) {
  if (running) return null;
  const withDetail = (sentence: string) => (failureDetail ? `${sentence} (${failureDetail})` : sentence);
  if (coolingDown || failure === 'throttled') return <span className="tb-check-note tb-check-note--error" role="status">{withDetail(stayCopy.throttled)}</span>;
  if (failure === 'timed_out') return <span className="tb-check-note tb-check-note--error" role="status">{withDetail(stayCopy.timedOut)}</span>;
  if (failure === 'failed') return <span className="tb-check-note tb-check-note--error" role="status">{withDetail(stayCopy.failed)}</span>;
  if (nothingToCheck) return <span className="tb-check-note" role="status">{stayCopy.noneCheckable}</span>;
  return null;
}
