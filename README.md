# TradeMesh

TradeMesh is a single-module Spring Boot backend (monolith) for a trading
platform. This repository is being built out in phases; this README reflects
**Phase 1: Backend Setup** and **Phase 2: Matching Engine Core**.

## Repository structure

```
TradeMesh/
├── backend/
│   ├── src/
│   ├── pom.xml
│   └── Dockerfile
├── docker-compose.yml
├── README.md
└── .gitignore
```

## Phase 1 scope

This phase covers project setup, a working database connection, and the
three core domain entities as plain JPA models. It intentionally does **not**
include: matching/order-book logic, JWT or any security, Redis, WebSocket,
or REST controllers beyond the built-in actuator health endpoint. Those are
later phases.

What's in place:

- Spring Boot 3.3.4, Java 21, Maven, single module (`backend/`)
- `spring-boot-starter-web`, `spring-boot-starter-data-jpa`,
  `spring-boot-starter-validation`, `spring-boot-starter-actuator`,
  PostgreSQL driver, `spring-boot-starter-test`
- Spring profiles: `default` (local Postgres on `localhost`) and `docker`
  (Postgres reachable at hostname `postgres`, for later phases when the
  backend itself is added to `docker-compose.yml`)
- Three JPA entities — `User`, `Order`, `Trade` — with matching
  `JpaRepository` interfaces, all in one `trademesh` schema
- `/actuator/health` exposed, including the datasource health indicator

## Schema management: `ddl-auto=update`, not Flyway

For this phase, schema creation uses Hibernate's `ddl-auto: update`
(`backend/src/main/resources/application.yml`) rather than Flyway
migrations.

**Why:** the entity model is still taking shape (order-book and matching
logic in later phases will likely add columns, indexes, and possibly new
tables). `ddl-auto=update` lets the schema evolve for free while the domain
model is unstable, with no migration-authoring overhead. Flyway is the
better tool once the schema stabilizes and the app needs to run against a
persistent environment with real data — at that point every change needs to
be an explicit, reviewable, reversible migration rather than
Hibernate-inferred DDL. Introducing Flyway is expected to happen in a later
phase; this is a deliberate short-term tradeoff, not an oversight.

## Prerequisites

- Java 21 (JDK)
- Maven (or use `./mvnw` if a wrapper is added later — for now, a local
  Maven install is required)
- Docker + Docker Compose (for running Postgres)

## Running Postgres via Docker Compose

From the repo root:

```
docker compose up -d
```

This starts a single `postgres:16-alpine` container:

- database: `trademesh`
- user / password: `trademesh` / `trademesh`
- port: `5432` (mapped to host)
- includes a `pg_isready` healthcheck

Check it's healthy:

```
docker compose ps
```

No backend service is defined in `docker-compose.yml` yet — the backend
still runs directly on the host for this phase (see below). It will be
added to Compose in a later phase, using `backend/Dockerfile` (already
written, multi-stage: Maven build → `eclipse-temurin:21-jre-alpine`
runtime) and the `docker` Spring profile.

## Running the backend locally

With Postgres up (above), from `backend/`:

```
mvn spring-boot:run
```

This runs with the `default` profile, connecting to
`jdbc:postgresql://localhost:5432/trademesh`. On startup, Hibernate will
create/update the `users`, `orders`, and `trades` tables in the `trademesh`
database.

## Milestone: Spring Boot connects to PostgreSQL

With the app running, confirm the milestone:

```
curl http://localhost:8080/actuator/health
```

Expected response — `200 OK` with overall status `UP` and a `db` component
also reporting `UP`:

```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "isValid()"
      }
    },
    ...
  }
}
```

A `db` status of anything other than `UP` (or its absence) means the app
did not successfully connect to Postgres — check that the Compose container
is healthy and that the `default` profile's datasource URL/credentials in
`application.yml` match the container.

## Domain entities (Phase 1)

Plain `@Entity` classes, no business logic — getters/setters only.

- **User** — `id` (UUID), `username` (unique), `email` (unique),
  `passwordHash`, `createdAt`
- **Order** (table `orders`, since `ORDER` is a reserved SQL keyword) —
  `id` (UUID), `userId`, `symbol`, `side` (`BUY`/`SELL`),
  `type` (`LIMIT`/`MARKET`), `price`, `quantity`, `remainingQuantity`,
  `status` (`OPEN`/`PARTIALLY_FILLED`/`FILLED`/`CANCELLED`), `createdAt`
- **Trade** — `id` (UUID), `symbol`, `buyOrderId`, `sellOrderId`, `price`,
  `quantity`, `executedAt`

Repositories (`UserRepository`, `OrderRepository`, `TradeRepository`) are
plain `JpaRepository<T, UUID>` interfaces, with `UserRepository` adding only
`findByUsername`.

## Phase 2: Matching Engine Core

The matching engine lives in `com.trademesh.backend.engine`
(`backend/src/main/java/com/trademesh/backend/engine/`) as plain Java —
no Spring annotations, no JPA, no dependency on anything in
`com.trademesh.backend.entity` beyond the three shared enums
(`OrderSide`, `OrderType`, `OrderStatus`), which are plain enums with no
persistence concerns of their own. The engine can be exercised entirely
through `mvn test`, with no database, no Spring context, and no running
server.

### Design

- **One `OrderBook` per symbol.** `OrderBook` *is* the engine for that
  symbol — it owns the book and exposes `submitOrder`, `cancelOrder`, and
  `getSnapshot` directly, rather than sitting behind a separate
  multi-symbol manager class. A later phase's REST/service layer is
  expected to own a `Map<String, OrderBook>` when it needs to route
  requests across symbols; nothing in this phase requires that routing,
  so it wasn't built.
- **Book structure:** two `TreeMap<BigDecimal, Deque<EngineOrder>>` per
  book — `buyLevels` ordered highest price first
  (`Comparator.reverseOrder()`), `sellLevels` ordered lowest price first
  (natural order). Each price level's `Deque` is FIFO, so time priority
  within a level falls out of ordinary queue insertion/polling — no
  extra bookkeeping needed there. `EngineOrder` still carries a
  monotonic `sequence` (assigned from a static `AtomicLong` at
  construction) as an explicit, inspectable record of arrival order,
  since `System.nanoTime()` isn't guaranteed monotonic across threads.
- **Trade execution price is always the resting order's price**, never
  the incoming order's — implemented by reading `resting.getPrice()`
  when building each `EngineTrade`, regardless of which side is
  incoming.
- **Market orders never rest.** After matching, an order only enters the
  book if it's `LIMIT` and still has `remainingQuantity > 0`. A market
  order that doesn't fully fill simply reports its leftover
  `remainingQuantity` on `MatchResult` and disappears — there is
  deliberately no "resting market order" state.
- **Status on the fixed four-value enum.** `OrderStatus` only has
  `OPEN` / `PARTIALLY_FILLED` / `FILLED` / `CANCELLED` (reused from
  Phase 1, not extended). For a market order that never rests, `OPEN` is
  used to mean "nothing filled" rather than "resting in the book" —
  there's no fifth "unfilled and discarded" state to give it. Callers
  that care whether an order is actually sitting in the book should
  check `remainingQuantity` / whether it was a `LIMIT` order, not lean on
  `OPEN` alone.
- **Cancellation** is O(1) lookup via a `Map<UUID, EngineOrder>` of
  currently-resting orders, then a linear `Deque.remove` (by reference
  equality) to unlink it from its price level, removing the level
  entirely if it's now empty. Returns `false` for an unknown id or an
  order that's no longer resting (already `FILLED` or `CANCELLED`).
- **Not thread-safe.** No synchronization inside `OrderBook` — this
  phase has no concurrent callers (no controllers, no server). Whatever
  wires this into a concurrent context later owns that decision.

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

## What's explicitly out of scope so far

- REST controllers wiring the engine up to HTTP (beyond the actuator
  health endpoint)
- Persisting `EngineTrade`s / order state changes to Postgres
  (`entity.Order` / `entity.Trade` and the engine's `EngineOrder` /
  `EngineTrade` are intentionally unconnected so far)
- JWT / authentication / security
- Redis
- WebSocket
- Flyway migrations (see above)
- Wiring the backend into `docker-compose.yml`
- Concurrency control around `OrderBook` (single-threaded use only, for now)
