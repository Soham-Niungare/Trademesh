# Phase 3: Trade Execution and Persistence

Connects the Phase 2 matching engine (pure in-memory, no persistence) to
PostgreSQL, so trades and order state survive a restart. New code lives in
`com.trademesh.backend.service`
(`backend/src/main/java/com/trademesh/backend/service/`).

## What was built

- **`TradeService`** — the entry point. `submitOrder(SubmitOrderRequest)`
  persists the incoming order, runs it against the live engine, then
  persists every trade generated and every order (incoming plus any
  resting orders touched) whose status/remaining quantity changed — all
  inside one `@Transactional` method. `cancelOrder(UUID)` cancels against
  the live book first and only updates the DB row (`CANCELLED`) if the
  book confirms it was actually resting.
- **`OrderBookRegistry`** — a `@Component` holding the
  `Map<String, OrderBook>` that Phase 2's design doc anticipated a later
  phase would need; `getOrCreate(symbol)` lazily creates one `OrderBook`
  per symbol.
- **`OrderBookLoader`** — takes a list of already-fetched, oldest-first
  `Order` rows and feeds them into an `OrderBookRegistry` via
  `OrderBook.restoreRestingOrder`. Deliberately has no Spring or JPA
  dependency of its own (it just takes a `List<Order>`), so it's directly
  unit-testable without a Spring context.
- **`OrderBookWarmupRunner`** — an `ApplicationRunner` that, on startup,
  queries `OrderRepository.findByStatusInOrderByCreatedAtAsc(OPEN,
  PARTIALLY_FILLED)` and hands the result to `OrderBookLoader`, so a
  restart rebuilds every symbol's book instead of starting empty.
- **`EngineOrderMapper`** — package-private field mapping between the
  engine's plain types (`EngineOrder`, `EngineTrade`) and the JPA
  entities (`Order`, `Trade`); centralizes the mapping so `TradeService`
  stays focused on orchestration and the transaction boundary.
- **Engine changes** (`com.trademesh.backend.engine`, documented in full
  in [`docs/matching-engine.md`](../matching-engine.md)): `MatchResult`
  gained `affectedRestingOrders` (every resting order touched by a match,
  not just the trades), `EngineOrder` gained a rehydration constructor
  (explicit `remainingQuantity`/`status`, for reconstructing a
  partially-filled order from a DB row), and `OrderBook` gained
  `restoreRestingOrder` (insert without matching, for the startup
  reload).
- **Repository additions:** `OrderRepository.findByUserIdOrderByCreatedAtDesc`
  (order history), `OrderRepository.findByStatusInOrderByCreatedAtAsc`
  (startup reload), `TradeRepository.findBySymbolOrderByExecutedAtDesc`
  (trade history), `TradeRepository.findByOrderId` (trades either side of
  a given order, via a small `@Query` since Spring Data can't derive an
  "either column" match from one parameter alone).

### A schema fix this phase needed

`Order.id` was `@GeneratedValue(strategy = GenerationType.UUID)` since
Phase 1. That's incompatible with what this phase actually needs:
`TradeService` must know an order's id *before* it's persisted, so it can
hand the same id to the in-memory `EngineOrder` and return it to the
caller. Hibernate's `UUID` generator unconditionally assigns a fresh id
at insert time regardless of anything pre-set on the entity — it doesn't
check "is this already set." With the generator still in place, every
`save()` silently got a new random id, so `TradeService`'s own
`orderId` variable never matched what actually landed in the row, and
calling `save()` twice on the same logical order (once before matching,
once after) produced two separate rows instead of one insert + one
update. Fixed by dropping `@GeneratedValue` from `Order.id` entirely —
the id is now purely application-assigned, which is what every write
path here already assumed.

## Known limitation: in-memory book and Postgres can diverge on a persistence failure

Documented in full in
[`docs/architecture.md`](../architecture.md#known-limitations), summarized
here: `TradeService` mutates the live `OrderBook` *before* it persists
the result. The in-memory book is not part of the `@Transactional`
boundary, so if the database write fails after a match already happened,
Spring rolls back every JPA write — but the engine's in-memory state
(resting orders reduced or removed) stays mutated. The book ends up ahead
of what Postgres actually has, until the next restart's reload heals it
from the database. This is accepted as a known gap for this phase, not
silently ignored — see architecture.md for what a real fix would involve
and why it's out of scope here.

`TradeServiceTest.persistenceFailureMidFlow_rollsBackDbButLeavesInMemoryBookAheadOfIt`
proves both halves directly: it forces a save failure via a Mockito spy
on `TradeRepository` (there's no reachable path where `TradeService`'s
own correct code produces a genuine constraint violation, so the spy
stands in for any real persistence failure at that point in the flow),
then asserts Postgres has no partial writes while the live book has
already diverged from it.

## Tests

`backend/src/test/java/com/trademesh/backend/service/`:

- **`TradeServiceTest`** — `@SpringBootTest` against a real, ephemeral
  PostgreSQL container via Testcontainers (`@ServiceConnection`), not an
  embedded/emulated database. Chosen over H2 specifically so the tests
  exercise real Postgres semantics rather than an emulation layer's
  approximation of them, and over requiring a developer to have already
  run `docker compose up -d` so `mvn test` stays self-contained and
  reproducible — Docker is already a hard prerequisite for this project,
  so Testcontainers doesn't add a new environmental requirement, just
  automates it. The class deliberately avoids Spring's test-managed
  transaction rollback (no `@Transactional` on the test class): each
  `TradeService` call needs to commit or roll back for real so the
  rollback test can observe genuinely-persisted state in a fresh read,
  not whatever's visible inside a single still-open test transaction;
  `@AfterEach` wipes both tables instead. Covers: a full match persisting
  exactly one `Trade` row with both orders `FILLED`; a partial fill
  persisting accurate `remainingQuantity`/`PARTIALLY_FILLED` on the
  resting side; a zero-liquidity market order persisting the order with
  zero `Trade` rows; cancellation removing the order from the live book
  and setting `CANCELLED` in the DB; cancelling an unknown id returning
  `false`; and the persistence-failure/rollback test described above.
- **`OrderBookLoaderTest`** — `@DataJpaTest` (`Replace.NONE`, same
  Testcontainers Postgres) plus a plain, non-Spring
  `new OrderBookLoader().reload(...)` call. Seeds `OPEN`,
  `PARTIALLY_FILLED`, `FILLED`, and `CANCELLED` orders, reloads, and
  asserts: only the resting ones make it into the rebuilt snapshot, their
  quantities aggregate correctly per price level, and — submitting one
  more matching order afterward — time priority survived the round trip
  (the older of two same-price resting sells fills first).

## Milestone

`mvn test` from `backend/` (all 20 tests: 13 engine tests unchanged from
Phase 2, plus this phase's 7):

```
$ mvn test
...
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.engine.OrderBookTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.OrderBookLoaderTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.TradeServiceTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] BUILD SUCCESS
```

## Still out of scope

- REST controllers (submitting/cancelling orders is only reachable via
  `TradeService` directly, no HTTP surface yet, beyond the actuator
  health endpoint)
- JWT / authentication / security
- Redis
- WebSocket / real-time market data
- The frontend
- A real fix for the in-memory/DB divergence limitation above
- Flyway migrations
