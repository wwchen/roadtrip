const RECGOV_VENDOR = 'recgov'

export function cartMatchFromPayload (payload) {
  const openings = Array.isArray(payload.openings) ? payload.openings : []
  const opening = openings[0] || payload
  const dates = [...new Set(openings.map((o) => o.date).filter(Boolean))]
  const firstDate = opening.date || opening.first_date || payload.start_date
  return {
    vendor: opening.vendor || payload.vendor,
    booking_url: opening.booking_url || payload.booking_url,
    campground_id: opening.campground_id || payload.campground_id,
    campsite_id: opening.campsite_id || payload.campsite_id,
    provider_campground_id: opening.provider_campground_id || payload.provider_campground_id,
    provider_campsite_id: opening.vendor_id || opening.provider_campsite_id || payload.provider_campsite_id,
    first_date: firstDate,
    checkout_date: opening.checkout_date || payload.end_date,
    available_dates: dates.length ? dates : (firstDate ? [firstDate] : []),
    campsite_site: opening.label || '',
  }
}

export function cartMatchFromAtcInput (input) {
  if (input?.payload) return cartMatchFromPayload(input.payload)
  if (input?.openings || input?.start_date || input?.end_date) return cartMatchFromPayload(input)
  return normalizeCartMatch(input || {})
}

export function cartMatchFromArgs (args) {
  const firstDate = stringValue(args['start-date']) || stringValue(args.date) || stringValue(args.first_date)
  return normalizeCartMatch({
    booking_url: stringValue(args['booking-url']) || stringValue(args.booking_url),
    campground_id: stringValue(args['campground-id']) || stringValue(args.campground_id),
    campsite_id: stringValue(args['campsite-id']) || stringValue(args.campsite_id),
    provider_campsite_id: stringValue(args['provider-campsite-id']) || stringValue(args.provider_campsite_id) || stringValue(args.vendor_id),
    first_date: firstDate,
    checkout_date: stringValue(args['end-date']) || stringValue(args['checkout-date']) || stringValue(args.checkout_date),
    available_dates: csv(args['available-dates'] || args.available_dates) || (firstDate ? [firstDate] : []),
    campsite_site: stringValue(args.site) || stringValue(args.label) || stringValue(args.campsite_site) || '',
  })
}

export function validateCartMatch (match) {
  if (match.vendor && match.vendor !== RECGOV_VENDOR) return `unsupported vendor: ${match.vendor}`
  if (!match.first_date) return 'missing first_date/start-date'
  if (!match.checkout_date) return 'missing checkout_date/end-date'
  if (!match.booking_url && !match.campground_id && !match.provider_campsite_id && !match.campsite_id) {
    return 'missing booking_url or campsite/campground identifier'
  }
  return null
}

function normalizeCartMatch (match) {
  const firstDate = match.first_date || match.start_date
  return {
    vendor: match.vendor,
    booking_url: match.booking_url,
    campground_id: match.campground_id,
    campsite_id: match.campsite_id,
    provider_campground_id: match.provider_campground_id,
    provider_campsite_id: match.provider_campsite_id || match.vendor_id,
    first_date: firstDate,
    checkout_date: match.checkout_date || match.end_date,
    available_dates: match.available_dates || (firstDate ? [firstDate] : []),
    campsite_site: match.campsite_site || match.label || match.site || '',
  }
}

function csv (value) {
  if (!value) return null
  if (Array.isArray(value)) return value
  return String(value).split(',').map((item) => item.trim()).filter(Boolean)
}

function stringValue (value) {
  return value === true ? undefined : value
}
