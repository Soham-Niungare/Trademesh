# Phase 1: Backend Setup

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

## Milestone: Spring Boot connects to PostgreSQL

With Postgres running (`docker compose up -d` from the repo root) and the
app running (`mvn spring-boot:run` from `backend/`), confirm the milestone:

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
