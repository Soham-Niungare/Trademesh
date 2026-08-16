# Phase 4: Authentication + First Order REST API

Two things, done together because "secure the order APIs" requires order
APIs to exist first: stateless JWT authentication
(`com.trademesh.backend.security`), and the first REST surface over
Phase 3's `TradeService` (`com.trademesh.backend.controller`).

For the full authentication design (request flow, the ownership-check
pattern, secret/expiry config), see the "Authentication" section of
[`docs/architecture.md`](../architecture.md#authentication) — that's the
canonical, kept-current version. This doc covers what was built and
proves the milestone.

## What was built

**`com.trademesh.backend.security`:**

- `JwtService` — issues and parses HMAC-SHA256 JWTs (`io.jsonwebtoken`
  0.12.x). Fails loudly: invalid/expired/tampered/malformed tokens throw,
  nothing is swallowed here.
- `JwtAuthenticationFilter` — a plain `OncePerRequestFilter` (not a
  Spring bean — see the class-level note on `SecurityConfig` for why:
  Spring Boot auto-registers any `Filter`-type bean as a second,
  independent servlet filter, which would run this twice per request).
  Reads `Authorization: Bearer <token>`, and on a valid token sets the
  subject (`UUID` userId) as the authenticated principal. On a
  missing/invalid token it does nothing and continues the chain — it
  never rejects a request itself.
- `SecurityConfig` — stateless sessions, CSRF disabled, `/api/auth/**`
  and `/actuator/health` open, everything else authenticated. Registers
  `RestAuthenticationEntryPoint` so a missing/invalid token on a
  protected route comes back `401` (Spring Security's actual default
  here, with no `httpBasic`/`formLogin` configured, is a silent `403`).
- `PasswordEncoderConfig` — a `BCryptPasswordEncoder` bean.

**`com.trademesh.backend.controller.AuthController`** (+ `AuthService` in
the service package, following the existing `TradeService` pattern of
keeping business logic out of the controller):

- `POST /api/auth/register` → `201 {userId}`, `409` if username or email
  is already taken (checked separately, so the error says which one).
- `POST /api/auth/login` → `200 {token, expiresIn}`, `401` for either bad
  username or bad password — same exception, same message, so the
  response can't be used to enumerate valid usernames.

**`com.trademesh.backend.controller.OrderController`** — all routes
authenticated, `userId` always from `@AuthenticationPrincipal`:

- `POST /api/orders` → `201`, builds a `SubmitOrderRequest` from the
  authenticated userId (never from the request body — `CreateOrderRequest`
  has no `userId` field and ignores unknown JSON properties) and delegates
  to `TradeService.submitOrder`. Response is the persisted order plus any
  trades generated (fetched back via `TradeRepository.findByOrderId`, so
  the response reflects what's actually durable, including `executedAt`).
- `GET /api/orders` → the caller's own order history,
  `OrderRepository.findByUserIdOrderByCreatedAtDesc`, most recent first.
- `GET /api/orders/{id}` → `404` (not `403`) if the order doesn't exist
  *or* belongs to someone else — both cases throw the identical
  `OrderNotFoundException`, so a non-owner gets no signal either way.
- `DELETE /api/orders/{id}` → same ownership check, then
  `TradeService.cancelOrder`; `409` if that returns `false` (order isn't
  currently resting — already `FILLED`/`CANCELLED`, or was a `MARKET`
  order that never rested to begin with). `200` with the order's final
  state on success — not `204`, since the caller needs to see the
  now-`CANCELLED` order.

**`com.trademesh.backend.web.GlobalExceptionHandler`** (new — none
existed before this phase) + `ErrorResponse` — one JSON shape
(`timestamp`, `status`, `error`, `message`) for every error path:
validation failures and domain-invariant violations (`IllegalArgumentException`,
e.g. a `LIMIT` order with no price) → `400`; not-found/not-owned → `404`;
duplicate registration (plus a `DataIntegrityViolationException` fallback
for the same case under a race) → `409`; not-cancellable → `409`; bad
login → `401`. The one error path `@RestControllerAdvice` *can't* reach —
a request rejected by the security filter chain before any controller
runs — is covered by `RestAuthenticationEntryPoint` writing the identical
`ErrorResponse` shape directly.

## Two design decisions worth calling out

- **404, not 403, for another user's order.** Returning `403` would
  confirm the order exists; `404` doesn't. `OrderController.findOwnedOrder`
  throws the same exception for "doesn't exist" and "exists but isn't
  yours" so there's no way to tell them apart from the response.
- **`CreateOrderRequest` has no `userId` field, at all.** Rather than
  accepting a `userId` in the body and just ignoring it in code (an easy
  place for a future edit to accidentally start trusting it),
  `@JsonIgnoreProperties(ignoreUnknown = true)` plus the field's absence
  means there's structurally nothing to read. `OrderControllerTest`
  proves this by sending a spoofed `userId` in the JSON and asserting the
  persisted order belongs to the real, token-derived caller instead.

## Tests

`backend/src/test/java/com/trademesh/backend/`:

- **`security/JwtServiceTest`** — no Spring context, no database; a
  plain unit test. Issue-then-parse round-trips the userId/username;
  parsing a manually-backdated expired token throws
  `ExpiredJwtException`; parsing a token with one flipped character
  throws `JwtException`.
- **`controller/AuthControllerTest`** — `@SpringBootTest` +
  `@AutoConfigureMockMvc`, Testcontainers Postgres (same pattern as
  Phase 3's `TradeServiceTest`, for the same reason: real Postgres
  semantics, no new environmental requirement since Docker is already a
  prerequisite). Register success; duplicate username → `409`; duplicate
  email → `409`; login success returns a non-empty token with a positive
  `expiresIn`; wrong password and unknown username produce the *same*
  `401` response body (asserted by comparing both response messages, not
  just both statuses).
- **`controller/OrderControllerTest`** — same setup. No token → `401`;
  a spoofed `userId` in the request body is ignored (order is created
  under the real caller's id, verified both in the response and by
  re-reading the persisted row); order list is most-recent-first;
  fetching or cancelling another user's order → `404`; cancelling an
  already-`FILLED` order → `409`; and a full
  register → login → place order → get order by id → cancel order flow
  as one authenticated user.

## Milestone

`mvn test` from `backend/` — all 35 tests (13 engine + 1 reload + 6 trade
service, unchanged from Phase 3, plus this phase's 3 JWT + 5 auth + 7
order):

```
$ mvn test
...
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.controller.AuthControllerTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.controller.OrderControllerTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.engine.OrderBookTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.security.JwtServiceTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.OrderBookLoaderTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.TradeServiceTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] BUILD SUCCESS
```

## Still out of scope

- Redis
- WebSocket / real-time market data
- Order book snapshot / market-data REST endpoints (deliberately still
  out of scope this phase, per the project plan — `OrderBook.getSnapshot()`
  exists but nothing exposes it over HTTP yet)
- The frontend
- Flyway migrations
- The Phase 3 in-memory/DB divergence-on-persistence-failure limitation
  (unchanged by this phase — see `docs/architecture.md`)
