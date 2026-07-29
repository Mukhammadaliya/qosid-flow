# QosID Flow

**Kafka-based integration middleware that stands in front of your main application — in both
directions.**

Java 21 · Spring Boot 3.5 · Apache Kafka · Redis · RocksDB
Version 2.3.1 · in production since April 2026

---

## The problem

Enterprise integrations are usually hand-written scripts. When the external API is slow, the
script blocks. When it fails, the error is swallowed. When it comes back, nobody re-sends what was
lost. There is no retry policy, no visibility, and no back-pressure — so one sick upstream takes
the whole system down with it.

## What QosID Flow does

It sits between your main application and everything it talks to, and it makes every request
survivable.

```
INBOUND    external clients ──▶ QosID Flow ──▶ main app
                                queue · retry · back-pressure · auth

OUTBOUND   main app ──────────▶ QosID Flow ──▶ external APIs
                                retry · circuit breaker · OAuth2
```

Both directions run through the same pipeline, so both get the same guarantees.

**Inbound** protects the main app from traffic it cannot absorb. Requests are queued in Kafka
instead of hitting the app directly; a burst becomes a queue, not an outage. The client gets an
immediate `202` with a polling URL rather than holding a connection open. If the main app is down,
nothing is lost — the requests wait and are delivered when it recovers.

**Outbound** protects delivery to third parties. Retries with a bounded policy, a per-host circuit
breaker, OAuth2 token management with caching and refresh, and a dead-letter queue with alerting
for anything that genuinely cannot be delivered.

---

## Production evidence

This is not a prototype. The numbers below are measured, not estimated.

| | |
|---|---|
| Throughput | **~50 700 requests/day** |
| Success rate | **99.994 %** |
| Peak burst handled | **~330 requests/second**, zero loss |
| Automated tests | **379** |
| Documented safety rules | **41**, each pinned by a test |
| Codebase | 13 082 lines of Java across 158 files |

---

## What "no message is lost" actually means

Every claim here corresponds to code and to a test.

**Manual acknowledgement, ordered.** Kafka offsets are never auto-committed. A record is
acknowledged only after its work is finished, and commits are routed through an ordered-ack
tracker that only advances the contiguous finished prefix — so a crash re-delivers, it never
skips.

**Idempotency at the door.** Every consumer checks completion state and takes a distributed lock
before doing anything, so a re-delivery is absorbed rather than duplicated.

**Back-pressure instead of failure.** When a circuit opens, the message is *parked in memory* and
re-checked on a doubling delay until the host accepts traffic again — it is not spun through
Kafka, and it is not failed. Give-up is by time, not by a retry count.

**Bounded by design.** The parked set is bounded in both message count and bytes. A per-host
concurrency limit stops our own burst from overwhelming an upstream in the first place.

**Nothing disappears quietly.** Anything that truly cannot be delivered lands in a dead-letter
queue with an alert. Response bodies are archived for 7 days and retrievable by request id.

---

## An incident, and what came out of it

On 27 July 2026 a single 30-second circuit-breaker window pushed **212 live production requests**
into the dead-letter queue. The cause was subtle: blocked messages were re-queued immediately, so
a retry budget meant as "wait out the outage" was spent in about one second. The 212 alerts that
followed hit Telegram's rate limit — 14 were delivered and 298 were rejected, so the alert channel
failed exactly when it was needed.

Three changes followed, each measured rather than assumed:

1. **Park instead of spin** — blocked messages wait in memory and re-check the circuit; give-up is
   by elapsed time against the message deadline.
2. **Per-host concurrency limit** — our own burst can no longer open the circuit.
3. **Alert coalescing** — one incident produces one message, and a `429` is obeyed rather than
   retried.

The day after, a production burst **five times denser** than the one that caused the incident
(~330 req/s) drained with the circuit never opening, zero requests lost and zero dead-letter
records. Twenty unrelated failures during that window produced **two** Telegram messages instead
of twenty.

The engineering rules that came out of this are written down in
[`ai-context/SAFETY-RULES.md`](ai-context/SAFETY-RULES.md) — rules 23, 40 and 41.

---

## Architecture

The pipeline has five stages, and both traffic directions share them:

| Stage | What happens |
|---|---|
| **E1 — Intake** | Pull from the source system, or accept an inbound REST request |
| **E2 — Queue** | Produce to a per-flow Kafka topic |
| **E3 — Consume** | Idempotency check, distributed lock, async dispatch |
| **E4 — Deliver** | HTTP call with per-host concurrency limit, circuit breaker and OAuth2 |
| **E5 — Settle** | Save the response, archive the body, fire the callback |

The source-system side is pluggable: the contract is specified in
[`ai-context/SOURCE-INTEGRATION-CONTRACT.md`](ai-context/SOURCE-INTEGRATION-CONTRACT.md), so
connecting a different back-end system is an implementation of that contract, not a fork.

The two traffic flows have **separate consumers, topics and thread pools on purpose** — one sick
upstream cannot stall the other flow.

---

## What is in the repository

```
settings-core/   Shared settings module — registry, AES secret storage, repositories
app/             The gateway itself (port 8090, management 8091)
ops/             Operations sidecar (port 8092) — start/stop, live log stream
frontend/        React admin console: statistics, errors, settings, ops
infrastructure/  Prometheus scrape config, alert rules, Grafana dashboards
ai-context/      Architecture, configuration, conventions, safety rules
deploy/          Reference nginx configuration
```

The admin console covers live statistics, a log-search module over Loki, opt-in AI error analysis,
and an operations page that can start and stop the gateway and stream its log.

---

## Running it

```bash
docker compose up -d          # Kafka, Redis, Prometheus, Grafana, Loki
mvn clean package -DskipTests # builds all three modules
mvn spring-boot:run -pl app
```

Configuration reference: [`ai-context/CONFIGURATION.md`](ai-context/CONFIGURATION.md).
Every setting has an environment-variable override; see [`.env.example`](.env.example).

---

## Documentation

The `ai-context/` directory is the real documentation, and it is kept in sync with the code as a
project rule:

| File | Contents |
|---|---|
| [OVERVIEW.md](ai-context/OVERVIEW.md) | Identity, stack, ports, repository layout |
| [ARCHITECTURE.md](ai-context/ARCHITECTURE.md) | Pipeline stages, components, data flow |
| [CONFIGURATION.md](ai-context/CONFIGURATION.md) | Every parameter, default and env var |
| [SAFETY-RULES.md](ai-context/SAFETY-RULES.md) | 41 rules that must not be broken, and why |
| [CONVENTIONS.md](ai-context/CONVENTIONS.md) | Code style, DI, logging, metrics, anti-patterns |
| [EXTENSION-GUIDE.md](ai-context/EXTENSION-GUIDE.md) | Recipes: new consumer, OAuth2 provider, metric |
| [DOMAIN-GLOSSARY.md](ai-context/DOMAIN-GLOSSARY.md) | Terms used throughout the codebase |
| [docs/REFERENCE.md](docs/REFERENCE.md) | Full technical reference and troubleshooting guide |

---

## Status and direction

Running in production against a live enterprise workload since April 2026. Two areas are
deliberately still open and are infrastructure rather than application work: broker replication
and a highly-available Redis deployment.

The intended direction is **open-core** — the pipeline and the connector contract open, the
enterprise layer (console, AI analysis, SSO/RBAC, audit) commercial — alongside a managed cloud
offering. That split has not been made yet.

## License

No license is granted at this time; all rights reserved. The repository is public so that the
system can be read and evaluated. An open-source license for the core is planned as part of the
open-core release described above.
