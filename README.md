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

Both directions run through the same pipeline, so both get the same guarantees. Usually this
takes two separate products.

### Inbound — protecting the main app from its callers

A request from an external client is authorised, size-checked, queued and only then delivered.

- **Auth gate.** A pluggable `AuthorizationProvider` sits in front, so the main app gains a
  gateway without changing its own authentication.
- **Size limit enforced on `Content-Length`, before buffering.** A 21 MB body is rejected with
  `413` after ~1.7 MB has been read — not after the whole thing is in memory.
- **`202` means *durably queued*, not *we'll try*.** The response is returned only after Kafka has
  acknowledged the record. If the queue is unreachable the client gets an error and can re-send —
  nothing disappears silently.
- **The client never holds a connection open.** It receives a polling URL, or supplies
  `X-Callback-Url` and gets called back when the result is ready.
- **A burst becomes a queue, not an outage.** The main app consumes at its own rate; if it is
  down or restarting, requests wait and are delivered on recovery.
- **File uploads survive storage failure.** Multipart uploads go to S3-compatible storage with
  SHA-256 de-duplication and three attempts; if that fails, the files are forwarded to the
  application instead, so the flow does not stop.

### Outbound — protecting delivery to third parties

The main app does not call external APIs itself. It records the request; the gateway picks it up
and owns delivery.

- **Bounded retry with a per-host circuit breaker**, so one failing upstream cannot cascade.
- **A per-host concurrency limit**, so our own burst does not trip that circuit in the first place.
- **OAuth2 per request** — the provider is resolved per request, tokens are cached in Redis and
  refreshed ahead of expiry; a `401` evicts the cached token and retries once without spending a
  retry attempt.
- **Responses are archived for 7 days** and retrievable by request id, then written back to the
  source system with bounded retries.
- **Anything undeliverable lands in a dead-letter queue with an alert** — never a silent drop.

### Why one product and not two

An API gateway (Kong, Apigee) handles inbound traffic but synchronously — if the application
behind it is down, the request is lost. An integration platform (Confluent, MuleSoft) handles
asynchronous outbound delivery but is not an HTTP front door. Both directions are the same
problem — *deliver this request reliably* — with the arrow pointing a different way, so QosID Flow
solves them with one pipeline.

### Why not a workflow engine (n8n, Airflow)?

Mostly you can build this in one. That is the honest answer, and it is worth being precise about
what is and is not already there:

- **Per-destination concurrency limits** — Airflow has this natively (Pools). Genuine strength.
- **Circuit breaker per host** — neither has it; you write it in a function node or custom
  operator plus external state.
- **OAuth2 token lifecycle** — both have credential systems that will do.
- **A record of every outbound call** — n8n keeps execution history, Airflow logs; retrieving one
  by *business* request id is your own work.
- **Exactly-once external effect** — neither gives it to you. A retry that re-runs a node can
  charge a card twice unless you build the idempotency key yourself.

So the difference is not capability. It is three other things:

**Shape.** Airflow is a batch scheduler whose unit is a DAG run; delivering tens of thousands of
individual low-latency business requests a day is not its shape. n8n is closer — event-driven,
queue mode — but its unit is a workflow execution, and at that volume the execution store becomes
the problem you manage.

**Assembled versus assemblable.** Everything above *can* be built. Then you own it: the breaker,
the idempotency key, the contiguous-prefix offset commit, the bounded parked queue, the alert
coalescing. You will find the bugs in production — as we did on 27 July. This is the same argument
as using a database instead of writing a B-tree.

**The inbound direction.** Neither is a transparent HTTP front door for your main application: the
client's method, path and headers forwarded unchanged, a `202` with a polling URL returned
immediately, and a burst absorbed as a queue. A webhook trigger is a different thing.

**Where a workflow engine is the better answer:** many different SaaS systems to connect, modest
volume, logic that changes often. QosID Flow is for the narrow case — one core system, high
volume, requests that carry money or a legal obligation, where neither loss nor duplication is
acceptable.

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

## Repositories

This is the **working repository** — private, and the only place development happens.

A **curated public snapshot** is published at
[github.com/Mukhammadaliya/qosid-flow](https://github.com/Mukhammadaliya/qosid-flow) so the system
can be read and evaluated. It carries no git history and omits internal planning documents,
operational runbooks and the security-remediation record; upstream host names are replaced with
placeholders there. No license is granted on it — all rights reserved.

When a change improves the codebase, it belongs in both. When a change is only about redacting
this repository's contents, it belongs only in the snapshot.

## License

Proprietary; all rights reserved. An open-source license for the core is planned as part of the
open-core direction described above.
