package ca.floo.roadtrip.service.etl.framework

class TargetNotFoundException(
    name: String,
) : RuntimeException("unknown target: $name")
