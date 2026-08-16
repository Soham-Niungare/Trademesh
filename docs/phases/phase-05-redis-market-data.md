# Phase 5: Redis Market-Data Projection

Adds Redis as a derived, fully rebuildable market-data cache — **not** a
second source of truth. Postgres remains the sole durable source of
truth; the in-memory `OrderBook` remains the sole authoritative
matching-engine state. New/changed code lives in
`com.trademesh.backend.service` (`MarketDataService`, `OrderBookAggregator`,
plus changes to `TradeService` and `OrderBookWarmupRunner`) and
`com.trademesh.backend.controller` (`MarketController`).

For the full design — data model, why the Redis write happens strictly
after commit, why failures never affect trading, why the read endpoint
is public — see [`docs/market-data.md`](../market-data.md), which is the
canonical, kept-current version. This doc covers what was built, a real
bug found along the way, and proves the milestone.

## What was built

- **`MarketDataService`** — `updateSnapshot(symbol)` (refresh from the
  live in-memory engine, called by `TradeService` post-commit),
  `rebuildSnapshot(symbol)` / `rebuildAll(orders)` (recompute from
  Postgres, independent of Redis and of the in-memory engine — used at
  startup and for manual recovery), and `getSnapshot(symbol)` (the read
  path backing the new REST endpoint). Writes catch and log
  `DataAccessException`/`JsonProcessingException`; reads don't.
- **`OrderBookAggregator`** — a small, pure, package-private
  `List<Order> -> OrderBookSnapshot` function (no Spring/Redis/engine
  dependency), reusing `engine.OrderBookSnapshot`/`PriceLevel` as its
  output shape. Self-contained: it filters to the requested symbol and to
  `LIMIT` orders itself rather than trusting the caller to have
  pre-filtered, since a stray `MARKET` order would NPE it (see the bug
  below) rather than just skew a number.
- **`TradeService`** — restructured so the Redis update genuinely happens
  after commit, not just at the end of a still-open `@Transactional`
  method (see architecture.md for exactly why that distinction matters
  with AOP-proxied transactions). Wired into both `submitOrder` and
  `cancelOrder`, since both change a symbol's resting liquidity.
- **`OrderBookWarmupRunner`** — now also calls
  `marketDataService.rebuildAll(...)` on startup, fed the same query
  result already used to rebuild the in-memory engine.
- **`MarketController`** — `GET /api/market/{symbol}/orderbook`, backed
  entirely by `MarketDataService.getSnapshot`. Returns `bestBid`/`bestAsk`
  (null if that side is empty) plus the full sorted depth on each side. A
  symbol with no resting orders returns an empty book, not `404` —
  symbols aren't a registered/known set in this system, so "no orders" is
  a normal state, not an error. **Public** — `SecurityConfig` now
  `permitAll()`s `GET` on `/api/market/**` specifically, the one route
  besides `/api/auth/**` that doesn't require a token. This was flagged
  as an open question when the endpoint first shipped and decided
  explicitly shortly after: the snapshot is aggregated by price level
  with no per-order/per-user detail, a real exchange's book depth is
  normally public, and the dashboard is expected to show it before
  login. See `docs/market-data.md`'s "Access" section for the full
  reasoning; `MarketControllerTest` proves a request with no
  `Authorization` header succeeds.
- **Redis wiring** — `spring-boot-starter-data-redis` (nothing else
  needed: `StringRedisTemplate` is auto-configured out of the box and is
  already exactly the `RedisTemplate<String, String>` shape needed here,
  so there's no custom `RedisConfig`/connection-factory bean); `redis`
  service added to the root `docker-compose.yml` (no volume — losing
  Redis's data is fine, it's rebuilt from Postgres on every boot);
  `spring.data.redis.host`/`port` in `application.yml`
  (`REDIS_HOST`/`REDIS_PORT`-overridable, `docker` profile points at
  hostname `redis`). No `application-test.yml` was added: this project's
  existing tests (Phases 3–4) configure Postgres per-test-class via
  Testcontainers `@ServiceConnection`/`@DynamicPropertySource` rather
  than a static test config file, and Redis follows that same pattern
  here rather than introducing a new one.

## A real bug found and fixed, not silently patched

While wiring `rebuildAll` into `OrderBookWarmupRunner`, tracing through
what "resting orders" actually means in this codebase surfaced a
pre-existing, unrelated bug in the Phase 3 startup path — nothing to do
with Redis, but directly adjacent to the code this phase touches, and
exactly the kind of mistake `OrderBookAggregator` had to be careful not
to repeat.

**The bug:** `EngineOrderMapper.applyEngineState` (Phase 3) copies an
engine order's `status`/`remainingQuantity` onto its JPA row for *every*
order type, not just `LIMIT`. A `MARKET` order that doesn't fully fill
ends up persisted with `status = OPEN` or `PARTIALLY_FILLED` — correctly,
per the engine's own documented semantics (`docs/matching-engine.md`:
"`OPEN` is used to mean 'nothing filled' rather than 'resting in the
book'"). But `OrderBookWarmupRunner` fetched "resting orders" with
`OrderRepository.findByStatusInOrderByCreatedAtAsc(OPEN, PARTIALLY_FILLED)`
— a status-only filter, with no `type` check — and handed every result
straight to `OrderBookLoader.reload`, which calls
`OrderBook.restoreRestingOrder` unconditionally. That method explicitly
rejects non-`LIMIT` orders:

```java
if (order.getType() != OrderType.LIMIT) {
    throw new IllegalArgumentException("Only LIMIT orders can rest in the book");
}
```

**Impact:** any `MARKET` order that didn't fully fill (a completely
normal, already-tested scenario — see Phase 3's
`marketOrderWithNoLiquidity_persistsOrderButNoTrades`) leaves a row in
Postgres that would crash `OrderBookWarmupRunner.run()` — an
`ApplicationRunner`, so an uncaught exception there fails application
startup entirely — on *every subsequent restart*, for as long as that row
existed (i.e. forever, since nothing ever changes its status). This was
never caught before because no existing test combined "a market order
didn't fully fill" with "then the app restarts."

**The fix:** `OrderRepository.findByStatusInOrderByCreatedAtAsc(...)` was
replaced with `findByTypeAndStatusInOrderByCreatedAtAsc(OrderType.LIMIT, ...)`
(and a new `findBySymbolAndTypeAndStatusIn` added for the single-symbol
rebuild path) — "resting orders" now means what it always should have:
`LIMIT` orders with status `OPEN`/`PARTIALLY_FILLED`. `OrderBookLoaderTest`
got a regression case: a `MARKET` order with status `OPEN` seeded
alongside the existing `LIMIT` orders, asserting reload no longer throws
and the market order's quantity doesn't leak into the rebuilt book.
`OrderBookAggregator` applies the same `type == LIMIT` filter defensively
on its own (see above) rather than only trusting its caller to have
pre-filtered.

## Tests

`backend/src/test/java/com/trademesh/backend/controller/MarketControllerTest`
proves the access decision directly: `GET /api/market/{symbol}/orderbook`
with no `Authorization` header at all still returns `200`, through the
real Spring Security filter chain (via `MockMvc`), not just a unit-level
assumption.

`backend/src/test/java/com/trademesh/backend/service/`:

- **`OrderBookAggregatorTest`** — no Spring, no database; pure unit
  tests. Sums quantity at a shared price level; sorts bids
  descending/asks ascending; ignores orders for other symbols and
  `MARKET` orders even if handed a mixed/dirty list (the defensive
  filter described above); empty input produces an empty snapshot.
- **`OrderBookLoaderTest`** — updated for the new repository method, plus
  the `MARKET`-order regression case for the bug above.
- **`MarketDataServiceTest`** — `@SpringBootTest` with **both** a
  Postgres and a Redis Testcontainer (Redis via a plain `GenericContainer`
  + `@DynamicPropertySource`, since there's no built-in Spring Boot
  service-connection factory for a bare `GenericContainer`). Placing
  resting orders produces a correctly aggregated Redis snapshot; a full
  match empties it back out; cancelling an order removes it; a
  never-traded symbol returns an empty snapshot, not an error;
  `rebuildSnapshot` recomputes correctly from Postgres after Redis is
  directly corrupted with garbage (proving it's genuinely independent of
  whatever Redis currently holds); `rebuildAll` correctly groups a batch
  spanning multiple symbols.
- **`MarketDataResilienceTest`** — a separate `@SpringBootTest` class,
  Postgres only, with `spring.data.redis.port` pointed at port 1 on
  `localhost` (nothing ever listens there, so every Redis call fails
  fast and deterministically — no container start/stop lifecycle to
  manage, no risk of affecting other test classes). Proves order
  placement, matching, *and* cancellation all still succeed and persist
  correctly with Redis genuinely unreachable for the whole class.

`TradeServiceTest` and `OrderBookLoaderTest`'s other assertions
(Phases 3) needed no changes beyond the query rename above — they keep
passing unmodified, incidentally also demonstrating Redis-failure
tolerance, since they never configure Redis at all and the default
`localhost:6379` has nothing listening in the test environment either.

## Milestone

`mvn test` from `backend/` — all 48 tests (35 unchanged from Phase 4,
plus this phase's 13):

```
$ mvn test
...
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.controller.AuthControllerTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.controller.MarketControllerTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.controller.OrderControllerTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.engine.OrderBookTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.security.JwtServiceTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.MarketDataResilienceTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.MarketDataServiceTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.OrderBookAggregatorTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.OrderBookLoaderTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.TradeServiceTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] BUILD SUCCESS
```

## Still out of scope

- WebSocket / real-time streaming (market data is pull-only via
  `GET /api/market/{symbol}/orderbook` this phase; pushing snapshot
  changes to subscribed clients is Phase 6)
- The frontend
- Flyway migrations
- The Phase 3 in-memory/DB divergence-on-persistence-failure limitation
  (unchanged by this phase — see `docs/architecture.md`)
- An HTTP endpoint for the manual Redis rebuild path — the task framed
  this as "a method or endpoint," and `MarketDataService.rebuildSnapshot`
  already satisfies it as a method; adding an HTTP trigger would need an
  authorization policy decision (who's allowed to force a rebuild?) this
  project has no role system for yet, so it was left as a method only
