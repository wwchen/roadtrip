package ca.floo.campsite.recgov.booker.db

import ca.floo.roadtrip.db.generated.tables.references.SETTINGS
import org.jooq.DSLContext

// Open so tests can extend with an in-memory fake without spinning up Postgres.
// SettingsRoutes / TokenManager / SlackNotifier all only touch get / all / set /
// setMany; nothing here is performance-critical, so the virtual dispatch is fine.
open class SettingsRepo(
    private val ctx: DSLContext,
) {
    open fun get(key: String): String? =
        ctx
            .select(SETTINGS.VALUE)
            .from(SETTINGS)
            .where(SETTINGS.KEY.eq(key))
            .fetchOne()
            ?.value1()

    open fun all(): Map<String, String> =
        ctx
            .selectFrom(SETTINGS)
            .fetchMap(SETTINGS.KEY, SETTINGS.VALUE)
            .filterKeys { it != null }
            .mapKeys { it.key!! }
            .mapValues { it.value ?: "" }

    open fun set(
        key: String,
        value: String,
    ) {
        ctx
            .insertInto(SETTINGS, SETTINGS.KEY, SETTINGS.VALUE)
            .values(key, value)
            .onConflict(SETTINGS.KEY)
            .doUpdate()
            .set(SETTINGS.VALUE, value)
            .execute()
    }

    open fun setMany(updates: Map<String, String>) {
        ctx.transaction { trx ->
            val tx = trx.dsl()
            for ((k, v) in updates) {
                tx
                    .insertInto(SETTINGS, SETTINGS.KEY, SETTINGS.VALUE)
                    .values(k, v)
                    .onConflict(SETTINGS.KEY)
                    .doUpdate()
                    .set(SETTINGS.VALUE, v)
                    .execute()
            }
        }
    }

    fun seedDefaults(env: Map<String, String?>) {
        val defaults =
            mapOf(
                "poll_interval" to (env["POLL_INTERVAL"] ?: "60"),
                "slack_token" to (env["SLACK_TOKEN"] ?: ""),
                "slack_channel" to (env["SLACK_CHANNEL"] ?: ""),
            )
        for ((k, v) in defaults) {
            if (get(k) == null) set(k, v)
        }
    }
}
