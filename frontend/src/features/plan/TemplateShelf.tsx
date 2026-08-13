// The shelf: one card per trip template, each opening its timeline.
import { Button, Card, Tag } from '@ui';
import type { TripTemplate } from '@/api/planning-api';
import {
  formatBudgetTotal,
  formatPrimeMonths,
  gradeHue,
  LIST_SEPARATOR,
} from './plan-format';

export interface TemplateShelfProps {
  templates: TripTemplate[];
  onSelect: (template: TripTemplate) => void;
}

export function TemplateShelf({ templates, onSelect }: TemplateShelfProps) {
  return (
    <div className="rt-plan-shelf">
      {templates.map((template) => (
        <Card
          key={template.id}
          kicker={template.tagline}
          title={template.name}
          body={
            <>
              <div>
                {template.origin} → {template.terminus}
              </div>
              <div>
                {[
                  `${template.days} days`,
                  `${template.totalMiles} mi total`,
                ].join(LIST_SEPARATOR)}
              </div>
              <div>
                {[
                  `Avg drive ${template.avgDriveMinutesPerDay} min/day`,
                  `longest ${template.longestDriveMinutes} min`,
                ].join(LIST_SEPARATOR)}
              </div>
            </>
          }
          meta={
            <span className="rt-plan-tags">
              <Tag hue={gradeHue(template.evGrade)}>EV: {template.evGrade}</Tag>
              <Tag hue={gradeHue(template.bookingGrade)}>
                Booking: {template.bookingGrade}
              </Tag>
              <Tag>Season: {formatPrimeMonths(template.seasonPrimeMonths)}</Tag>
              <span>{formatBudgetTotal(template.budget.totalUsd)}</span>
            </span>
          }
          actions={
            <Button variant="secondary" onClick={() => onSelect(template)}>
              View timeline
            </Button>
          }
        />
      ))}
    </div>
  );
}
