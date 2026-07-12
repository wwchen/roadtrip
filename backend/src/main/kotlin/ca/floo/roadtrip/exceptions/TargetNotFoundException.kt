package ca.floo.roadtrip.exceptions

class TargetNotFoundException(
    name: String,
) : RuntimeException("unknown target: $name")
