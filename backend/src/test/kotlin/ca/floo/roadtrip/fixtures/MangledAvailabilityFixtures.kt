package ca.floo.roadtrip.fixtures

private const val MANGLED_FIXTURE_DIR = "availability-fixtures/mangled"

internal fun mangledAvailabilityFixture(name: String): String =
    checkNotNull(Thread.currentThread().contextClassLoader.getResource("$MANGLED_FIXTURE_DIR/$name")) {
        "missing fixture $MANGLED_FIXTURE_DIR/$name"
    }.readText()
