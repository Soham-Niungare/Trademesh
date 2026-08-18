# Real-time events (internal event hook + WebSocket)

Living design reference for `com.trademesh.backend.event` (the internal
domain events `TradeService` publishes) and `com.trademesh.backend.websocket`
(STOMP config, the outbound message DTOs, and `WebSocketPublisher`). Added
in Phase 6. Like `docs/matching-engine.md`/`docs/market-data.md`, this file
describes the design as it currently stands and is expected to be updated
whenever it changes; see `docs/phases/phase-06-websocket.md` for what
shipped when.

## Why an event hook: decoupling `TradeService` from its consumers

Before this phase, `TradeService` called `MarketDataService.updateSnapshot(symbol)`
directly, right after its `TransactionTemplate.execute(...)` call returned
(see `docs/market-data.md` for why that timing matters). Adding WebSocket
broadcasting the same way would have meant `TradeService` also calling
`WebSocketPublisher` directly — a second downstream dependency, and a third
whenever the next consumer shows up.

Instead, `TradeService` now publishes a plain domain event via
`ApplicationEventPublisher.publishEvent(...)` and no longer references
`MarketDataService` (or anything else downstream) at all. `MarketDataService`
and `WebSocketPublisher` each independently react to the same event via a
plain `@EventListener` method. Neither knows the other exists. Adding a
future consumer (an audit log, a metrics counter) means adding another
listener, not touching `TradeService`.

### Two event types, not one

- **`TradeExecutedEvent`** — published once per `submitOrder` call. Carries
  the symbol, every trade generated (possibly none, e.g. a resting limit
  order or an unfilled market order), the incoming order's own final
  state, every resting order whose state changed too, and the resulting
  book snapshot.
- **`OrderCancelledEvent`** — published once per successful `cancelOrder`
  call. Carries the symbol, the cancelled order's final state, and the
  resulting book snapshot.

These are deliberately two separate types rather than one
`TradeExecutedEvent` used for both, with empty/null trade fields on
cancellation. A cancellation never involves a trade — there is no
`EngineTrade` to report — so forcing it through the trade-shaped event
would leave those fields permanently meaningless for that case, and the
class's own name would lie about what happened. Both `MarketDataService`
and `WebSocketPublisher` listen to both event types (a cancellation
changes the book and an order's status exactly as a trade does — it just
never involves a trade itself).

`OrderStateChange` is the shared, immutable payload type both events use
to describe an order's state: `orderId`, `userId`, `symbol`, `status`,
`remainingQuantity`. It's deliberately not the live, mutable `EngineOrder`
itself, which may still be resting in an `OrderBook` and could be mutated
again by a later match — embedding a frozen snapshot instead means nothing
holding onto an event can be retroactively surprised by a state change it
didn't itself observe. Two static factories build it from either source
that naturally carries these fields: `OrderStateChange.from(EngineOrder)`
(used for the incoming order and any resting orders affected by a match)
and `OrderStateChange.from(Order)` (the JPA entity, used for a cancelled
order, which by that point only exists as a DB row plus whatever's already
in-memory in `OrderBook`).

### Why the event is published after `execute()` returns, not inside it, and not via `@TransactionalEventListener`

`TradeService.submitOrder`/`cancelOrder` are structured as: a public method
that calls `transactionTemplate.execute(status -> doX(...))`, then —
**after that call returns** — publishes the event. `TransactionTemplate.execute(...)`
only returns once its transaction has actually committed (or rolled back),
so publishing from the public method, after that call, guarantees the
event fires strictly post-commit.

Two things this deliberately avoids:

- **Publishing from inside the `TransactionTemplate` callback.** That would
  fire the event *before* commit — same class of bug `TransactionTemplate`
  was introduced in Phase 3 to fix in the first place (see
  `docs/market-data.md`'s explanation of why AOP-proxied `@Transactional`'s
  commit timing doesn't line up with "the last line of the method").
- **`@TransactionalEventListener(phase = AFTER_COMMIT)`.** This is Spring's
  built-in mechanism for exactly this kind of "run after commit" need, and
  it would work here too — but it relies on the *publishing* method itself
  running inside an active, AOP-proxied `@Transactional` context, which is
  precisely the mechanism Phase 3 moved away from for this class. Using it
  here would reintroduce the same proxy/self-invocation subtlety in a new
  form, for no benefit over the `TransactionTemplate` boundary that's
  already in place and already proven correct (see
  `TradeServiceTest.persistenceFailureMidFlow_...`). Plain, synchronous
  `ApplicationEventPublisher.publishEvent(...)` calls its listeners
  immediately and on the same thread, in the same method that already
  knows, by construction, that it's running after commit.

If `execute(...)` throws (the transaction rolled back), the event is never
published — the lines constructing and publishing it are simply never
reached, so `MarketDataService`/`WebSocketPublisher` never see a trade or
cancellation that didn't durably happen.

## WebSocket: STOMP over WebSocket with a SockJS fallback

Chosen over plain/raw WebSocket (`WebSocketHandler`): STOMP's topic-based
pub/sub (`SimpMessagingTemplate.convertAndSend(destination, payload)`) is
exactly "broadcast this payload to every subscriber of this destination,"
which is all this phase needs. A raw `WebSocketHandler` would mean
hand-rolling a session registry, subscribe/unsubscribe semantics, and
per-topic broadcast filtering that STOMP already provides. SockJS adds
broader browser/proxy compatibility for Phase 7's dashboard client with no
added server-side complexity — `WebSocketConfig` registers one endpoint,
`/ws`, `.withSockJS()`.

### Topics, scoped per symbol like Redis's keys

Mirroring `MarketDataService`'s Redis key scheme (`marketdata:orderbook:{symbol}`,
one key per symbol — see `docs/market-data.md`), every topic is scoped
`/topic/market/{symbol}/...`, so a client watching one symbol isn't
flooded with every other symbol's activity:

| Topic | Fires on | Payload | Cardinality |
|---|---|---|---|
| `/topic/market/{symbol}/trades` | `submitOrder`, when it matched | `TradeExecutedMessage` | one message per `EngineTrade` |
| `/topic/market/{symbol}/orders` | `submitOrder` or `cancelOrder` | `OrderUpdatedMessage` | one message per order whose state changed |
| `/topic/market/{symbol}/orderbook` | `submitOrder` or `cancelOrder` | `OrderBookUpdatedMessage` | one message, the resulting snapshot |

### Why `ORDER_UPDATED` didn't need its own internal event type, but does have its own message shape

The task this design came out of asked, in effect: does `ORDER_UPDATED`
map cleanly onto `TradeExecutedEvent`, or does it need its own event type,
given a partial fill on a *resting* order isn't itself "a trade"?

The answer split in two:

- **No separate Spring `ApplicationEvent` class was needed.** The order
  state changes `ORDER_UPDATED` needs to report — the incoming order's own
  outcome, and every resting order a match touched — are already fully
  present as `OrderStateChange` values inside `TradeExecutedEvent` (and,
  for a cancellation, inside `OrderCancelledEvent`). Introducing a third
  event type here would have meant either publishing redundant events for
  data the other two already carry, or awkwardly splitting one logical
  "this call changed these things" occurrence across multiple events.
- **`ORDER_UPDATED` does have its own outbound message shape**, `OrderUpdatedMessage`
  — genuinely different from a trade (no counterparty order id, no
  execution price) and from a book snapshot (one order, not an aggregate).
  `WebSocketPublisher` derives it from whichever event it's reacting to.

### Public, unauthenticated — with one deliberate exception

`/ws/**` is `permitAll()` in `SecurityConfig`, same reasoning as
`GET /api/market/**` (Phase 5, see `docs/market-data.md`): this is
read-only, aggregated market activity, not per-user account data, and
Phase 7's dashboard is expected to show it before login.

The one place this needed a second look: `OrderStateChange` (the internal
event payload) carries `userId`, but `OrderUpdatedMessage` (the outbound
WebSocket payload) deliberately does **not** — it has no `userId` field at
all. Broadcasting *who* placed an order on a public topic would leak
per-user information this project has otherwise been careful never to
expose outside of ownership checks (`/api/orders/**` returning `404`, not
`403`, for someone else's order — see `docs/architecture.md`). The order's
id, symbol, status, and remaining quantity reveal nothing beyond what's
already inferable from the equally-public order-book snapshot.

A "real" per-user channel (e.g. a STOMP user-destination like
`/user/queue/orders`, scoped to the authenticated caller) was considered
and set aside: it needs a `Principal` on the STOMP session, which means
authenticating the WebSocket handshake or the STOMP `CONNECT` frame —
effectively a new, separate authentication mechanism for this one
endpoint. That's meaningfully more scope than "decide public vs.
authenticated," and this session was explicitly WebSocket-only, not an
auth-subsystem expansion. Redacting `userId` from the public message was
the pragmatic fix; a genuinely private per-user order-update channel is a
reasonable candidate for a later phase, once there's a broader need for
authenticated real-time channels than this one field.

### Broadcast failures never affect trading

`WebSocketPublisher`'s two `@EventListener` methods catch `MessagingException`
and only log — never rethrow. Same principle as `MarketDataService`'s Redis
writes (`docs/market-data.md`): the trade or cancellation being broadcast
is already durably committed by the time either listener runs, so a
broker/messaging failure must not turn that already-successful operation
into an error response for whatever caller triggered it.

## Key classes

| Class | Purpose |
|---|---|
| `event.OrderStateChange` | Immutable snapshot of one order's state; shared payload type for both events. |
| `event.TradeExecutedEvent` | Published once per `submitOrder` call, post-commit. |
| `event.OrderCancelledEvent` | Published once per successful `cancelOrder` call, post-commit. |
| `websocket.WebSocketConfig` | STOMP broker (`/topic`) + SockJS endpoint (`/ws`) registration. |
| `websocket.WebSocketPublisher` | The two `@EventListener` methods that broadcast to the topics above. |
| `websocket.TradeExecutedMessage` / `OrderUpdatedMessage` / `OrderBookUpdatedMessage` / `PriceLevelMessage` | Outbound JSON payloads, independent of (not reused from) the `controller` package's REST response DTOs — see the note below. |

`websocket` and `controller` deliberately don't depend on each other, even
though `OrderBookUpdatedMessage`/`PriceLevelMessage` are structurally
similar to `controller.OrderBookResponse`/`PriceLevelResponse`. Both are
presentation-layer surfaces (one push-based, one pull-based) that map from
the same underlying domain/event types rather than reaching sideways into
one another — consistent with this project's one-way layering elsewhere
(`engine` ← `service` ← `controller`/`websocket`, with `event` sitting
alongside `service` as the shared vocabulary both `service` and
`websocket` consume). One consequence: `OrderBookUpdatedMessage` is a
strict subset of `OrderBookResponse` (no `bestBid`/`bestAsk` convenience
fields) — a client can derive them from `bids[0]`/`asks[0]` the same way
the REST DTO's own construction does internally.
