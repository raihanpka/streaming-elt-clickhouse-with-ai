# Decisions

Architecture decision records, newest information last. Each record states the context, the decision, and what it costs.
A decision is changed by adding a new record, not by rewriting an old one.

## ADR 1: ClickHouse as the only datastore, ELT in the warehouse

Status: accepted, 2026-10-20.

**Context.** Bromo is a data engineering project: a streaming pipeline lands raw events in a warehouse, models them
there, and supports analytics and an AI agent on top. The datastore and transformation approach decide the footprint
and the number of moving parts.

**Decision.**

1. ClickHouse is the only datastore. Cassandra, ScyllaDB, PostgreSQL, Redis, Kafka, and PostHog export are not used.
2. Transformation is ELT inside ClickHouse with three layers: `raw`, `events`, `mart_*`.
3. There is no message broker in this version. The Java ingest API validates events and writes them straight to
   `raw` with asynchronous inserts that are acknowledged after the flush.
4. Backfill and replay go through the same ingest API as live traffic.

**Consequences.** Fewer moving parts and a footprint that fits a 12 GB, 2 CPU VPS. Duplicates are possible until merge,
and marts can over-count by that rate, so the rate is measured and published. There is no transactional store, so auth
uses static keys until a second tenant exists. Without a broker there is no durable buffer: when ClickHouse is down
ingest returns `503` and clients retry, and there is no topic replay (silver and gold are rebuilt from `raw`). If
buffering or fan-out becomes necessary, a broker can be placed behind a single write seam in the `ingest` slice.

**Rejected.** Cassandra for events plus ClickHouse for analytics (two stores for one dataset). Flink (heavy, not needed
for per-minute aggregates). A Kafka topic between ingest and ClickHouse (durable buffer and replay, but another JVM and
more operations on a 2 CPU VPS, to be revisited when ingestion must survive warehouse outages). Managed ClickHouse
Cloud (the point is self-hosting).

## ADR 2: Java backend, Python agent, gRPC between them

Status: accepted, 2026-10-20.

**Context.** The backend stays Java. The agent needs the best LLM and MCP ecosystem, which is Python (LiteLLM, the
open-source `mcp-clickhouse` server). Authentication, rate limits, and the spend cap must sit in front of the agent.

**Decision.** `bromo-app` (Java, Spring Boot) is the product backend: ingest, read API, chat gateway, auth, limits, UI
hosting. `bromo-agent` (Python) is an internal service: detector, agent loop, LLM access. They talk over gRPC with a
server-streaming `Chat` RPC. The agent is never exposed outside the compose network.

**Consequences.** A typed contract and token streaming. Costs: protobuf code generation on both sides, a gRPC
dependency in Spring Boot 4.1 (verify the integration in the milestone that needs it), and two toolchains in CI.
Fallback if gRPC costs too much: internal HTTP with Server-Sent Events.

**Rejected.** Whole backend in Python (discards the Java backend). Agent in Java with Spring AI (smaller agent
ecosystem, less provider flexibility).

## ADR 3: Vertical slice architecture in the Java backend

Status: accepted, 2026-10-20.

**Context.** The backend is a few features. Horizontal layers (`domain`, `application`, `infrastructure`, `web`) and an
extra `bromo-core` module would spread each feature over four packages and invite one interface per repository.

**Decision.** Organize `io.bromo` by feature: `ingest`, `pipeline`, `metrics`, `findings`, `chat`, plus `shared` and
`bootstrap`. A slice never imports another slice, `shared` never imports a slice, and only `bootstrap` sees all slices
(ArchUnit enforces this). Interfaces exist only for two real implementations or a process boundary (`AgentClient`). There is no `bromo-core` module.

**Consequences.** A feature change touches one package. Small duplication between slices is allowed, extract to `shared`
only when two slices need the same stable thing.

**Rejected.** Keeping hexagonal layers. One flat package.

## ADR 4: API and interface conventions

Status: accepted, 2026-10-20.

**Decision.** The conventions in [API.md](API.md#1-standards-followed) apply: RFC 9457 errors, bearer authentication
(no `X-` headers), RFC 3339 UTC timestamps, UUIDv7 ids, cursor pagination with `Link` headers, CloudEvents HTTP
binding with batch mode, SSE per the WHATWG HTML standard, and management endpoints on a separate internal port.
JSON uses `camelCase`, enums and database values use lowercase `snake_case`.

**Consequences.** Clients and tools that already speak these standards work without special cases. The UI streams chat
with `fetch` because the browser `EventSource` cannot send an `Authorization` header or use `POST`.

## Prior art and its limits

The design borrows from public engineering write-ups. Where a source does not fully apply, it is stated here.

| Source | Borrowed | Does not apply |
|---|---|---|
| [Cloudflare, HTTP analytics on ClickHouse](https://blog.cloudflare.com/http-analytics-for-6m-requests-per-second-using-clickhouse/) | Per-minute materialized views, keys matched to queries, fewer components | Their scale and replication factor |
| [Cloudflare, DNS analytics](https://blog.cloudflare.com/how-cloudflare-analyzes-1m-dns-queries-per-second/) | Batch size drives ingest performance | |
| [Uber, logging on ClickHouse](https://www.uber.com/blog/logging/) | Schema-agnostic events with typed access | Their abstraction layer |
| [Uber, QueryGPT](https://www.uber.com/blog/query-gpt) | Golden-set evaluation | Multi-agent table pruning |
| [Stripe, Billing analytics](https://stripe.dev/blog/how-we-built-it-real-time-analytics-for-stripe-billing) | One transformation logic for live and backfill, sub-300 ms dashboard queries | Stripe uses Flink and Pinot, not ClickHouse |
| [Meta, AI-assisted incident response](https://engineering.fb.com/2024/06/24/data-infrastructure/leveraging-ai-for-efficient-incident-response/) | Heuristics narrow candidates, the LLM ranks and explains | Meta's fine-tuned ranker |
| [ClickHouse, agentic analytics](https://clickhouse.com/blog/agentic-analytics-ask-ai-agent-and-remote-mcp-server-beta-launch) | Read-only SQL tools, table comments as documentation | The managed remote MCP server is Cloud only |
| [Fly.io, logging](https://fly.io/blog/fly-behind-the-scenes-fresh-logging.md) | A common schema beats per-app formats | Uses Elasticsearch, not ClickHouse |

No relevant Apple engineering source was found, and none is claimed.
