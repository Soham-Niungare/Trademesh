# Phase 6: WebSocket Real-Time Updates

Two things, in order: a required refactor introducing an internal event
hook (`com.trademesh.backend.event`), so `TradeService` no longer calls
`MarketDataService` directly; then WebSocket broadcasting
(`com.trademesh.backend.websocket`) built on top of that same hook.

For the full design — the event types, why the event fires strictly
post-commit and not via `@TransactionalEventListener`, the topic scheme,
and why `ORDER_UPDATED` needed its own message shape but not its own
event type — see [`docs/websocket.md`](../websocket.md), the canonical,
kept-current version. This doc covers what was built, the refactor's
verification, and proves the milestone.

## Part 1: the event-hook refactor

`TradeService` used to call `marketDataService.updateSnapshot(symbol)`
directly, right after `TransactionTemplate.execute(...)` returned. It now
publishes `TradeExecutedEvent` (from `submitOrder`) or `OrderCancelledEvent`
(from `cancelOrder`) via a plain `ApplicationEventPublisher` at that exact
same point instead, and no longer imports or references `MarketDataService`
anywhere. `MarketDataService` gained two `@EventListener` methods
(`onTradeExecuted`/`onOrderCancelled`) that just call the existing,
unchanged `updateSnapshot(String symbol)` — the trigger mechanism changed;
the snapshot logic itself didn't.

This was done and verified **before** any WebSocket code was written:
`mvn test` was run immediately after the refactor, with zero new test
files added yet, specifically to confirm it was behavior-preserving. All
48 tests from Phase 5 passed unchanged. Only after that checkpoint did
WebSocket config/publishing/tests get added.

## Part 2: WebSocket broadcasting

- **`WebSocketConfig`** — `@EnableWebSocketMessageBroker`, a simple broker
  on `/topic`, one STOMP endpoint at `/ws` with a SockJS fallback.
- **`WebSocketPublisher`** — two `@EventListener` methods (mirroring
  `MarketDataService`'s) that translate `TradeExecutedEvent`/`OrderCancelledEvent`
  into broadcasts on three per-symbol topics: `/topic/market/{symbol}/trades`
  (TRADE_EXECUTED), `/topic/market/{symbol}/orders` (ORDER_UPDATED),
  `/topic/market/{symbol}/orderbook` (ORDER_BOOK_UPDATED). Catches
  `MessagingException` and only logs, same resilience principle as
  `MarketDataService`'s Redis writes — a broadcast failure can't turn an
  already-committed trade into an error response.
- **Message DTOs** (`TradeExecutedMessage`, `OrderUpdatedMessage`,
  `OrderBookUpdatedMessage`, `PriceLevelMessage`) — independent of the
  `controller` package's REST DTOs by design (see `docs/websocket.md`).
  `OrderUpdatedMessage` deliberately omits `userId`, present on the
  internal `OrderStateChange` it's built from but never broadcast on this
  public topic.
- **`SecurityConfig`** — `/ws/**` added to the existing `permitAll()`
  list (not scoped to `GET`, unlike `/api/market/**`: SockJS's fallback
  transports genuinely need POST/OPTIONS too).

## Design decisions worth calling out

- **Two event types, not one.** `TradeExecutedEvent` (submit) and
  `OrderCancelledEvent` (cancel) rather than one event with
  sometimes-empty trade fields — a cancellation never involves a trade,
  and an event named for one shouldn't fire when none happened. Both are
  consumed by both `MarketDataService` and `WebSocketPublisher`.
- **`ORDER_UPDATED` got its own message shape, not its own Spring event.**
  The per-order state data it needs is already fully present inside the
  two events above (`OrderStateChange` values); a third
  `ApplicationEvent` class would have meant redundant publishing for data
  that already exists. See `docs/websocket.md`'s "Why `ORDER_UPDATED`
  didn't need its own internal event type" for the full reasoning.
- **`OrderUpdatedMessage` strips `userId`.** Broadcasting who placed an
  order on a public topic would undo the ownership-privacy work from
  Phase 4. A genuine per-user authenticated channel (STOMP user
  destinations) was considered and set aside as out of scope for a
  WebSocket-only session — see `docs/websocket.md`.
- **The WebSocket endpoint is public**, same reasoning as
  `GET /api/market/**` (Phase 5): aggregated, no per-order/per-user
  detail (once `userId` is stripped), and the dashboard is expected to
  show live data before login.

## Tests

`backend/src/test/java/com/trademesh/backend/`:

- **`event/OrderStateChangeTest`** — no Spring, no database; both
  `OrderStateChange.from` overloads (`EngineOrder`, JPA `Order`) map
  their fields correctly.
- **`websocket/WebSocketMessageMappingTest`** — no Spring, no database;
  each `*Message.from(...)` factory, including the specific check that
  `OrderUpdatedMessage` correctly maps the four fields it does carry
  (the absence of `userId` is a compile-time property of the record
  itself, not something a runtime assertion can meaningfully add to).
- **`websocket/WebSocketPublisherTest`** — `@SpringBootTest(webEnvironment = RANDOM_PORT)`
  (a real embedded server; WebSocket needs an actual socket, unlike
  MockMvc's in-memory dispatch) with a real Postgres Testcontainer and a
  real STOMP-over-SockJS client (`WebSocketStompClient` +
  `SockJsClient`/`StandardWebSocketClient`). No Redis configured
  deliberately — `MarketDataService`'s listener silently no-ops if
  unreachable, so it's irrelevant to what this class tests. Three
  scenarios, each registering + logging in a real user and driving
  everything through the actual REST API:
  - Two matching orders (SELL then BUY) → exactly one `TRADE_EXECUTED`
    message arrives on `/topic/market/{symbol}/trades` with the correct
    symbol, price, and quantity.
  - One resting order → one `ORDER_UPDATED` (status `OPEN`) and one
    `ORDER_BOOK_UPDATED` (the new ask level) arrive.
  - Cancelling a resting order → one `ORDER_UPDATED` (status `CANCELLED`,
    correct `orderId`) and one `ORDER_BOOK_UPDATED` (now empty) arrive —
    exercising the `OrderCancelledEvent` path specifically, which the
    other two scenarios never touch.

## Milestone

`mvn test` from `backend/` — all 57 tests (48 unchanged from Phase 5,
plus this phase's 9):

```
$ mvn test
...
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.controller.AuthControllerTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.controller.MarketControllerTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.controller.OrderControllerTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.engine.OrderBookTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.event.OrderStateChangeTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.security.JwtServiceTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.MarketDataResilienceTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.MarketDataServiceTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.OrderBookAggregatorTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.OrderBookLoaderTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.TradeServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.websocket.WebSocketMessageMappingTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.websocket.WebSocketPublisherTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 57, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] BUILD SUCCESS
```

## No bugs found this phase

Unlike Phase 5 (which surfaced a real pre-existing warmup-path bug), this
phase's refactor and new code didn't uncover anything wrong in existing
code — the `TransactionTemplate`-based post-commit timing Phase 3
established turned out to compose cleanly with event publishing once the
publish call was placed at the same point the direct `MarketDataService`
call used to be.

## Still out of scope

- The frontend (Phase 7)
- A genuine per-user authenticated WebSocket channel (considered for
  `ORDER_UPDATED`, set aside — see `docs/websocket.md`)
- An HTTP endpoint for the Phase 5 manual Redis rebuild path (unchanged
  from Phase 5)
- Flyway migrations
- The Phase 3 in-memory/DB divergence-on-persistence-failure limitation
  (unchanged by this phase — see `docs/architecture.md`)
