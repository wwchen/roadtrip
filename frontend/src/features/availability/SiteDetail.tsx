// The expanded row under a selected site in the matrix.
//
// No `dangerouslySetInnerHTML` anywhere, unlike the campground drawer's About
// section: every field here is text off the typed catalog row, so this component
// has no sanitiser to get wrong.
import type { Campsite } from '@/api/campsite-api';
import { siteName } from './matrix-rows';
import { descriptionText, detailFacts, featureLabels } from './site-detail-facts';
import {
  bookingLabel,
  reservationUrlFromTemplate,
  type ReservationUrlTemplates,
} from './booking-links';

export interface SiteDetailProps {
  site: Partial<Campsite>;
  /** Set only when a day is selected, which is what makes a booking link possible. */
  selectedDate?: string | null;
  selectedEndDate?: string | null;
  reservationUrlTemplates: ReservationUrlTemplates;
}

export function SiteDetail({
  site,
  selectedDate = null,
  selectedEndDate = null,
  reservationUrlTemplates,
}: SiteDetailProps) {
  const name = siteName(site);
  const imageUrl = site.photo_url ?? '';
  const description = descriptionText(site.description);
  const facts = detailFacts(site);
  const features = featureLabels(site);
  const url = reservationUrlFromTemplate(site, {
    startDate: selectedDate,
    endDate: selectedEndDate,
    reservationUrlTemplates,
  });

  return (
    <section className="cg-site-detail" aria-label="Site details">
      <div className="cg-site-detail-head">
        <div className="cg-site-detail-title-wrap">
          <div className="cg-site-detail-title" title={name}>
            {name}
          </div>
          {selectedDate ? <div className="cg-site-detail-subtitle">{selectedDate}</div> : null}
        </div>
      </div>

      {imageUrl ? (
        <div className="cg-site-detail-media">
          <img src={imageUrl} alt={name} />
        </div>
      ) : null}

      {description ? <p className="cg-site-detail-description">{description}</p> : null}

      {facts.length > 0 ? (
        <div className="cg-site-detail-facts">
          {facts.map((fact) => (
            <div className="cg-site-detail-fact" key={fact.label}>
              <span>{fact.label}</span>
              <strong>{fact.value}</strong>
            </div>
          ))}
        </div>
      ) : null}

      {features.length > 0 ? (
        <div className="cg-site-detail-features">
          {features.map((feature) => (
            <span className="cg-site-detail-feature" key={feature}>
              {feature}
            </span>
          ))}
        </div>
      ) : null}

      {url ? (
        <a className="cg-site-detail-book" href={url} target="_blank" rel="noreferrer">
          {bookingLabel(site)}
        </a>
      ) : null}
    </section>
  );
}
