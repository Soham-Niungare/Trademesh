# Architecture

High-level system overview. This file grows as later phases add
components — it's a living document, not a phase log (see
`docs/phases/` for the history of what shipped when).

## Current shape (through Phase 7)

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
- **Authentication & the first REST API** (`com.trademesh.backend.security`,
  `com.trademesh.backend.controller`, Phase 4) — stateless JWT auth
  gating a `/api/orders/**` REST surface over `TradeService`. See
  the "Authentication" section below.
- **Redis market-data projection** (`com.trademesh.backend.service`,
  Phase 5) — `MarketDataService` keeps a per-symbol, aggregated
  price-level snapshot in Redis, refreshed after every trade/cancel and
  rebuildable from Postgres on demand. See `docs/market-data.md` for the
  design.
- **Internal event hook + WebSocket** (`com.trademesh.backend.event`,
  `com.trademesh.backend.websocket`, Phase 6) — `TradeService` publishes
  `TradeExecutedEvent`/`OrderCancelledEvent` after each transaction
  commits instead of calling downstream services directly;
  `MarketDataService`'s Redis refresh and the new `WebSocketPublisher`
  (STOMP over WebSocket, public, per-symbol topics) both react to the
  same events independently. See `docs/websocket.md` for the design.
- **Frontend** (`frontend/`, Phase 7) — a Next.js dashboard (App Router,
  TypeScript, Tailwind CSS) consuming all three surfaces above: the REST
  API for auth and orders, the market-data endpoint for the initial book,
  and the WebSocket topics for live updates via `@stomp/stompjs` over
  SockJS. The first code outside `backend/`, and a separate process — the
  backend serves no HTML. See
  `docs/phases/phase-07-frontend.md` for the design (state approach, JWT
  storage, reconnect handling), and `frontend/README.md` for how to run it.
- **CORS** (`com.trademesh.backend.security.CorsConfig`, Phase 7) — added
  so a browser on another origin can call `/api/**` at all. Origins come
  from `cors.allowed-origins` (`CORS_ALLOWED_ORIGINS`-overridable), and
  `/ws/**` is mapped separately to preserve the origin policy
  `WebSocketConfig` already declares rather than narrowing it. See the
  Phase 7 doc for why that second mapping is load-bearing.

## Authentication

Stateless JWT bearer auth, added in Phase 4 and expected to be the
mechanism every later phase (Redis-backed caching, WebSocket, the
frontend) authenticates against — this section is kept current rather
than left as a one-time phase writeup.

### Request flow

1. `POST /api/auth/register` (`AuthController` → `AuthService`) — creates
   a `User` row, password hashed with `BCryptPasswordEncoder`. `409` if
   the username or email is already taken (checked explicitly, so the
   response says which one collided; a `DataIntegrityViolationException`
   handler backs this up in case of a race between the check and the
   insert).
2. `POST /api/auth/login` — looks up the user, checks the password with
   `PasswordEncoder.matches`, and on success calls
   `JwtService.issue(userId, username)` for a signed token. Wrong
   password and unknown username both throw the same
   `InvalidCredentialsException` → the same `401` → the same message, so
   the response can't be used to enumerate valid usernames.
3. Every other route requires a `Bearer` token. `JwtAuthenticationFilter`
   (a plain `OncePerRequestFilter`, registered ahead of
   `UsernamePasswordAuthenticationFilter` — see `SecurityConfig`) reads
   the header, calls `JwtService.parse`, and on success sets the token's
   subject (the user's id, as a `UUID`) as the `Authentication` principal.
   `JwtService` itself fails loudly — invalid/expired/tampered/malformed
   all throw `JwtException` (or `IllegalArgumentException` for a garbage
   subject) — and the filter is where that gets caught and turned into
   "leave the request unauthenticated," not swallowed silently.
4. A request that reaches a protected route with no valid authentication
   is rejected by Spring Security's authorization rules before any
   controller runs. `RestAuthenticationEntryPoint` is registered
   specifically so that rejection comes back as `401` with the same JSON
   error shape `GlobalExceptionHandler` uses elsewhere — Spring
   Security's actual default here (with no `httpBasic`/`formLogin`
   configured) is `Http403ForbiddenEntryPoint`, i.e. a silent `403`
   instead; this is the fix for that, not a hand-rolled response format.

Nothing here uses Spring Security's own `AuthenticationManager`/
`UserDetailsService` machinery — login is a plain REST call that verifies
credentials directly and hands back a token. Spring Security is used only
for the stateless bearer-token filter chain and its authorization rules.

### Order ownership: 404, not 403, for someone else's order

`OrderController` takes `userId` exclusively from
`@AuthenticationPrincipal` (the JWT subject) — `CreateOrderRequest` has no
`userId` field at all, and is annotated
`@JsonIgnoreProperties(ignoreUnknown = true)` so a client-supplied
`userId` in the JSON body is silently dropped rather than read or
rejected. There is no code path where a request body can determine whose
order gets placed, touched, or cancelled.

Fetching or cancelling another user's order returns `404`, not `403`:
`OrderController.findOwnedOrder` throws the exact same
`OrderNotFoundException` whether the order genuinely doesn't exist or
exists but belongs to someone else, so a non-owner gets no signal that
the order is even there. This is deliberate — it's the same reasoning as
the login-enumeration point above, applied to resource existence instead
of usernames.

### Secret and expiry

`jwt.secret` / `jwt.expiration-ms` in `application.yml`, overridable via
the `JWT_SECRET` / `JWT_EXPIRATION_MS` env vars. The checked-in default
secret is dev-only (long enough for HS256's 256-bit minimum, but public
in source control) — any real deployment must override it via env var,
same pattern as the Postgres credentials.

## Known limitations

### Concurrent order submissions corrupt the book (Phase 8)

`OrderBook` is not thread-safe and `TradeService` takes no lock, so two
simultaneous `POST /api/orders` for the same symbol run `submitOrder` on
the same book from two request threads at once. A transaction boundary
does not help: it protects the database, not an in-memory data structure.

Measured, not inferred — see
[`docs/matching-engine.md`](matching-engine.md#known-limitation-concurrent-submissions-corrupt-the-book-phase-8)
for the full write-up and the numbers. Two silent failure modes: quantity
traded away that the resting order never gave up (phantom liquidity that
gets sold again), and accepted orders that never enter the book at all.
Every request returns `201`; nothing is thrown or logged.

Distinct from the Phase 3 limitation below, despite the overlapping
symptom: there, a failed write leaves the book ahead of Postgres and a
restart heals it. Here the corrupted state is what got persisted, so the
startup reload faithfully restores the wrong numbers.

`ConcurrentOrderSubmissionTest` demonstrates both and is `@Disabled` — the
acceptance criterion for a fix that has not been attempted, because the
options (a per-symbol lock spanning match *and* persistence, or a
single-writer queue per book) differ materially in throughput and need
their own design pass. **This should be resolved before deployment exposes
the engine to more than one concurrent caller.**

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
