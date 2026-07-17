package ca.floo.roadtrip.service.notification.slack

import ca.floo.roadtrip.clients.slack.SlackAttachmentDto
import ca.floo.roadtrip.clients.slack.SlackBlocks

private const val TEST_SLACK_FALLBACK = "Roadtrip test Slack message"

internal object SlackContentTestRenderer {
    fun render(): Pair<String, List<SlackAttachmentDto>> =
        TEST_SLACK_FALLBACK to
            listOf(
                SlackAttachmentDto(
                    color = SlackWatchCard.COLOR_WATCHING,
                    fallback = TEST_SLACK_FALLBACK,
                    blocks =
                        listOf(
                            SlackBlocks.header("Roadtrip test Slack"),
                            SlackBlocks.section("Slack alerts are configured."),
                        ),
                ),
            )
}
