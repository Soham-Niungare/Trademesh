# Architecture

High-level system overview. This file grows as later phases add
components — it's a living document, not a phase log (see
`docs/phases/` for the history of what shipped when).

## Current shape (through Phase 3)

- **Single Spring Boot monolith.** One Maven module (`backend/`), no
  microservices, no gRPC. See `README.md` for the repo layout.
- **PostgreSQL**, single schema (`trademesh`), one database for the whole
  app — no schema-per-domain split. Schema managed via Hibernate
  `ddl-auto=update` for now (see `docs/phases/phase-01-backend-setup.md`
  for why, and when that's expected to switch to Flyway).
- **Matching engine core** (`com.trademesh.backend.engine`) — an
  in-process, in-memory, per-symbol order book with price-time-priority
  matching. Plain Java, no Spring/JPA dependency. See
  `docs/matching-engine.md` for the design.
- **Trade execution & persistence** (`com.trademesh.backend.service`,
  Phase 3) — wires the in-memory engine to Postgres: `TradeService`
  submits/cancels orders against the engine and persists the resulting
  trades and order-state changes; `OrderBookRegistry` holds one
  `OrderBook` per symbol; `OrderBookLoader` / `OrderBookWarmupRunner`
  rebuild the in-memory book from Postgres on startup so a restart
  doesn't silently lose the resting book.

Not yet present: authentication/JWT, Redis, WebSocket/market-data
streaming, REST controllers beyond the actuator health endpoint, and the
frontend. See the README's phase status table for what's next.

## Known limitations

### The in-memory order book and Postgres can diverge if persistence fails mid-request (Phase 3)

`TradeService.submitOrder` (and `cancelOrder`) do two things per call:
run the match against the live, in-memory `OrderBook`, then persist the
result (the incoming order, any generated trades, and any resting orders
whose state changed) inside a single `@Transactional` boundary.

The problem: **the in-memory `OrderBook` is not transactional.** By the
time persistence runs, the engine has already mutated its in-memory state
— resting orders may have been reduced or removed from the book, and the
incoming order's status/remaining quantity already reflect the match. If
the subsequent database write then fails (constraint violation, lost
connection, anything that trips `@Transactional`'s rollback), Spring rolls
back every JPA write for that call — but nothing rolls back the in-memory
engine. The live book ends up *ahead of* Postgres: it reflects a match
that, as far as the database is concerned, never happened.

Concretely, after such a failure:

- Postgres shows the pre-match state (correct, rolled back).
- The live `OrderBook` shows the post-match state (a resting order may be
  gone or partially consumed, even though nothing was persisted).
- Any order submitted after the failure matches against the *wrong*
  (already-consumed) in-memory book, not against what's actually in the
  database.

This is exercised directly by `TradeServiceTest`'s persistence-failure
test (`backend/src/test/java/com/trademesh/backend/service/TradeServiceTest.java`),
which forces a save failure mid-flow and asserts both halves: Postgres
correctly has no partial writes, and the in-memory book has already
diverged from it.

The divergence is self-limited to "until the next restart" — the startup
reload (`OrderBookLoader`) always rebuilds the book from Postgres, which
is the source of truth, so a restart heals it. But between the failure
and the next restart, the live book can produce matches that don't
correspond to reality.

**This is a known, accepted gap for Phase 3, not an oversight.** A proper
fix — e.g. only mutating the in-memory book after the database commit
succeeds (which requires restructuring matching to be replayable, or
holding a lock across both steps), or an event log / outbox that the book
replays deterministically — is out of scope for this phase. It should be
addressed before this engine handles real concurrent traffic.
