// Month calendar for jumping the visible week.
//
// The grid and its month paging are `MonthCalendar` from `@ui`; this is the
// popover around it. The parent owns the selection — the popover only reports a
// pick.
//
// Dismissal is `@/lib/use-dismiss`. Paging can detach the clicked button, so the
// inner stopPropagation guard stays on top of the hook's containment test.
import { useRef } from 'react';
import { MonthCalendar } from '@ui';
import { localYmd, parseLocalYmd } from '@/lib/local-date';
import { useDismiss } from '@/lib/use-dismiss';

export interface CalendarPopoverProps {
  /** The month to open on — the visible week's start. */
  viewMonth: Date;
  /** The earliest selectable day, and the one marked "today". */
  today: Date;
  /** Highlighted day: the visible week's start. */
  selectedDate: Date | null;
  /** The provider's booking horizon, or null when nothing has stated one. */
  maxDate: Date | null;
  onPick: (date: Date) => void;
  onClose: () => void;
}

export function CalendarPopover({
  viewMonth,
  today,
  selectedDate,
  maxDate,
  onPick,
  onClose,
}: CalendarPopoverProps) {
  const hostRef = useRef<HTMLDivElement>(null);

  useDismiss(hostRef, onClose);

  const todayIso = localYmd(today);

  return (
    <div
      className="cg-cal-host"
      ref={hostRef}
      // Every click in here came from inside the popover, so it is never an
      // outside click — stop it before the document listener sees it. Paging a
      // month unmounts the clicked button, which would defeat a contains() check.
      onClick={(event) => event.stopPropagation()}
    >
      <div className="cg-cal-popover" role="dialog" aria-label="Pick a week">
        <MonthCalendar
          initialMonth={localYmd(viewMonth)}
          today={todayIso}
          min={todayIso}
          max={maxDate ? localYmd(maxDate) : null}
          selectedStart={selectedDate ? localYmd(selectedDate) : null}
          onPick={(iso) => onPick(parseLocalYmd(iso))}
        />
      </div>
    </div>
  );
}
