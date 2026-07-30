# Observability

The dev (`tilt up`) and production Compose stacks run the same observability
pipeline alongside the backend: Grafana, Loki, Tempo, Prometheus, and Alloy.
Locally, Grafana is available at <http://127.0.0.1:3000>, Tempo at
<http://127.0.0.1:3200>, Prometheus at <http://127.0.0.1:9090>, and Alloy at
<http://127.0.0.1:12345>.

Local Compose enables anonymous editor access and provisioned dashboard UI
saves so dashboards can be adjusted in the Grafana UI; those saves live in the
local `grafana-data` volume and are not written back to
`grafana/dashboards/*.json`.

The backend container runs with the OpenTelemetry Java agent. Ktor, JDBC, and
JVM telemetry goes to Alloy over OTLP, Alloy forwards traces to Tempo and
metrics to Prometheus, and existing JSON logs still go through Docker stdout to
Loki. Trace/span IDs are injected into Logback MDC, so Grafana can correlate
logs and traces.

## Logs: Loki's three tiers

Logs use Loki's three tiers rather than storing one JSON blob per line. The
backend and companion each print a JSON envelope to stdout; Alloy
(`grafana/alloy/config.alloy`) unpacks it once at ingest into:

- **stream label** — `level` only, since labels are indexed and must stay low
  cardinality
- **structured metadata** — `logger_name`, `thread_name`, `trace_id`, `span_id`,
  `run_id`, `http_*`; filterable directly (`| trace_id="..."`) with no parser
- **log line** — the human message

So queries are `{container="roadtrip-backend-1"} | http_status="500"`, not
`| json | __error__="" | ...`. Parsing happens once on ingest instead of on every
query, and log viewers show a readable line instead of a JSON blob with the
timestamp and level repeated inside it.

Logs travel over stdout rather than OTLP deliberately. Docker's json-file driver
persists them independently of Alloy and Alloy records its read position, so if
Alloy is down Docker keeps writing and Alloy backfills on restart. The OTel
agent's log exporter has only a bounded in-memory queue, so an outage there is
silent data loss. Traces and metrics have no such local buffer available, which
is why they do use OTLP. Log rotation (`x-log-rotation` in `docker-compose.yml`)
caps disk use and therefore also bounds how much history a long Alloy outage can
recover.

## Domain metrics

Beyond the agent's auto-instrumentation, the backend emits its own domain
metrics from `ca.floo.roadtrip.observability` (`RoadtripMetrics`): per-provider
availability fetch outcomes and latency, poll run status, skipped poll cycles
(`coverage_fresh` vs `governor_starved`), watch trigger deliveries, and ETL
ingest runs. These ride the agent's existing OTLP pipeline — the process needs
no exporter config, and without the agent (plain `make run`) they are a no-op.
Row-level detail stays in Postgres (`availability_run`,
`availability_fetch_call`, `ingest_runs`) for drill-down; the metrics are the
rate/trend/alerting layer. `OtelRoadtripMetricsTest` pins the instrument names
and label keys the dashboards and alert rules query.

## Alerting

Alerting is provisioned in `grafana/provisioning/alerting/roadtrip.yml` and
routes to Slack via `GRAFANA_SLACK_WEBHOOK_URL`: collector down, backend
telemetry silent, backend/upstream 5xx, DB pool saturation, GC pressure,
availability fetches rate-limited, poll runs failing, and watch alerts not being
delivered. Prometheus scrapes Alloy, Loki, Tempo, and Grafana so the
observability stack can observe itself — the backend has no `up` series (it is
remote-written, not scraped), so its liveness rule uses
`absent_over_time(jvm_thread_count[10m])`. Grafana refuses to start on invalid
alert provisioning, so a boot failure after editing that file is a schema error
in it.

## Correlation and retention

Correlation is wired in all three directions: Loki → Tempo (derived field on
`trace_id`), Tempo → Loki (`tracesToLogsV2`), and Prometheus → Tempo
(exemplars). Tempo's `metrics_generator` produces span metrics and service
graphs into Prometheus, which is what the Service Graph and `tracesToMetrics`
views read. Log retention (Loki) and trace retention (Tempo) are both 168h since
the two are read together; metrics keep 14d (`PROMETHEUS_RETENTION`).

## Dashboards

Provisioned dashboards include a catalog explorer
(`/d/roadtrip-catalog-explorer/roadtrip-catalog-explorer`) that covers POIs,
campsites, and snapshot-backed availability, plus status overview, POI detail,
Campground detail, Tesla Supercharger detail/stats, Campsite detail/stats, DB
stats, Roadtrip Metrics (`/d/roadtrip-metrics/roadtrip-metrics`),
watch/scheduler health, and API/SQL equivalence.

To edit a dashboard, use the Grafana UI (UI saves are enabled locally), then
snapshot the result back into the repo with `make grafana-export` before
committing.
