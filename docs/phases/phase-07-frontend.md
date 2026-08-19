# Phase 7: Next.js Frontend

The first code outside `backend/`: a Next.js dashboard (`frontend/`) over the
REST API from Phase 4, the market-data endpoint from Phase 5, and the
WebSocket topics from Phase 6. Login/register, a live order book, order
entry, cancellation, and a live trade tape.

Next.js 16 (App Router, TypeScript, Turbopack), Tailwind CSS v4, and
`@stomp/stompjs` over `sockjs-client` — the last chosen to match the
backend's own `.withSockJS()` endpoint registration rather than reaching for
the raw-WebSocket transport underneath it.

Unlike Phases 2–6, this phase has no living design doc of its own: there is
no `docs/frontend.md`, and this file is the detail reference that
[`docs/architecture.md`](../architecture.md) points at. Run instructions and
configuration live in [`frontend/README.md`](../../frontend/README.md),
next to the code they describe.

## What was built

**Pages** (`frontend/src/app/`):

- `/login`, `/register` — the auth endpoints from Phase 4. Registration
  returns only a `userId`, no token, so it signs in immediately afterwards
  rather than bouncing the user to a form they just filled out.
- `/dashboard` — the single-symbol view: best bid/ask, depth ladder, live
  trade tape, open orders, order history, and the order-entry form.
- `/` — routes to `/dashboard` or `/login` once the auth context has
  rehydrated.
- `dashboard/layout.tsx` — the route guard. Deliberately a UX guard, not a
  security boundary: it runs in the browser and can be bypassed trivially.
  It doesn't need to be more, because every order route is authenticated
  server-side and a request without a valid token gets a `401` regardless of
  what this component renders.

**Components** (`frontend/src/components/`): `OrderBookView` (plus
`BestQuotes`), `TradeHistory`, `OrderForm`, `OpenOrdersList`,
`OrderHistoryList`, `ConnectionIndicator`, `SymbolSelector`, plus
`AuthShell` and a small `ui.tsx` of shared primitives (panel shell, spinner,
error/success banners, form field, button classes).

**State** (`frontend/src/context/`, `frontend/src/hooks/`): plain React
state plus one `AuthContext`, with two hooks doing the real work —
`useMarketStream` (STOMP lifecycle, order book, trade tape) and `useOrders`
(the user's orders, updated from `ORDER_UPDATED`). No Redux, no Zustand:
the only genuinely shared state in the app is the auth token, which is what
context is for. Everything else is owned by the dashboard and passed down
one level. Reaching for a store here would have added indirection without
removing any.

**Wire layer** (`frontend/src/lib/`): `types.ts` mirrors the backend's DTOs
field-for-field; `api.ts` wraps `fetch` with the `Authorization` header, the
backend's `ErrorResponse` shape, and a global `401` hook; `config.ts` reads
every endpoint from `NEXT_PUBLIC_*` env vars so nothing is hardcoded ahead of
a Phase 9 deployment; `auth-storage.ts` handles token persistence and claim
inspection.

## JWT storage: `localStorage`, with the upgrade path written down

An httpOnly cookie is strictly better against XSS — script on the page cannot
read it, so an injected script cannot exfiltrate the token. `localStorage`
has no such protection. It was still the right call for this phase, and the
reasoning is worth recording because it is about scope, not about
`localStorage` being good:

- **Switching is not a frontend change.** `JwtAuthenticationFilter` reads
  `Authorization: Bearer` and nothing else. Cookies would need the backend to
  set one on login and read it in the filter.
- **CSRF is deliberately disabled** (`SecurityConfig`), and that is *correct
  today precisely because nothing is cookie-based*. Cookie auth would make
  every authenticated route CSRF-attackable and require re-enabling CSRF
  protection. Shipping the cookie half of that from a frontend-only session,
  without the CSRF half, would be a real security regression.
- The blast radius of a stolen token here is a one-hour session against
  simulated orders.

**This is a known upgrade path, not a final answer.** If this ever handled
real value the fix is httpOnly + SameSite cookies, CSRF re-enabled, and
short-lived access tokens with a refresh endpoint — none of which exists
backend-side yet. The reasoning is duplicated in `auth-storage.ts`'s class
comment so it is visible at the point of change.

### Expiry: proactive and reactive, because neither alone is enough

- **Proactive** — a timer scheduled off the token's decoded `exp` ends the
  session on schedule instead of at whatever moment the user next clicks
  something. The decode is deliberately **unverified** (this client has no
  signing key) and is used only for presentation and for this timer, never
  to decide whether a request is allowed.
- **Reactive** — any `401` from any call clears the session and redirects to
  `/login` with a message explaining why. This is not redundant with the
  timer: a token can stop being accepted for reasons the client cannot see —
  a backend restart with a different `JWT_SECRET`, clock skew — and those
  only ever surface as a `401`.

## WebSocket lifecycle: the library reconnects, the hook re-syncs

Reconnection is entirely `@stomp/stompjs`'s own (`reconnectDelay: 5000`, plus
10s heartbeats in both directions to detect a connection that has gone silent
without a clean close). Nothing is hand-rolled. What `useMarketStream` adds is
the part the library cannot do on its own:

- **Subscriptions are created inside `onConnect`, not once at setup.** STOMP
  subscriptions are per-connection and die with the socket; `onConnect` fires
  again after every successful retry, so all three topics are re-established
  there. Handles from the dead connection are discarded rather than
  unsubscribed — unsubscribing them would itself fail.
- **The REST snapshot is re-fetched on every (re)connect.** While the socket
  was down, book updates were missed, so the locally-held book is stale by an
  unknown amount and only a fresh snapshot can be trusted. On the first
  connect this merely confirms the snapshot already fetched on mount; after a
  reconnect it is the only recovery path.
- **A sequence counter guards the resulting race.** The counter increments on
  every snapshot arriving over the socket; an in-flight REST fetch records the
  value it started at and discards its own result if the counter moved. Without
  it, a slow HTTP response could overwrite a newer snapshot that arrived over
  the socket while it was in flight.
- **`deactivate()` on cleanup**, which unsubscribes, disconnects, *and*
  cancels any pending reconnect — so navigating away doesn't leave a client
  retrying forever in the background.

Switching symbols tears the connection down and rebuilds it for the new
symbol, so only one symbol's traffic is ever consumed.

## A bug avoided: what "open order" actually means

The obvious implementation of an open-orders list — filter on
`status == OPEN || PARTIALLY_FILLED` — is wrong here, and
[`docs/matching-engine.md`](../matching-engine.md) says so directly: the
engine reuses `OPEN` to mean "nothing filled" for a `MARKET` order that never
rests, because `OrderStatus` has no fifth state to give it.

The consequence for this UI is concrete. An unfilled `MARKET` order is
persisted with `status = OPEN` despite having been discarded by the engine
immediately. A status-only filter would list it as open and render a Cancel
button next to it — a button that can only ever produce a `409`, because
`TradeService.cancelOrder` asks the live book first and the order was never in
it. Every user who ever submitted a market order into a thin book would find a
permanently un-cancellable row in their open orders.

`useOrders.isWorking()` therefore requires `type === "LIMIT"` **and**
`remainingQuantity > 0` alongside the status check — which is exactly the
guidance the matching-engine doc gives callers: check the type and the
remaining quantity, don't lean on `OPEN` alone. This was caught by reading
that doc while building the list, not by hitting the `409` in the browser.

## The CORS regression, found and fixed in the companion backend session

Enabling CORS was a separate, backend-only session (it produced
`backend/src/main/java/com/trademesh/backend/security/CorsConfig.java` and the
`.cors(...)` line in `SecurityConfig`), but it is recorded here because
without it none of the above works in a browser, and because the interesting
part was a regression the obvious fix introduced.

The starting state was that the backend had no CORS configuration at all:
preflight `OPTIONS /api/auth/login` returned `403`, and
`GET /api/market/{symbol}/orderbook` returned `200` with no `Access-Control-*`
headers at all — so even the deliberately-public order book was unreadable
from a browser on another origin.

The trap: **Spring Security's `CorsFilter` rejects a preflight whose path has
no configuration, rather than passing it through.** `DefaultCorsProcessor`
answers a null config with `403 Invalid CORS request`. Adding
`.cors(Customizer.withDefaults())` places that filter ahead of the whole
chain, so with only `/api/**` mapped, `OPTIONS /ws/**` — which Spring's SockJS
service had been answering itself with `200` — silently started returning
`403`. Measured across all three states:

| Preflight | Before CORS | `/api/**` only | Final |
|---|---|---|---|
| `OPTIONS /api/auth/login` | `403` | `200` | `200` |
| `OPTIONS /api/orders` | `401` | `200` | `200` |
| `OPTIONS /ws/info` | `200` | **`403`** | `200` |
| `OPTIONS /ws/{server}/{session}/xhr_send` | `200` | **`403`** | `200` |

Nothing visibly broke while it was regressed — today's `sockjs-client` never
sends those preflights, because its transports are CORS "simple requests" —
which is exactly why it needed measuring rather than testing by hand. That is
a property of the current client, not a guarantee of the protocol.

The fix registers `/ws/**` with a pass-through configuration mirroring what
`WebSocketConfig` already grants on that endpoint (any origin, credentials
allowed), purely to restore the previous answer. It deliberately does **not**
narrow the WebSocket to `cors.allowed-origins`: that endpoint is
intentionally public (Phase 6, see [`docs/websocket.md`](../websocket.md)),
and changing its origin policy belongs with the endpoint in
`WebSocketConfig`, not in a CORS config added for the REST API.

Allowed origins are configurable — `cors.allowed-origins` in
`application.yml`, overridable via `CORS_ALLOWED_ORIGINS`, same pattern as
`JWT_SECRET` — so a deployed frontend origin can be supplied at Phase 9
without a rebuild.

## Verification

This phase has no automated test suite. That is a real difference from every
prior phase, and the distinction between what was exercised by hand and what
was checked programmatically is recorded precisely rather than blurred into
"tested".

**Verified manually, in a browser, against the live backend (with CORS
enabled):**

- Register a new account, and sign in.
- Place `BUY` and `SELL` orders.
- Cancel an order.

That is the whole of the hand-verified list. Notably absent, and not claimed:
nobody sat and watched the trade tape or the depth ladder update from a
*second* client's activity. The push path underneath them was exercised
programmatically (below), but the rendering of those live updates was not
deliberately verified in a browser.

**Verified programmatically, not in a browser:**

- `npx tsc --noEmit`, `npx eslint src`, and `npm run build` — all clean.
- All four routes served `200` from the dev server.
- The wire contracts in `types.ts`, checked against real responses: Jackson
  serializes `BigDecimal` as JSON **numbers** (not strings), `Instant` as
  ISO-8601 strings, and `expiresIn` in **seconds**.
- The STOMP-over-SockJS path, driven end-to-end by a throwaway Node script
  using the same `@stomp/stompjs` + `sockjs-client` stack: all three topics
  delivered, `ORDER_UPDATED` carried no `userId`, and `ORDER_BOOK_UPDATED`
  carried no `bestBid`/`bestAsk` — confirming those must be derived from
  `bids[0]`/`asks[0]` client-side.
- Every CORS assertion in the table above, by inspecting response headers
  directly.
- `mvn test` from `backend/` — **57/57**, unchanged, before and after the
  CORS change.

**Not verified:** reconnection behaviour under a genuinely dropped
connection. The reconnect path is the library's own and the re-subscribe and
re-sync logic is straightforward, but neither was exercised against a real
socket drop.

## Two smaller notes

- **`suppressHydrationWarning` on `<html>`** in `app/layout.tsx` is there for
  browser extensions that write attributes onto the root element between the
  server HTML arriving and React hydrating. It applies one level deep only —
  to that element's own attributes — so a genuine mismatch anywhere inside
  still reports normally.
- **Three `eslint-disable-next-line react-hooks/set-state-in-effect`
  comments** (in `AuthContext`, `useMarketStream`, `useOrders`) cover reading
  `localStorage` on mount and fetching on mount. Every state update in the
  fetch paths happens after an `await`, so none is the synchronous render
  cascade the rule guards against; the rule does not model the `await`
  boundary. The rule's other findings — two ref writes during render, and the
  per-symbol state reset — were restructured away rather than suppressed.

## Deferred: no trade history on load

The trade tape is **live-only**. It starts empty on every page load and fills
as trades execute, because no REST endpoint exposes trade history —
`TradeRepository.findBySymbolOrderByExecutedAtDesc` exists (added in Phase 3)
but no controller publishes it. Relatedly, `TradeExecutedMessage` carries no
timestamp, so the tape's time column is the moment the client received the
message and is labelled **"Received"** rather than passed off as the
execution time.

Both are out of scope for this phase, not oversights: adding the endpoint is
backend work, and this session was frontend-only. Together they are a good
candidate for a later small backend+frontend session — a
`GET /api/market/{symbol}/trades` endpoint (public, matching the existing
order-book endpoint's access decision) plus `executedAt` on the WebSocket
payload would fix both at once. Not urgent: the dashboard is fully usable
without it, and the gap is visible to the user rather than silent.

## Still out of scope

- Automated frontend tests (no Jest/Vitest/Playwright this phase)
- Trade history on load, per the section above
- A genuine per-user authenticated WebSocket channel (unchanged from Phase 6 —
  see [`docs/websocket.md`](../websocket.md))
- An HTTP endpoint for the Phase 5 manual Redis rebuild path (unchanged)
- Flyway migrations
- Deployment (Phase 9) — every endpoint is already env-configurable for it
- The Phase 3 in-memory/DB divergence-on-persistence-failure limitation
  (unchanged by this phase — see [`docs/architecture.md`](../architecture.md))
