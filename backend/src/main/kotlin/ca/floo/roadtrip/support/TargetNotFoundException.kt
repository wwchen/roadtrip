package ca.floo.roadtrip.support

class TargetNotFoundException(
    name: String,
) : RuntimeException("unknown target: $name")
