package ca.floo.roadtrip.repo

/**
 * A unit of work over the catalog. [run] hands [block] repo handles that all
 * share one connection context and commits when it returns normally; anything
 * thrown rolls the whole block back.
 *
 * No jOOQ type appears here on purpose: a service takes this port, never a
 * `DSLContext`, so "which connection am I on" stops being a service concern.
 */
interface UnitOfWork {
    fun <T> run(block: (Repos) -> T): T
}
