# Phase 8: Testing and Hardening

An audit-first phase, not an "add more tests" phase. Phases 1–7 were built
test-first and left 57 passing tests, so the work here was to find what that
coverage genuinely missed, fix what turned out to be broken, and record what
was deliberately left broken.

The audit ran the project plan's Phase 8 checklist — matching-engine cases,
API cases, integration cases — against the existing suite, class by class.
Most of it was already covered. What follows is what wasn't: two real bugs,
one structural hole in the integration coverage, and a race that had been
assumed away in writing.

Test count went from 57 to 88 (86 executing, 2 deliberately skipped — see
the concurrency section). No production code changed except the two bug
fixes below.

## Two bugs found and fixed

Both were found by the audit reading code and probing a running instance,
not by an existing test failing — which is the point: nothing in the suite
was positioned to notice either one.

### Bug 1: every unhandled error reported itself as an authentication failure

**Symptom.** Malformed JSON, an unknown enum value, a wrong field type, a
genuine server fault, an unsupported HTTP method — all came back
`401 "Missing or invalid authentication token"`. Including on `permitAll`
routes. `POST /api/auth/login` with a malformed body claimed the caller's
authentication was invalid, on an endpoint that requires none.

**Cause,** three things compounding:

1. `GlobalExceptionHandler` had no `HttpMessageNotReadableException`
   handler, so Jackson failures escaped the dispatcher entirely and the
   container forwarded them to `/error`.
2. `/error` was not in `SecurityConfig`'s `permitAll` list, so it was
   subject to `anyRequest().authenticated()`.
3. `OncePerRequestFilter.shouldNotFilterErrorDispatch()` defaults to
   `true`, so `JwtAuthenticationFilter` never ran on that second dispatch.
   The request therefore arrived at the authorization rules unauthenticated
   **no matter what token the caller sent**, and
   `RestAuthenticationEntryPoint` did what it is supposed to do with an
   unauthenticated request to a protected route.

Each piece is individually reasonable, which is why this survived four
phases of review.

**Blast radius was wider than the original report.** The audit only
observed it for malformed bodies; probing the fix showed `405` and `415`
were affected too, since Spring MVC sets those statuses without ever
throwing into a controller and so never had an `@ExceptionHandler` to
reach either.

**Why it mattered beyond the wrong status.** Two consequences:

- It disguised server faults as auth problems. Any future
  `NullPointerException` in a controller would have presented as `401`,
  sending whoever debugged it to look at tokens.
- It logged the Phase 7 frontend out. `frontend/src/lib/api.ts` treats any
  `401` as session-expired: clear the token, redirect to `/login`. A single
  typo'd enum value in a request body would end the user's session.

**Fix.** Two changes, both needed:

- `@ExceptionHandler(HttpMessageNotReadableException.class)` → `400`, in
  the project's standard `ErrorResponse` shape. The message is deliberately
  generic rather than `ex.getMessage()`: Jackson's own text names the
  failing Java class and lists an enum's constants, which this API doesn't
  otherwise expose. Same reasoning as the existing duplicate-registration
  handler.
- `/error` added to `permitAll`, so every *other* error dispatch — 500,
  405, 415, 404 — renders with its real status instead of being challenged.

A blanket `@ExceptionHandler(Exception.class)` was considered and
**rejected**: `ExceptionHandlerExceptionResolver` runs before
`DefaultHandlerExceptionResolver`, so a catch-all would have intercepted
Spring's standard MVC exceptions and turned `405`/`415` into `500`s. That
would have traded one wrong-status bug for another.

One accepted consequence: responses rendered by `/error` use Spring Boot's
default error shape (`timestamp`/`status`/`error`/`path`) rather than this
project's `ErrorResponse`. Everything with an explicit handler still uses
`ErrorResponse`. Making the last-resort fallback match too would mean
either a catch-all handler (rejected above) or customising
`BasicErrorController`, neither of which is worth it for a path that
should never be hit.

### Bug 2: negative and zero prices were accepted on LIMIT orders

**Symptom.** `POST /api/orders` with `"price": -100` or `"price": 0` on a
`LIMIT` order returned `201` and the order rested in the book.

**Cause.** `CreateOrderRequest.price` carried no validation annotation at
all — `quantity` had `@NotNull @Positive`, `price` had nothing — and
nothing downstream checked its sign either. `EngineOrder` only rejects a
*null* price on a LIMIT order; `OrderBook.submitOrder` only checks the
symbol. There was no positivity check anywhere in the chain.

**Impact.** A nonsensical price level enters the book and the Redis
projection, corrupting the depth ladder and the `bestBid`/`bestAsk` derived
from it. A resting sell at `0` becomes the best ask, and the next market
buy executes against it at zero. Nothing of monetary value moves — there is
no balance or settlement system — but plainly invalid input reached the
matching engine and was persisted.

**Fix.** `@Positive` on `price`, deliberately **without** `@NotNull`. Bean
Validation treats `null` as valid for `@Positive`, which is exactly the
behaviour needed: a MARKET order legitimately carries no price and must
still be accepted, while "LIMIT with no price" stays where it belongs, as
`EngineOrder`'s own invariant. Making price mandatory would have been the
obvious wrong fix, and `submitOrder_marketOrderWithNoPrice_stillAccepted`
exists specifically to catch someone doing that later.

### Both fixes were verified against the pre-fix code

Following the project's established pattern, the regression tests were
proven to actually catch the bugs rather than merely pass alongside them:
the three production fixes were stashed, the tests re-run, and the failures
confirmed —
`submitOrder_negativePrice_returns400AndPersistsNothing` failed with
"expected 400 but was 201", and all five of `ErrorStatusHttpTest` failed.
The fixes were then restored.

Worth knowing for the future: pre-fix, `ErrorStatusHttpTest` does not fail
with a tidy status assertion. The `401` makes the JDK's
`HttpURLConnection` attempt an authentication retry it cannot perform on an
already-streamed body, so the assertion is never reached and the failure
reads `"I/O error ... cannot retry due to server authentication, in
streaming mode"`. That message means the server answered `401` — i.e. this
regression — not that the test's HTTP plumbing broke. This is recorded in
the test class itself.

## The full-path integration test that didn't exist

Every boundary was tested, but always with one link stubbed out:

| Test | HTTP | Engine | Postgres | Redis | WebSocket |
|---|---|---|---|---|---|
| `OrderControllerTest` | ✅ | ✅ | ✅ | ✗ no container | ✗ |
| `MarketDataServiceTest` | ✗ direct service calls | ✅ | ✅ | ✅ | ✗ |
| `WebSocketPublisherTest` | ✅ | ✅ | ✅ | ✗ omitted on purpose | ✅ |

So nothing asserted that one REST call produces a persisted trade **and** a
refreshed Redis snapshot **and** a broadcast message. A regression in how
the two `@EventListener`s coexist — one throwing and suppressing the other,
an ordering assumption, a transaction-boundary change — could not have been
caught.

`FullOrderFlowIntegrationTest` closes that: Testcontainers Postgres *and*
Redis, a real embedded server, a real STOMP-over-SockJS client, one
continuous flow asserting all four.

Two deliberate choices in it:

- **A partial fill, not a clean one.** A full match empties the book, and
  an empty Redis snapshot is indistinguishable from one that was never
  written. Leaving 6 of 10 resting means the Redis assertion has to match a
  specific non-empty value.
- **No polling for Redis.** `MarketDataService`'s listener runs
  synchronously on the calling thread, after commit and before the POST
  response is written, so Redis is already current when the request
  returns. Only the WebSocket assertion needs a timeout, because only that
  one crosses the network.

## The concurrency race: discovered, measured, not fixed

`docs/matching-engine.md` had stated that `OrderBook` is not thread-safe
but that `TradeService` "serializes access implicitly by running inside a
single `@Transactional` method per request." **That claim was false**, and
this phase disproved it: a transaction boundary protects the database, not
an in-memory data structure. Two simultaneous `POST /api/orders` for the
same symbol run `submitOrder` on the same book from two Tomcat worker
threads at once, and nothing prevents it.

`ConcurrentOrderSubmissionTest` fires 32 concurrent submissions and asserts
*conservation* invariants — properties that hold under any correct
interleaving — rather than any particular ordering. It failed on the first
run and again on a second, **with different magnitudes each time**, which is
what distinguishes a race from an off-by-one:

| Probe | Run 1 | Run 2 |
|---|---|---|
| 32 buys of 1 vs. a resting sell of 32 | 32 traded, only 30 consumed | 32 traded, only 29 consumed |
| 32 resting sells at one price | book held 30 of 32 | book held 29 of 32 |

Two distinct failure modes, **both entirely silent** — every request
returned `201`, and no exception was thrown or logged in either run:

1. **Phantom liquidity.** More was traded away than the resting order gave
   up. 32 units were sold and durably persisted as 32 trades while the
   order that supplied them still showed 2–3 units remaining. Those units
   do not exist, are still being offered, and will be sold *again*.
   `EngineOrder.reduceRemainingQuantity` is a plain read-modify-write on a
   mutable field; concurrent decrements are lost.
2. **Lost resting orders.** With no matching involved at all, orders
   accepted and persisted as `OPEN` never entered the book. Postgres and
   the engine diverge immediately: those orders can never match, and
   cancelling one returns `409` because the book has no record of it.

This is a **different mechanism** from the Phase 3 divergence limitation,
though the symptom overlaps. Phase 3 is a failed database write leaving the
book ahead of Postgres, and it heals on restart because Postgres is the
source of truth. Here the book is ahead of nothing — the corrupt state *is*
what got persisted, so the startup reload faithfully restores the wrong
numbers.

**Fixing it was explicitly out of scope for this phase**, and the fix is
not the obvious one. Marking `submitOrder` `synchronized` would serialize
every symbol against every other, because the lock would be per-`OrderBook`
while the contention needing removal is per-symbol; and the critical
section has to span the match *and* the persistence that follows it, or the
same divergence reappears. The plausible designs — a per-symbol lock held
across both, or a single-writer queue per book — differ materially in
throughput. That is a design decision, not a one-line change.

The test is `@Disabled` so the suite stays green, kept as executable
evidence rather than deleted, and runnable on demand:

```
mvn test -Dtest=ConcurrentOrderSubmissionTest
```

It is the acceptance criterion for the eventual fix and should be
re-enabled as part of it. Note this differs from how the Phase 3 limitation
is handled — that one has a test asserting the broken behaviour directly —
because a race has no single deterministic outcome to assert.

Full write-up in
[`docs/matching-engine.md`](../matching-engine.md#known-limitation-concurrent-submissions-corrupt-the-book-phase-8);
summarised in [`docs/architecture.md`](../architecture.md#known-limitations)
alongside the Phase 3 limitation.

## Smaller gaps closed

- **JWT rejection through the real filter chain.** `JwtServiceTest` proved
  `JwtService.parse` throws on expired and tampered tokens, but by calling
  the service directly. `JwtAuthenticationFilterHttpTest` covers what that
  structurally cannot: that `JwtAuthenticationFilter` *catches* those
  throws and translates them into "unauthenticated" rather than letting
  them escape, and that the resulting `401` carries the standard
  `ErrorResponse` shape. Expired, tampered, garbage, and missing-`Bearer`
  cases all produce identical responses, so a caller learns nothing about
  why their token was refused. Includes a control case with a valid token —
  without it, every other assertion would still pass if the filter simply
  rejected everything.
- **Engine-level guards.** `OrderBook.submitOrder`'s symbol-mismatch throw,
  all four `restoreRestingOrder` throws, and `EngineOrder`'s "price is
  required for LIMIT orders" invariant are now tested at the unit level
  where they are thrown, rather than incidentally through an HTTP request
  that happened to reach one of them. Includes a positive case, so the
  guards are shown not to be over-eager.
- **`OrderBookWarmupRunner` as a wired bean.** `OrderBookLoaderTest`
  constructs `OrderBookLoader` directly, which is correct for a class with
  no Spring dependency, but leaves the wiring untested.
  `OrderBookWarmupRunnerTest` asserts the bean is discoverable *as an
  `ApplicationRunner`* — without which every other assertion would pass
  while the real application silently started with an empty book — and that
  one invocation rebuilds both the in-memory registry and the Redis
  projection from the same query. Both containers are needed precisely
  because the runner's job is to leave those two in agreement. It also
  carries the Phase 5 startup-crash regression at the level where it
  actually killed startup.

## An environmental issue worth recording

Maven runs this build on **JDK 26** while the project targets Java 21.
Mockito's inline mock maker cannot instrument concrete classes there —
`@MockBean` on a concrete class fails the entire application context with
*"Byte Buddy could not instrument all classes within the mock's type
hierarchy."*

This had never surfaced because no existing test mocked a concrete class;
Phases 3–7 only ever spied repository *interfaces*, which mock fine.
`ErrorStatusHttpTest` was the first to try, and works around it by spying
`OrderRepository` instead — the same pattern `TradeServiceTest` already
uses. **Any future attempt to mock a concrete class will fail** until the
toolchain or the Mockito version changes.

## Milestone

`mvn test` from `backend/` — 88 tests, 86 executing, 2 skipped:

```
$ mvn test
...
[WARNING] Tests run: 2, Failures: 0, Errors: 0, Skipped: 2 -- in com.trademesh.backend.concurrency.ConcurrentOrderSubmissionTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.controller.AuthControllerTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.controller.MarketControllerTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.controller.OrderControllerTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.engine.EngineOrderTest
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.engine.OrderBookTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.event.OrderStateChangeTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.integration.FullOrderFlowIntegrationTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.security.JwtAuthenticationFilterHttpTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.security.JwtServiceTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.MarketDataResilienceTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.MarketDataServiceTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.OrderBookAggregatorTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.OrderBookLoaderTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.OrderBookWarmupRunnerTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.service.TradeServiceTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.web.ErrorStatusHttpTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.websocket.WebSocketMessageMappingTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.trademesh.backend.websocket.WebSocketPublisherTest
[WARNING] Tests run: 88, Failures: 0, Errors: 0, Skipped: 2
[INFO] BUILD SUCCESS
```

The 31 tests added this phase: 4 price-validation (`OrderControllerTest`),
6 engine guards (`OrderBookTest`), 4 `EngineOrderTest`, 5
`ErrorStatusHttpTest`, 6 `JwtAuthenticationFilterHttpTest`, 1
`FullOrderFlowIntegrationTest`, 3 `OrderBookWarmupRunnerTest`, and 2
skipped `ConcurrentOrderSubmissionTest`.

## Still out of scope

- **A fix for the concurrency race** — discovery only this phase, by
  design. See above for why it needs its own pass, and
  `docs/matching-engine.md` for the candidate designs. **This should be
  resolved before Phase 9 exposes the engine to more than one caller at a
  time** — a single deployed instance with two browser tabs reproduces it.
- **A trade-history REST endpoint.** The audit confirmed
  `TradeRepository.findBySymbolOrderByExecutedAtDesc` still has zero
  production callers, so the Phase 7 frontend's trade tape remains
  live-only. Deferred deliberately: it is a feature, not hardening.
- Automated frontend tests (Phase 7 shipped with none; unchanged here)
- Flyway migrations
- The Phase 3 in-memory/DB divergence-on-persistence-failure limitation
  (unchanged by this phase — see `docs/architecture.md`)
- Deployment (Phase 9)
