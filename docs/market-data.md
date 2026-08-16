# Market data (Redis)

Living design reference for `com.trademesh.backend.service.MarketDataService`
and its supporting pieces (`OrderBookAggregator`, and the market-data-related
changes to `TradeService`/`OrderBookWarmupRunner`). Added in Phase 5. Like
`docs/matching-engine.md`, this file describes the projection as it currently
stands and is expected to be updated whenever its design changes — it is not
a snapshot of one phase; see `docs/phases/phase-05-redis-market-data.md` for
that.

Redis here is a **derived, fully rebuildable projection, never a second
source of truth.** Postgres remains the sole durable source of truth; the
in-memory `OrderBook` (see `docs/matching-engine.md`) remains the sole
authoritative matching-engine state. `MarketDataService` only ever reads
from one of those two and writes to Redis, never the reverse, and nothing
in the matching engine itself knows Redis exists.

## Data model

One Redis `STRING` key per symbol, `marketdata:orderbook:{symbol}`,
holding a JSON-serialized snapshot: `{symbol, bids, asks}`, where `bids`/
`asks` are each a list of `{price, totalQuantity}` — the remaining
quantity of every resting `LIMIT` order at that price, summed, sorted
bids highest-first and asks lowest-first (exactly the shape
`OrderBook.getSnapshot()` already produces internally; the same class,
`engine.OrderBookSnapshot`, is reused directly for JSON (de)serialization
rather than maintaining a parallel type).

A single JSON blob per symbol was chosen over e.g. a Redis `ZSET` per
side: every access pattern here reads or writes the *whole* snapshot at
once, so a sorted-set's native range queries wouldn't buy anything, and
one key per symbol keeps the read path a single `GET`.

## Two ways a snapshot gets computed

- **`updateSnapshot(symbol)`** — reads the *live* `OrderBook` for that
  symbol (`OrderBookRegistry.getOrCreate(symbol).getSnapshot()`) and
  writes it to Redis. This is the normal, hot-path update: `TradeService`
  calls it once per `submitOrder`/`cancelOrder`, strictly *after* that
  call's DB transaction has committed (see below).
- **`rebuildSnapshot(symbol)`** / **`rebuildAll(orders)`** — recompute
  directly from Postgres (`OrderBookAggregator`, a pure `List<Order> ->
  OrderBookSnapshot` function with no Spring/Redis/engine dependency),
  independent of whatever Redis currently holds *and* independent of the
  in-memory engine. `rebuildAll` is wired into `OrderBookWarmupRunner`
  right alongside the existing in-memory rebuild, reusing the exact same
  Postgres query result so Redis's initial snapshot matches the
  freshly-warmed engine with no extra round-trip. `rebuildSnapshot` (one
  symbol) is the manual/runtime recovery path if Redis data is lost or
  suspected stale — deliberately reading from Postgres rather than the
  live engine, since the engine itself can have silently drifted from
  Postgres (see the Phase 3 divergence limitation in
  `docs/architecture.md`); rebuilding from a possibly-already-wrong
  in-memory book wouldn't actually fix anything. Exposed as a plain
  method only, no HTTP trigger — see "Deliberate omissions" below.

`OrderBookAggregator.aggregate(symbol, orders)` is self-contained rather
than trusting its caller to have pre-filtered: it only ever aggregates
`orders` whose `symbol` and `type` match (`LIMIT` only). A stray `MARKET`
order slipping through would otherwise `NullPointerException` — `MARKET`
orders carry a null price, and a `TreeMap` can't take a null key. This
is deliberate defense-in-depth after a related bug (see
`docs/phases/phase-05-redis-market-data.md`) was found in the
warmup path during this same phase: `OrderRepository`'s resting-orders
query had been filtering on status only, not also on `type = LIMIT`.

## Why the Redis write happens after commit, not inside the transaction

`TradeService.submitOrder`/`cancelOrder` used to be plain `@Transactional`
methods. Redis needed to update only *after* that transaction commits —
but with Spring's AOP-proxied `@Transactional`, the commit happens when
the proxy's method call returns to its caller, not at the last line of
the method body. Code added at the end of an `@Transactional` method
still runs *before* commit, not after — so the DB work was pulled into a
private `TransactionTemplate`-wrapped method instead.
`TransactionTemplate.execute(...)` only returns once its transaction has
actually committed (or rolled back), so `marketDataService.updateSnapshot(symbol)`,
called from the public method right after `execute(...)` returns, is
genuinely post-commit — a direct, synchronous call, not an event
listener or a queue, per this feature's own design constraint.

If that DB transaction fails, `execute(...)` rolls back and re-throws the
same exception — `updateSnapshot` is never reached, which is correct:
Redis keeps whatever it already had (matching Postgres's rolled-back
state), rather than being refreshed from an in-memory engine that may
itself have already advanced past what got persisted.

## Redis failures never affect trading

`MarketDataService.writeSnapshot` (used by both `updateSnapshot` and the
rebuild paths) catches `DataAccessException`/`JsonProcessingException`
and only logs — it never rethrows. Since the write always happens after
the DB transaction has already committed, there is nothing left to roll
back anyway; the catch exists so a Redis outage can't turn a successful
trade into an error response to the caller. `MarketDataResilienceTest`
proves this directly: with Redis genuinely unreachable for the whole
test class, order placement, matching, and cancellation all still
succeed and persist correctly.

Reads (`GET /api/market/{symbol}/orderbook`) are the deliberate
exception: a `DataAccessException` there is **not** caught, and
`GlobalExceptionHandler` maps it to `503`. There's no safe fallback for a
read that wouldn't either lie ("no orders" vs. "we don't know, Redis is
down" are very different things) or make every read as expensive as a
live Postgres rebuild, defeating the point of caching in Redis at all.

## Access: public, read-only

`GET /api/market/{symbol}/orderbook` is `permitAll()` in `SecurityConfig`
— scoped to `GET` on `/api/market/**` specifically, not the whole prefix
— unlike every other route besides `/api/auth/**`. This was flagged as
an open question at the end of Phase 5 and decided shortly after,
explicitly, rather than left as debt:

- The snapshot is aggregated by price level, not per-order/per-user — it
  leaks no more than any real exchange's public order-book depth does.
- A real exchange's market data (book depth, recent trades) is normally
  public; a dashboard showing it before login is a completely standard
  expectation, and one this project's own API design anticipates.
- Scoping to `GET` (not the whole `/api/market/**` prefix) means a
  future authenticated/admin write under that path — e.g. an HTTP
  trigger for the manual rebuild below — won't inherit public access by
  accident; it falls through to the authenticated-by-default rule
  instead.

`MarketControllerTest` proves this directly: a request with no
`Authorization` header at all still succeeds.

## Deliberate omissions

- **No HTTP endpoint for the manual rebuild.** `MarketDataService.rebuildSnapshot(symbol)`
  is a plain method, callable directly (e.g. from a REPL, an admin tool,
  or a future scheduled job) but not wired to any route. Adding one would
  need an authorization policy — who's allowed to force a rebuild for an
  arbitrary symbol? — that this project has no role system to answer yet.
  Revisit once one exists.
- **No `RedisConfig`/custom connection factory.** Spring Boot's
  auto-configured `StringRedisTemplate` is already exactly the
  `RedisTemplate<String, String>` shape this needs (String keys, JSON
  string values) — there was nothing left to configure.

## Key classes

| Class | Purpose |
|---|---|
| `MarketDataService` | `updateSnapshot`/`rebuildSnapshot`/`rebuildAll`/`getSnapshot` — see above. |
| `OrderBookAggregator` | Pure `List<Order> -> OrderBookSnapshot` aggregation, package-private, no Spring/Redis/engine dependency. |
| `MarketController` | `GET /api/market/{symbol}/orderbook`, public, backed entirely by `MarketDataService.getSnapshot`. |
| `OrderBookResponse` / `PriceLevelResponse` | Response DTOs (in `com.trademesh.backend.controller`) mapped from `engine.OrderBookSnapshot`/`PriceLevel`; `OrderBookResponse` also surfaces `bestBid`/`bestAsk` (null if that side is empty) alongside the full sorted depth. |
