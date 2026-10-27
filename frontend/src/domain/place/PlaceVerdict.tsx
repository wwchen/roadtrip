import { createElement } from 'react';
import { isAnswered, type StayAnswer, type StayAvailabilityStatus } from '@/api/availability-api';
import { entryOf } from '@/lib/entry-of';
import { placeCopy, stayCopy } from '@/lib/strings';
import { CHECKED_AGO_TICK_MS, useNow } from '@/lib/use-now';
import { formatWatchDate, relativeTime } from '@/lib/watch-format';
import { Row, Tag, type TagProps } from '@ui';
import { driveTimeLabel, type DriveOrigin } from './drive-origin';
import './place-verdict.css';

const CLOSED_FOR_SEASON: StayAvailabilityStatus = 'closed_for_season';

const STATUS_TONE = {
  open: 'success',
  few_left: 'warning',
  full: undefined,
  closed_for_season: undefined,
  no_data: undefined,
} satisfies Record<StayAvailabilityStatus, TagProps['status']>;

export interface PlaceVerdictProps {
  name: string;
  nameAs?: 'h2' | 'h3';
  compact?: boolean;
  answer: StayAnswer | null;
  origin: DriveOrigin | null;
  reasons?: readonly string[];
  priceFrom?: string;
  fits?: string;
  reopensOn?: string;
}

interface Verdict {
  line: string;
  tone: TagProps['status'];
  closedForSeason: boolean;
  observedAt: string | undefined;
}

function verdictOf(answer: StayAnswer): Verdict {
  if (!isAnswered(answer)) {
    return {
      line: stayCopy.errorFor(answer.outcome, answer.reason ?? null),
      tone: undefined,
      closedForSeason: false,
      observedAt: undefined,
    };
  }
  const { stay } = answer;
  const line = entryOf(stayCopy.result, stay.status);
  return {
    line: line ? line(stay.open_sites) : stayCopy.error,
    tone: entryOf(STATUS_TONE, stay.status),
    closedForSeason: stay.status === CLOSED_FOR_SEASON,
    observedAt: stay.from_cache ? stay.observed_at : undefined,
  };
}

export function PlaceVerdict({
  name,
  nameAs,
  compact = false,
  answer,
  origin,
  reasons = [],
  priceFrom,
  fits,
  reopensOn,
}: PlaceVerdictProps) {
  const verdict = answer === null ? null : verdictOf(answer);
  const observedAt = verdict?.observedAt;
  const now = useNow(CHECKED_AGO_TICK_MS, !compact && observedAt !== undefined);
  const facts = [priceFrom, fits].filter((fact): fact is string => fact !== undefined);
  const title = nameAs ? createElement(nameAs, { className: 'rt-place-verdict__name' }, name) : name;

  return (
    <div className="rt-place-verdict">
      <Row
        title={title}
        subtitle={!compact && origin ? driveTimeLabel(origin) : undefined}
        trail={
          <span className="rt-place-verdict__answer" role="status">
            <Tag status={verdict?.tone}>{verdict ? verdict.line : stayCopy.pending}</Tag>
          </span>
        }
      />
      {!compact && verdict?.closedForSeason && reopensOn ? (
        <p className="rt-place-verdict__detail">{stayCopy.reopens(formatWatchDate(reopensOn) ?? reopensOn)}</p>
      ) : null}
      {!compact && facts.length > 0 ? (
        <p className="rt-place-verdict__detail">{facts.join(placeCopy.factsSeparator)}</p>
      ) : null}
      {!compact && reasons.length > 0 ? (
        <ul className="rt-place-verdict__reasons">
          {reasons.map((reason) => (
            <li key={reason}>
              <Tag>{reason}</Tag>
            </li>
          ))}
        </ul>
      ) : null}
      {!compact && observedAt ? (
        <p className="rt-place-verdict__detail">{stayCopy.checkedAgo(relativeTime(observedAt, now))}</p>
      ) : null}
    </div>
  );
}
