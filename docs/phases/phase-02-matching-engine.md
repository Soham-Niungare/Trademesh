# Phase 2: Matching Engine Core

The matching engine lives in `com.trademesh.backend.engine`
(`backend/src/main/java/com/trademesh/backend/engine/`) as plain Java —
no Spring annotations, no JPA, no dependency on anything in
`com.trademesh.backend.entity` beyond the three shared enums
(`OrderSide`, `OrderType`, `OrderStatus`), which are plain enums with no
persistence concerns of their own. The engine can be exercised entirely
through `mvn test`, with no database, no Spring context, and no running
server.

For the design itself — the book structure, matching rules, and the
tradeoffs behind them — see [`docs/matching-engine.md`](../matching-engine.md),
which is maintained as a living reference rather than repeated per phase.

### Key classes

| Class | Purpose |
|---|---|
| `EngineOrder` | Mutable order used only inside the engine — separate from the JPA `Order` entity so the engine has no persistence dependency. |
| `EngineTrade` | Immutable record of one execution: symbol, buy/sell order ids, price, quantity. No `executedAt` — that's assigned when a later phase persists it as an `entity.Trade`. |
| `MatchResult` | Returned by `submitOrder`: the trades generated, the incoming order's final status, and its remaining quantity. |
| `OrderBookSnapshot` / `PriceLevel` | Aggregate view of both sides of the book (price + total resting quantity per level), for a future market-data API/WebSocket to consume. |

### Milestone: orders can be inserted, matched, partially filled, completed, or cancelled — entirely inside the engine

Proven by `mvn test` (from `backend/`), no server required:

```
$ mvn test
...
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.trademesh.backend.engine.OrderBookTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.296 s -- in com.trademesh.backend.engine.OrderBookTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] BUILD SUCCESS
```

`OrderBookTest` (`backend/src/test/java/com/trademesh/backend/engine/OrderBookTest.java`)
covers, one test per rule: the project-doc example (partial fill against
the nearer price level, remainder rests `PARTIALLY_FILLED`); an exact
quantity match filling both sides completely; same-price time priority;
cross-price priority on both the buy-matches-lowest-sell and
sell-matches-highest-buy paths; a market order with insufficient
liquidity (partial fill, does not rest); a market order with zero
liquidity (nothing matches); a market sell and a market buy each
sweeping multiple price levels; cancellation removing a resting order;
and cancellation correctly returning `false` for an unknown, an
already-filled, and an already-cancelled order.

## What's explicitly out of scope so far (as of the end of Phase 2)

- REST controllers wiring the engine up to HTTP (beyond the actuator
  health endpoint)
- Persisting `EngineTrade`s / order state changes to Postgres
  (`entity.Order` / `entity.Trade` and the engine's `EngineOrder` /
  `EngineTrade` are intentionally unconnected so far)
- JWT / authentication / security
- Redis
- WebSocket
- Flyway migrations (see the Phase 1 doc)
- Wiring the backend into `docker-compose.yml`
- Concurrency control around `OrderBook` (single-threaded use only, for now)

(Persistence is picked up in [Phase 3](phase-03-trade-execution.md).)
