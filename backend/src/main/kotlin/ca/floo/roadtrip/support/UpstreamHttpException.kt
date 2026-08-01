package ca.floo.roadtrip.support

/**
 * Provider-neutral contract for a vendor exception that carries the upstream
 * HTTP status it failed on (or `null` when the exchange never got a status —
 * transport failure, parse failure after a 200, missing config).
 *
 * The route layer walks a failed request's cause chain looking for this
 * status so it can surface `upstream_status` in the error envelope. Every
 * vendor wrapper implements it, so a Campflare/ReserveAmerica/ReserveCalifornia
 * 5xx retains the same diagnostic evidence an Aspira 5xx does — matching the
 * runbook instead of dropping the field for three of four providers.
 */
interface UpstreamHttpException {
    val httpStatus: Int?
}
