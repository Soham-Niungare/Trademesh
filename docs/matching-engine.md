# Matching engine

Living design reference for `com.trademesh.backend.engine`
(`backend/src/main/java/com/trademesh/backend/engine/`). Unlike the
`docs/phases/` logs, this file describes the engine as it currently stands
and is expected to be updated whenever the engine's design changes — it is
not a snapshot of one phase.

The engine itself is plain Java: no Spring annotations, no JPA, no
dependency on anything outside `com.trademesh.backend.entity`'s three
shared enums (`OrderSide`, `OrderType`, `OrderStatus`). It's exercisable
entirely through `mvn test`, with no database and no running server.

## Book structure

One `OrderBook` per symbol. `OrderBook` *is* the engine for that symbol —
it owns the book and exposes `submitOrder`, `cancelOrder`,
`restoreRestingOrder`, and `getSnapshot` directly, rather than sitting
behind a separate multi-symbol manager class. Since Phase 3, the service
layer (`com.trademesh.backend.service.OrderBookRegistry`) owns the
`Map<String, OrderBook>` that routes requests to the right symbol's book —
that's the "later phase" this design anticipated back in Phase 2.

Internally, two `TreeMap<BigDecimal, Deque<EngineOrder>>` per book:

- `buyLevels`, ordered highest price first (`Comparator.reverseOrder()`)
- `sellLevels`, ordered lowest price first (natural order)

Each price level's `Deque` is FIFO, so time priority within a level falls
out of ordinary queue insertion/polling — no extra bookkeeping needed
there. `EngineOrder` still carries a monotonic `sequence` (assigned from a
static `AtomicLong` at construction) as an explicit, inspectable record of
arrival order, since `System.nanoTime()` isn't guaranteed monotonic across
threads. That sequence is metadata for observability, not something the
book's matching or ordering logic reads — priority is entirely a function
of which `TreeMap`/`Deque` position an order sits at.

## Matching rules

- **Trade execution price is always the resting order's price**, never the
  incoming order's — implemented by reading `resting.getPrice()` when
  building each `EngineTrade`, regardless of which side is incoming.
- **Market orders never rest.** After matching, an order only enters the
  book if it's `LIMIT` and still has `remainingQuantity > 0`. A market
  order that doesn't fully fill simply reports its leftover
  `remainingQuantity` on `MatchResult` and disappears — there is
  deliberately no "resting market order" state.
- **Status on the fixed four-value enum.** `OrderStatus` only has
  `OPEN` / `PARTIALLY_FILLED` / `FILLED` / `CANCELLED` (reused from the
  JPA entity, not extended). For a market order that never rests, `OPEN`
  is used to mean "nothing filled" rather than "resting in the book" —
  there's no fifth "unfilled and discarded" state to give it. Callers
  that care whether an order is actually sitting in the book should check
  `remainingQuantity` / whether it was a `LIMIT` order, not lean on `OPEN`
  alone.
- **Cancellation** is O(1) lookup via a `Map<UUID, EngineOrder>` of
  currently-resting orders, then a linear `Deque.remove` (by reference
  equality) to unlink it from its price level, removing the level
  entirely if it's now empty. Returns `false` for an unknown id or an
  order that's no longer resting (already `FILLED` or `CANCELLED`).
- **Not thread-safe.** No synchronization inside `OrderBook`. Concurrent
  access is the caller's responsibility, and **as of Phase 8 that
  responsibility is known to be unmet** — `TradeService` takes no lock, and
  a transaction boundary does not serialize in-memory access. See the
  known limitation below, which supersedes the earlier assumption that
  running inside one transactional method per request was sufficient.

## Known limitation: concurrent submissions corrupt the book (Phase 8)

Two simultaneous `POST /api/orders` for the same symbol run
`OrderBook.submitOrder` on the same book instance from two Tomcat worker
threads at once. Nothing prevents this: `TradeService` holds no lock, and
the surrounding database transaction protects only the database — the
in-memory book is not part of it. `EngineOrder.reduceRemainingQuantity` is
a plain read-modify-write on a mutable field, and the book's `TreeMap`,
`ArrayDeque`, and `restingOrders` `HashMap` are all unsynchronized.

This was measured in Phase 8, not inferred.
`backend/src/test/java/com/trademesh/backend/concurrency/ConcurrentOrderSubmissionTest.java`
fires 32 concurrent submissions and asserts conservation invariants that
hold under any correct interleaving. It fails reliably — twice on first
authoring, with **different magnitudes each time**, which is what confirms
a race rather than an off-by-one:

| Probe | Run 1 | Run 2 |
|---|---|---|
| 32 buys of 1 vs. a resting sell of 32 | 32 traded, only 30 consumed | 32 traded, only 29 consumed |
| 32 resting sells at one price | book held 30 of 32 | book held 29 of 32 |

Two distinct failure modes, both silent — every request returned `201`,
and no exception was thrown or logged in either run:

1. **Phantom liquidity (lost update).** More quantity was traded away than
   the resting order gave up. 32 units were sold and durably persisted as
   trades, while the order that supplied them still showed 2–3 units
   remaining. Those units do not exist but are still offered, and will be
   sold *again* to the next taker. This is the more serious of the two: the
   over-sale is already committed to Postgres by the time anything could
   notice.
2. **Lost resting orders.** With no matching involved at all, orders
   accepted and persisted as `OPEN` never made it into the book. Postgres
   and the engine diverge immediately: those orders can never match, and
   cancelling one returns `409` because the book has no record of it.

Note this is a *different* mechanism from the Phase 3 divergence
limitation in `docs/architecture.md`, though the symptom overlaps. Phase 3
is about a failed database write leaving the book ahead of Postgres, and
heals on the next restart because Postgres is the source of truth. Here
the book is *ahead of nothing* — the corrupt state is what got persisted,
so the startup reload faithfully restores the wrong numbers.

The test is `@Disabled` so the suite stays green, and is kept as
executable evidence rather than deleted; run it with
`mvn test -Dtest=ConcurrentOrderSubmissionTest`. It is the acceptance
criterion for the fix and should be re-enabled as part of it.

**A fix is deliberately not attempted here.** The obvious reflex — marking
`submitOrder` `synchronized` — would serialize every symbol against every
other, since the lock would be per-`OrderBook` but the contention it needs
to remove is per-symbol. The plausible designs are a per-symbol lock held
across the match *and* the persistence that follows it (which lengthens
the critical section to include database I/O), or a single-writer queue
per book with submissions handed off to it. Choosing between those is a
design decision with real throughput consequences and belongs in its own
pass. **This should be resolved before the engine handles genuinely
concurrent traffic** — including before any deployment that exposes it to
more than one caller at a time.

## `MatchResult`

Returned by `OrderBook.submitOrder`:

- `trades` — the `EngineTrade`s generated by this call
- `affectedRestingOrders` — **(added in Phase 3)** every resting
  `EngineOrder` whose state changed during the match (partially or fully
  filled), so a persistence layer can update their DB rows too without
  re-deriving which orders were touched. Before Phase 3, `MatchResult`
  only exposed `trades`, which told a caller *that* resting orders had
  changed but not *which* ones or by how much — insufficient once
  something downstream needs to persist those state changes.
- `finalStatus` / `remainingQuantity` — the incoming order's own
  post-match state

## Restoring a book from persistence

`OrderBook.restoreRestingOrder(EngineOrder order)` — **(added in Phase
3)** — inserts an order directly into its price level without running it
through matching. This exists specifically for rebuilding a book from
rows already in Postgres (see `com.trademesh.backend.service.OrderBookLoader`):
those orders already went through matching once, in a previous process,
so re-matching them would be wrong (and, since their counterparties may
have since been cancelled or filled against other orders, may not even be
possible to reproduce). It only accepts `LIMIT` orders with
`remainingQuantity > 0` and status `OPEN` or `PARTIALLY_FILLED` — the same
invariants that hold for anything that ends up resting via `submitOrder`.

Time priority survives a restore *only if* the caller restores orders in
their original arrival order (oldest first) — `restoreRestingOrder` itself
doesn't know or enforce arrival order, it just appends to the end of
whatever `Deque` its price level already has. The freshly-assigned
`sequence` on the rehydrated `EngineOrder` does **not** need to match the
original one for this to work correctly, since (as above) sequence isn't
what the book's matching logic actually reads — only `Deque` insertion
order matters, and that's under the caller's control.

## Key classes

| Class | Purpose |
|---|---|
| `EngineOrder` | Mutable order used only inside the engine — separate from the JPA `Order` entity so the engine has no persistence dependency. Two constructors: one for a fresh incoming order (`remainingQuantity` defaults to `quantity`, status defaults to `OPEN`), one that takes `remainingQuantity`/`status` explicitly, for rehydrating an order from a DB row. |
| `EngineTrade` | Immutable record of one execution: symbol, buy/sell order ids, price, quantity. No `executedAt` — that's assigned when the persistence layer maps it to an `entity.Trade`. |
| `MatchResult` | See above. |
| `OrderBookSnapshot` / `PriceLevel` | Aggregate view of both sides of the book (price + total resting quantity per level), for a future market-data API/WebSocket to consume. |
