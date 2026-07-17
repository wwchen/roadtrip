package ca.floo.roadtrip

import ca.floo.roadtrip.clients.slack.SlackSignatureVerifier
import ca.floo.roadtrip.service.notification.slack.SlackInteractivityHandler

/** Pair of the signature verifier + the handler; wired only when the Slack
 *  app is configured with a signing secret. Null on a Slack-disabled install
 *  so the interactivity endpoint is absent (404) rather than answering 401. */
internal data class SlackInteractivityWiring(
    val verifier: SlackSignatureVerifier,
    val handler: SlackInteractivityHandler,
)
