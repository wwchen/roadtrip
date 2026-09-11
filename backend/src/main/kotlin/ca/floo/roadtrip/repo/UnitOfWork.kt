package ca.floo.roadtrip.repo

/**
 * A unit of work over the catalog. [run] hands [block] repo handles that all
 * share one connection context and commits when it returns normally.
 *
 * Three outcomes, and no fourth:
 * - the block returns: its writes commit;
 * - the block throws a [RuntimeException] or an [Error]: the caller catches
 *   that exact throwable, and the block's writes roll back;
 * - the block throws a checked exception: the caller catches that exact
 *   throwable too, and the block's writes roll back.
 *
 * [run] is not reentrant: a nested [run] throws. It would take a second pooled
 * connection and an independent transaction rather than a savepoint, so a
 * service already inside a block takes repo handles from that block's [Repos],
 * never a [UnitOfWork] of its own.
 *
 * [run] is blocking and its block cannot suspend by construction; call it from
 * an IO dispatcher.
 *
 * No jOOQ type appears here on purpose: a service takes this port, never a
 * `DSLContext`, so "which connection am I on" stops being a service concern.
 */
interface UnitOfWork {
    fun <T> run(block: (Repos) -> T): T
}
