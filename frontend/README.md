# TradeMesh frontend

Next.js (App Router) dashboard for the TradeMesh matching engine: live order
book, order entry, cancellation, and a live trade tape.

- **Framework:** Next.js 16 (App Router, TypeScript, Turbopack)
- **Styling:** Tailwind CSS v4
- **Realtime:** `@stomp/stompjs` over SockJS

## Running locally

Three processes, in this order.

**1. Postgres + Redis** (from the repo root):

```
docker compose up -d
```

**2. The backend** (from `backend/`) — serves on **:8080**:

```
mvn spring-boot:run
```

**3. This app** (from `frontend/`) — serves on **:3000**:

```
npm install       # first time only
npm run dev
```

Then open <http://localhost:3000>. You'll land on `/login`; use **Create one**
to register, which signs you in and redirects to the dashboard.

> **This will not work until the backend allows cross-origin requests from
> `http://localhost:3000`.** See "Backend CORS" below — every API call fails
> with a network error until that's configured.

## Configuration

All endpoints come from env vars — nothing is hardcoded — so this can be
pointed at a deployed backend later without a code change. Copy
[`.env.example`](.env.example) to `.env.local` and adjust:

| Variable | Default | Purpose |
|---|---|---|
| `NEXT_PUBLIC_API_BASE_URL` | `http://localhost:8080` | REST base URL, no trailing slash |
| `NEXT_PUBLIC_WS_URL` | `http://localhost:8080/ws` | SockJS endpoint — `http(s)`, **not** `ws(s)` |
| `NEXT_PUBLIC_SYMBOLS` | `AAPL,MSFT,GOOG` | Symbols the switcher offers; the first is the default |

These are `NEXT_PUBLIC_*`, so they're inlined into the client bundle at build
time. That's correct here — they're endpoint locations, not secrets. Never put a
secret in this file.

## Backend CORS

The backend currently has **no CORS configuration at all** — no
`CorsConfigurationSource` bean, no `@CrossOrigin`, no `addCorsMappings`. Since
the browser treats `localhost:3000` and `localhost:8080` as different origins,
every REST call from this app is blocked before it reaches Spring.

WebSocket traffic is *not* affected: `WebSocketConfig` already sets
`.setAllowedOriginPatterns("*")` on the `/ws` endpoint, so STOMP/SockJS connects
fine. Only `/api/**` needs the fix.

The change (backend-side, not made in this phase) is a CORS source wired into
`SecurityConfig`:

```java
@Bean
public CorsConfigurationSource corsConfigurationSource(
        @Value("${cors.allowed-origins}") List<String> allowedOrigins) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(allowedOrigins);
    config.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    // No setAllowCredentials(true): auth is a Bearer header, not a cookie, so
    // credentialed requests are not needed -- and enabling it would forbid the
    // "*" origin pattern anyway.
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
}
```

...plus `.cors(Customizer.withDefaults())` on the `HttpSecurity` chain, and
`cors.allowed-origins: http://localhost:3000` in `application.yml` (env-var
overridable, same pattern as `JWT_SECRET`, so a deployed frontend origin can be
supplied at Phase 9 without a rebuild).

The preflight `OPTIONS` request carries no `Authorization` header, so it must not
require authentication. Spring Security's CORS filter runs ahead of the
authorization rules and short-circuits preflights once `.cors(...)` is enabled,
so no extra `permitAll()` rule is needed.

## Structure

```
src/
├── app/
│   ├── layout.tsx            root layout, mounts AuthProvider
│   ├── page.tsx              routes to /dashboard or /login
│   ├── login/, register/     auth pages
│   └── dashboard/
│       ├── layout.tsx        client-side route guard
│       └── page.tsx          composes the dashboard
├── components/               OrderBookView, TradeHistory, OrderForm,
│                             OpenOrdersList, OrderHistoryList, …
├── context/AuthContext.tsx   token state, login/register/logout, 401 handling
├── hooks/
│   ├── useMarketStream.ts    STOMP lifecycle + order book + trade tape
│   └── useOrders.ts          the user's orders, updated from ORDER_UPDATED
└── lib/                      config, wire types, fetch wrapper, JWT storage
```

State is plain React state plus one context for auth — no external store. The
only genuinely shared state is the auth token; everything else is owned by the
dashboard and passed down.

## Checks

```
npx tsc --noEmit     # types
npx eslint src       # lint
npm run build        # production build
```
